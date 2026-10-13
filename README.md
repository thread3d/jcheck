# jcheck

A Rust-style feedback layer for Java: one command, one output format, precise actionable
diagnostics, and a clean check that means something.

jcheck drives javac in-process, normalizes every finding into one diagnostic model with
stable codes, gates the result by analysis tier, and renders it for humans, agents and CI.

- **[SPEC.md](SPEC.md)** — specification v0.1 (12 Oct 2026)
- **[docs/](docs/README.md)** — long-form pages behind `jcheck explain <code>`

## Build, run, test

Requires a JDK and nothing else — no Gradle, no Maven, no network.

```bash
./build.sh                 # compile + jar -> out/jcheck.jar
./jcheck check             # check the current project
./tests/run.sh             # build and run the whole test suite
./tests/run.sh --bless     # regenerate UI golden files (read the diff first)
```

## Commands

| Command | What it does |
| --- | --- |
| `jcheck check` | Fast path: in-process javac plus the jcheck check pack, no artifacts. |
| `jcheck build` | Runs a clean check first, then delegates to the build tool. |
| `jcheck test` | Delegates to the build tool, then renders JUnit XML reports as diagnostics. `--launcher` runs the tests through the JUnit Platform Console Launcher with `jcheck-junit` attached. |
| `jcheck fix` | Applies machine-applicable suggestions, re-checks, reverts if errors rose. `--error-prone` also runs Error Prone's patch mode, `--rewrite` OpenRewrite recipes. |
| `jcheck rewrite` | Applies OpenRewrite recipes, re-checks, and keeps the result only if errors did not rise. |
| `jcheck fmt` | google-java-format, or the project's Spotless task. |
| `jcheck explain <code>` | Long-form explanation: why it matters, failing and fixed example. |
| `jcheck init` | Writes `jcheck.toml`; `--agent` adds the agent instructions snippet. |
| `jcheck daemon start\|stop\|status\|run` | Warm daemon over a Unix domain socket. |
| `jcheck mcp` | Resources-only MCP server on stdio, for MCP-capable agent hosts. |
| `jcheck lsp` | Language Server Protocol bridge on stdio: diagnostics and quick fixes while you edit. |
| `jcheck mutate` | PIT mutation testing; surviving mutants become diagnostics. Scope with `--sources`. |
| `jcheck infer` | Facebook Infer over the project's sources, when the native binary is available. |
| `jcheck jcstress` | Run `@JCStressTest` classes with the OpenJDK Concurrency Stress Tests. |

Formats: `--message-format=human|json|llm|sarif`.
Exit codes: `0` clean (warnings allowed unless `--deny-warnings`), `1` diagnostics at error
level, `2` tool or configuration failure — deliberately distinct from "your code is wrong".

```
$ jcheck check --message-format=human
error[J0203]: missing return statement
  --> src/main/java/demo/M.java:4:29
 4 |     public int f(boolean b) {
   |                             ^ missing return
   = help: every path must return a value
   = source: javac (compiler.err.missing.ret.stmt)

analysis reached T2 of 5; T3-T5 skipped due to compile errors
jcheck: 1 error, 0 warnings.
```

## MCP interface

`jcheck mcp` serves jcheck's context over the Model Context Protocol, so an MCP-capable agent
host can pull it without shelling out.

**Resources only — no tools, deliberately.** The CLI already is the tool interface, and a second
way to ask for the same diagnostics would create exactly the "two modes the agent has to choose
between" that SPEC.md warns against. Resources are the part the CLI cannot express: pull-based
context that costs nothing until the agent asks for it.

| Resource | What it is |
| --- | --- |
| `jcheck://capabilities` | **The important one.** Which tiers actually run here, and where the guarantee ends |
| `jcheck://diagnostics` | Root-cause-first, capped diagnostic list in the agent-facing format |
| `jcheck://diagnostics.json` | The same findings as JSON Lines (versioned schema) |
| `jcheck://catalog` | Every code jcheck can emit, with its upstream key and tier |
| `jcheck://docs/{code}` | The long-form explanation, e.g. `jcheck://docs/J0201` |
| `jcheck://catalog/{code}` | One catalog entry, e.g. `jcheck://catalog/J0201` |
| `jcheck://suppressions` | Every suppression found, for auditing |

`jcheck://capabilities` is the reason this exists. The spec is emphatic that a quiet T3 must never
read as a clean T3, and that an agent must know where the guarantee ends — but in the CLI that
honesty is a note line the agent may skim. As a resource it is queryable on demand, and it derives
the gap from data rather than restating it: "catalogued but not implemented" is computed as the
catalog's jcheck-pack codes minus the ones with implementations, so it cannot silently drift.

**The server is the warm process.** An MCP server is long-lived for the agent's editing session,
so it holds the resolved build model and the last analysis, and re-runs javac only when the source
tree actually changed — a fingerprint over source file mtimes and sizes plus source-root directory
mtimes. That is the spec's warm-daemon goal achieved without a second process to supervise.
Subscribe to `jcheck://diagnostics` and the server sends `notifications/resources/updated` when the
tree changes.

Registering it with a host is a command plus an argument:

```jsonc
// e.g. an MCP host config
{ "command": "/path/to/jcheck/jcheck", "args": ["mcp", "--root", "/path/to/project"] }
```

Protocol notes: newline-delimited JSON-RPC 2.0 over stdio; version negotiation against
`2025-11-25`, `2025-06-18`, `2025-03-26` and `2024-11-05`; `-32002` for an unknown resource. The
transport requires that nothing but MCP messages reaches stdout, so all logging goes to stderr and
the server redirects `System.out` to stderr to defend the stream against any library that prints —
a stray `println` anywhere in the engine cannot corrupt a message.

Two deliberate, documented deviations: `listChanged` is **not** claimed (the resource list is
static, and claiming a capability we do not honour is worse than omitting it), and `tools/list`
answers with an empty array rather than an error so probing hosts are not confused, without
declaring a tools capability.

Verified against the official `@modelcontextprotocol/sdk` client, not only jcheck's own:
see `tests/mcp-sdk-verify/`.



## Design notes worth knowing

**Tiers are enforced, not inherited.** `-XDshould-stop.ifError=FLOW` lets javac continue past
errors, which is what makes analyzer findings visible at all — but it also means javac can reach
analysis on a file it only partly parsed. jcheck therefore suppresses any tier above the lowest
tier that produced an error, and says which tiers ran on every single run. A quiet T3 is never
presented as a clean T3. (This was a real bug during development: a `final`-suggestion was
reported on a file with a syntax error, because javac recovered enough to emit `ANALYZE`.)

**Cascades are dropped by name, not by position.** After an unresolved symbol, follow-on errors
are dropped only when they name the symbol javac said it could not resolve — read from javac's
own `symbol:` detail lines. The first version of this matched the literal word `class` out of
`symbol:   class Missing` and would have dropped any error that happened to mention "class".

**Dedupe is note-aware.** Diagnostics that share a catalog `group` merge across tools on location
alone, because tools word the same problem differently. Diagnostics that are their own group merge
only when code, file, line, *and detail lines* match — javac reports one missing type twice, and
merging on the message alone would have collapsed two genuinely different unresolved names.

**Catalog entries are data.** 215 rows in TSV resources map `(source, source_key)` to a stable
code, tier, curated headline and help lines. Unmapped keys are never dropped: they get a stable
`J9xxx` code derived from an FNV-1a hash of the key, and are flagged as needing curation.

## What is implemented

- **Build model** — Gradle/Maven/flat detection, source root and classpath discovery, `--sources`
  and `--classpath` overrides, and dependency-classpath resolution by running the project's own
  build tool. Limitations are stated in the output rather than glossed over.
- **Analysis runner** — in-process `JavaCompiler` with a diagnostic listener, real start/end
  spans from `Diagnostic.getStartPosition`/`getEndPosition`, `-Xlint`, `-Xdiags:verbose`,
  `--release`, `-proc:none`, and a retry path when a JDK rejects the hidden phase-gating options.
- **Tier gating T0–T5** with a per-run statement of which tiers ran and why the rest did not.
- **Diagnostic engine** — normalization, catalog codes, cross-tool dedupe, cascade suppression,
  `strict`/`standard`/`lenient` profiles, per-code `--deny`/`--warn`/`--allow`, deterministic
  ordering, and suppression auditing via `--report-suppressions`.
- **Four renderers** — rustc-style human output, JSON Lines with a versioned schema, a compact
  agent-shaped LLM format with diffs and a cap, and SARIF 2.1.0.
- **Check pack (T3, AST-based)** — the full pack runs through the standard
  `TaskListener`/`Trees` API rather than a new engine: `J0003 SuppressionNeedsReason`,
  `J0301 UncheckedArithmetic`, `J0302 LossyCast`, `J0601 SharedMutableWithoutGuard`,
  `J0602 MutationDuringIteration`, `J0603 EscapingMutableInternal`, `J0701 UnsafeOutsideUnsafe`,
  `J0705 MutableStaticState`, `J0706 LocalCanBeFinal`, `J0801 UnclosedResource`,
  `J0902 TodoMarker` and `J1001 UnclosedExecutorOrThread`. `J0706`, `J0302` and `J0301` carry
  machine-applicable fixes.
- **Error Prone and NullAway (T3)** — attached when `[errorprone] enabled = true`. Their jars are
  resolved from Maven Central on first use and cached under `~/.cache/jcheck/maven`; an explicit
  `classpath` list, a `file:` repository, or `--offline` avoids the network. Both need
  `--add-exports` on the JVM running javac, so they run in a forked JVM and report through the same
  diagnostic model and catalog (`J04xx` Error Prone, `J05xx` NullAway) as everything else.
- **Checker Framework (T4)** — attached when `[checkers.per-module]` or
  `[nullness] checker_framework` is set. Each configured checker runs as its own
  annotation-processor pass in the forked JVM, so a diagnostic is attributed to the checker that
  produced it instead of being guessed from a message key; the catalog maps them to `J0509`–`J0515`.
  Applied to the whole compilation rather than per module, which the tier line states.
- **SpotBugs (T5)** — attached when `[bytecode] spotbugs` is set. It runs over the class files the
  check compile produced, as its own process, and is asked for SARIF so the human message and source
  region survive; with FindSecBugs enabled its plugin jar is loaded too. Patterns map through the
  SpotBugs catalog to `J04xx`. If the compile produced no class files, T5 says so rather than
  reporting a quiet clean.
- **`jcheck-junit` (T5)** — `jcheck test --launcher` (or `[tests] launcher = "console"`) runs the
  project's tests through the JUnit Platform Console Launcher with a jcheck extension attached: a
  per-test timeout with a thread dump, thread-leak and file-descriptor-leak detection, uncaught
  exception capture, and optional randomized order with the seed printed. The extension source
  ships with jcheck and is compiled against the project's own JUnit Platform at test time, so
  jcheck carries no JUnit dependency and the extension always matches the platform version.
- **LSP bridge** — `jcheck lsp` speaks the Language Server Protocol on stdio from the same warm
  analysis the MCP server uses: diagnostics are published on open/change/save and
  `textDocument/codeAction` returns the machine-applicable suggestions as quick fixes.
- **Mutation testing (T5)** — `jcheck mutate` runs PIT, scoped with `--sources`, and reports each
  survivor (`J1101`) at its line. The project's JUnit Platform is placed ahead of PIT's own so a
  version clash cannot silently break discovery.
- **Coverage (T5)** — `jcheck test --launcher --coverage` (or `[tests] coverage = true`) runs the
  test JVM under the JaCoCo agent and reports uncovered and partly covered lines as `J1102`, so
  "the tests pass" and "the code is tested" stop being the same sentence.
- **Incremental recompilation (warm daemon)** — the MCP server and LSP bridge keep an API-hash
  index of the source tree. After an edit they recompile only the changed files plus the dependents
  whose files reference a changed API; fresh findings are merged with the cached ones for files that
  were not recompiled. Anything uncertain — a removed source, a changed classpath/flags/JDK, or an
  incremental compile that does not come back clean — falls back to a full re-check.
- **Infer (T5)** — `jcheck infer` runs Facebook Infer's Java frontend when the native binary is on
  `PATH` or pointed at by `[bytecode] infer_binary` / `$JCHECK_INFER`, and reports each finding as
  `J1401` with Infer's own qualifier and procedure. With `[bytecode] infer = true` the ordinary
  `jcheck check` runs it at T5 too. Its `infer-out` stays in the jcheck cache; when the binary is
  missing the command says so instead of reporting a quiet T5.
- **Concurrency stress (T5)** — `jcheck jcstress` compiles the project's `@JCStressTest` sources
  with the jcstress annotation processor when no `META-INF/TestList` exists yet, runs them, and
  reports every forbidden or interesting outcome as `J1201`.
- **OpenRewrite recipes (fix)** — `jcheck rewrite` (or `jcheck fix --rewrite`) resolves
  `rewrite-static-analysis`, compiles a small runner against those jars at run time, and applies the
  configured recipes (by default MissingOverrideAnnotation, FinalizeLocalVariables and
  UseTryWithResources) in a forked JVM, one recipe per pass so each change keeps its own name.
  Sources are snapshotted first and every edit is reverted when the re-check gets worse, like the
  other fix paths. Every applied change is reported with a catalog code — J1501 MissingOverride,
  J1502 FinalizeLocalVariables, J1503 UseTryWithResources, J1504 UnnecessaryParentheses, J1505
  EmptyNewlineAtEndOfFile, J1506 CodeCleanup, J1507 Kotlin trailing semicolons, or J1500 for a recipe
  with no curated row — so a rewrite is auditable in the same model as any other finding. Recipes
  come from any library on the runner classpath: `rewrite-static-analysis`, `rewrite-java` itself,
  `rewrite-kotlin`, and anything added through `[fix] rewrite_libraries`. Java and Kotlin sources are
  both rewritten (Kotlin is found under `src/main/kotlin` and friends and never handed to javac),
  and the Kotlin frontend is loaded reflectively so `[fix] rewrite_kotlin = false` still works.
  jcheck itself has no OpenRewrite dependency.
- **Build-tool models** — with a Gradle installation present, `jcheck` reads the project through the
  **Gradle Tooling API**: every subproject, its source sets and its resolved classpath, compiled from
  a small runner and cached by build-file fingerprint. With a Maven installation, the classpath is
  resolved through the **Maven embedder** in process, so no `mvn` process is spawned. Both need the
  installation's `lib/` (set `[project] gradle_home` / `maven_home` or the matching environment
  variable) and fall back to running the project's wrapper or binary, then to the heuristic
  classpath, saying which path was used. A Maven build's source roots and modules still come from its
  POMs.
- **Multi-module builds** — the Tooling API supplies a Gradle build's modules and source sets
  directly; a Maven `pom.xml` supplies its declared `<modules>` and each module's
  `<sourceDirectory>`/`<testSourceDirectory>`, so a module with `src/java` instead of
  `src/main/java` is found by the declaration. When neither is available, the model walks for module
  source roots (`module-a/src/main/java`, `group/module-b/src/test/java`, …) up to five levels deep,
  pruning build outputs, VCS metadata and hidden directories. A `--sources` list or
  `[project].sources` still wins over all of it.
- **Daemon** — length-prefixed binary protocol over a Unix domain socket, keyed by a hash of the
  project root so serving a project never writes into it. `jcheck check` uses a running daemon
  automatically and falls back in-process. A dropped connection, a malformed frame or a throwing
  check leaves it serving; an out-of-memory check is reported and the daemon stops so the client
  starts a fresh one rather than hanging on a half-dead JVM.
- **MCP server** — resources-only, stdio JSON-RPC 2.0, with the server acting as the warm
  per-session process and source-change subscriptions. See [MCP interface](#mcp-interface).
- **`init`, `fix`, `explain`, `fmt`, `build`, `test`** — including a JUnit XML parser that lifts
  project stack frames above framework frames.
- **Tests** — 1403 assertions: unit tests plus 22 rustc-style UI golden cases under
  `tests/ui/cases/`. A strict hand-written JSON validator checks the JSON and SARIF output. Set
  `JCHECK_TEST_PLUGINS=1` to opt into the end-to-end analyzer cases (Error Prone, NullAway, the
  Checker Framework, SpotBugs, jcstress, OpenRewrite, the twenty-one-library corpus and
  `jcheck-junit`), which need downloaded jars; that run has 1519 assertions on JDK 17 and 20, 1516 on
  21 and 1508 on 25, where an analyzer or corpus library that does not support the JDK is skipped
  rather than counted green.
- **`jcheck-support`** — a small companion artifact with the annotations the pack recognizes
  (`@Checked`, `@ThreadSafe`, `@GuardedBy`, `@Unsafe`, `@Immutable`, `@SuppressJcheck`), the
  `Todo()`/`Unreachable()`/`Unimplemented()` markers behind J0902, and a sealed `Result` type.
  `./build.sh` produces `out/jcheck-support.jar`.

## What is not implemented yet

Stated plainly, because a tool whose whole premise is "a clean check means something" must not
overstate itself:

- **Build-tool coverage is best-effort, not universal.** The Gradle Tooling API and the Maven
  embedder both need that tool's installation on the machine; without it jcheck runs the project's
  wrapper or binary, and if that is absent too it keeps the heuristic classpath and says so. Gradle
  source sets for non-Gradle layouts, and build tools other than Gradle and Maven (Bazel, sbt, Ant),
  have no dedicated path.
- **OpenRewrite coverage is opt-in.** `jcheck rewrite` runs the recipes named in
  `[fix] rewrite_recipes`; the recipe libraries are whatever `rewrite-static-analysis`,
  `rewrite-java`, `rewrite-kotlin` and `[fix] rewrite_libraries` put on the runner classpath, and a
  recipe that is not there fails loudly rather than being skipped. Java and Kotlin sources are
  rewritten; other OpenRewrite frontends (Python, YAML, …) are not wired up.

## Testing the tool

`./tests/run.sh` builds jcheck and runs the unit tests, the 22 rustc-style UI golden cases and the
MCP tests; the default suite needs only a JDK, no network. `JCHECK_TEST_PLUGINS=1` adds the
end-to-end analyzer cases — Error Prone, NullAway, the Checker Framework, SpotBugs, jcstress and
`jcheck-junit` — which download and cache their jars. The Infer case runs against a native binary
when one is present and skips otherwise.

Not covered here, and stated so a green run is not mistaken for more than it is: a broad
false-positive corpus. The suite runs the T3 pack over twenty-one real libraries' `-sources` jars —
Commons Lang, Gson, Commons Collections 4, Commons Codec, Commons CLI, Commons Math, SnakeYAML,
jsoup, ASM, SLF4J, RxJava, Commons IO, Commons Text, Commons CSV, Guava, Jackson Databind, JetBrains
Annotations, ANTLR4 runtime, Commons Exec, Commons Pool and JSR-305, **1.39M lines** in total — and
asserts that each library which compiles there reaches T3, finds something (a tiny annotations jar
may legitimately be clean), stays under 250 diagnostics per kLOC, keeps any single code under 60 per
kLOC, and leaves no upstream key uncatalogued, against the checked-in measurements in
`tests/corpus/baseline.txt` (re-measure with `JCHECK_CORPUS_BLESS=1`); it currently reports about 17
per kLOC. Sources are extracted under `src/main/java` and a file that is not valid UTF-8 is kept as
Latin-1, so the corpus looks like the projects themselves; a library that needs a companion jar
(RxJava needs reactive-streams, Guava its annotation jars, Jackson fastdoubleparser, Commons Pool
cglib) gets it in the project's `lib/`, the way a real build would. Commons Collections 4.4 does not
compile on JDK 21 or 25, so it is skipped on those rather than counted; twenty-one libraries are
still not every library. The corpus has already earned its keep by catching two real bugs: a package
directory named `build` was silently dropped from the source list (commons-io has
`org.apache.commons.io.build`), and a diagnostic pointing into an already-closed jar filesystem
failed the entire run instead of degrading to a line/column span. The suite runs on JDK 17, 20, 21
and 25, and covers Java 8 and 11 as `--release` targets with the installed JDK 8 and 11 compilers; on
25 the analyzers that do not support it are skipped rather than counted green. The daemon
tests cover classpath change, stale cache, concurrent requests, malformed frames, shutdown and
out-of-memory recovery (a constrained-heap daemon must answer rather than hang, and a fresh one must
come up afterwards). Every catalog entry is checked to have an implementation, every
machine-applicable suggestion is applied and re-checked, and the incremental planner is tested for
the body-change, API-change and classpath-change rules.

## Environment

Built and tested on **JDK 17, 20, 21 and 25**, all installed under the workspace's `tools/`
directory: jcheck itself and the built-in T3 pack pass the full suite — UI goldens included — on all
four, with no javac wording drift across them. **JDK 17 is the floor**, because jcheck's own source
uses records, switch expressions and Unix domain sockets; `build.sh` and the `jcheck` launcher refuse
anything older with a message that says so instead of a wall of syntax errors. SPEC.md assumes 21 or
25, so the tool runs on one release older than the spec requires.

Java **8 and 11** projects are covered the way `--release` intends: as target language levels rather
than as the JDK jcheck runs on. The suite checks the same fixture at release 8, 11 and 17 and asserts
the level is actually enforced (`var`, records and text blocks are rejected at 8; `List.of` is
rejected only when targeting 8), and the installed JDK 8 and 11 compilers confirm those fixtures
independently, so the JDKs under `tools/` are real inputs to the matrix and not just downloads. The
JDK-21 baseline in the spec still matters for the *checked code*: pattern switch exhaustiveness and
`-Xlint:this-escape` need 21+. UI goldens embed javac's own message wording, which is the reason
SPEC.md lists a JDK matrix as a test dimension. The pinned analyzers (Error Prone 2.24.1, NullAway
0.10.18, SpotBugs 4.8.6) support JDK 17-21: on JDK 25 they cannot run, so their end-to-end cases are
skipped there and the pins must be bumped to releases that support 25; when an analyzer cannot run,
jcheck reports a plugin failure on the tier line rather than a quiet T5. The attached plugins
are pinned to Error Prone **2.24.1**, NullAway **0.10.18** (with NullAway's `dataflow-nullaway` and
JSpecify pulled in transitively), the Checker Framework **3.42.0**, SpotBugs **4.8.6**, PIT
**1.15.8**, JaCoCo **0.8.12**, jcstress **0.16** and OpenRewrite (via `rewrite-static-analysis`
**2.41.1**, which pulls `rewrite-java` 8.90.2), verified on JDK 17, 20 and 21. All of them are
configurable in `jcheck.toml`. Infer is a native binary and is located rather
than resolved; the workspace copy used here is v1.2.0.

Environment variables, all optional: `JCHECK_CACHE_DIR` moves the cache,
`JCHECK_MAVEN_REPO` and `JCHECK_GRADLE_HOME` point at a Maven repository and a Gradle user home,
`JCHECK_MAVEN_INSTALLATION` and `JCHECK_GRADLE_INSTALLATION` point at the Maven and Gradle
installations the embedder and the Tooling API load their `lib/` from, `JCHECK_INFER` is the Infer
binary, `JCHECK_TEST_PLUGINS=1` runs the opt-in analyzer and corpus cases,
`JCHECK_CORPUS_BLESS=1` re-records `tests/corpus/baseline.txt`, and `JCHECK_DEBUG=1` prints a stack
trace for an internal error.

## Layout

```
src/main/java/jcheck/
  model/       Diagnostic, Span, Tier, TierReport, CheckReport
  catalog/     (source, source_key) -> stable code, from TSV resources
  engine/      javac runner, normalization, tier gating, cascade, dedupe, level policy
  render/      human, json, llm, sarif
  config/      jcheck.toml reader
  build/       build model
  cli/         commands
  daemon/      Unix-socket server and client
  mcp/         MCP resources-only server, resource providers, warm session state
  plugins/     Maven resolver, forked plugin runner, Error Prone/NullAway integration
  fix/ init/ explain/ testharness/
src/main/resources/jcheck/catalog/*.tsv   185 catalog rows
support/java/jcheck/support/              jcheck-support annotations, Todo markers, Result
docs/                                     explain pages
tests/java/                               unit tests and the golden runner
tests/ui/cases/                           rustc-style golden cases
tests/mcp-sdk-verify/                     opt-in MCP check against the official SDK
```
