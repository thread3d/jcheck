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
| `jcheck fix` | Applies machine-applicable suggestions, re-checks, reverts if errors rose. `--error-prone` also runs Error Prone's patch mode. |
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

**Catalog entries are data.** 186 rows in TSV resources map `(source, source_key)` to a stable
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
  `J1401` with Infer's own qualifier and procedure. Its `infer-out` stays in the jcheck cache; when
  the binary is missing the command says so instead of reporting a quiet T5.
- **Concurrency stress (T5)** — `jcheck jcstress` compiles the project's `@JCStressTest` sources
  with the jcstress annotation processor when no `META-INF/TestList` exists yet, runs them, and
  reports every forbidden or interesting outcome as `J1201`.
- **Daemon** — length-prefixed binary protocol over a Unix domain socket, keyed by a hash of the
  project root so serving a project never writes into it. `jcheck check` uses a running daemon
  automatically and falls back in-process.
- **MCP server** — resources-only, stdio JSON-RPC 2.0, with the server acting as the warm
  per-session process and source-change subscriptions. See [MCP interface](#mcp-interface).
- **`init`, `fix`, `explain`, `fmt`, `build`, `test`** — including a JUnit XML parser that lifts
  project stack frames above framework frames.
- **Tests** — 1245 assertions: unit tests plus 22 rustc-style UI golden cases under
  `tests/ui/cases/`. A strict hand-written JSON validator checks the JSON and SARIF output. Set
  `JCHECK_TEST_PLUGINS=1` to opt into the end-to-end analyzer cases (Error Prone, NullAway, the
  Checker Framework, SpotBugs, jcstress and `jcheck-junit`), which need downloaded jars; that run
  has 1260 assertions.
- **`jcheck-support`** — a small companion artifact with the annotations the pack recognizes
  (`@Checked`, `@ThreadSafe`, `@GuardedBy`, `@Unsafe`, `@Immutable`, `@SuppressJcheck`), the
  `Todo()`/`Unreachable()`/`Unimplemented()` markers behind J0902, and a sealed `Result` type.
  `./build.sh` produces `out/jcheck-support.jar`.

## What is not implemented yet

Stated plainly, because a tool whose whole premise is "a clean check means something" must not
overstate itself:

- **The Gradle Tooling API and Maven embedder are not used.** jcheck runs the project's own build
  tool instead — the wrapper first, then `PATH` — and asks it for the compile/runtime and
  annotation-processor classpath (for Maven, `dependency:build-classpath`). The answer is cached
  under the jcheck cache directory and keyed by a fingerprint of the build files, so a warm daemon
  does not pay twice. When the tool is absent or the resolution fails, the heuristic classpath is
  kept and the limitation is stated on every run where it applies.
- **`jcheck fix` does not run OpenRewrite recipes.** It applies the diagnostic model's
  machine-applicable suggestions and, with `--error-prone`, Error Prone's own patch mode
  (`-XepPatchChecks` / `-XepPatchLocation:IN_PLACE`); the sources are snapshotted first and every
  edit is reverted when the re-check gets worse.

## Testing the tool

`./tests/run.sh` builds jcheck and runs the unit tests, the 22 rustc-style UI golden cases and the
MCP tests; the default suite needs only a JDK, no network. `JCHECK_TEST_PLUGINS=1` adds the
end-to-end analyzer cases — Error Prone, NullAway, the Checker Framework, SpotBugs and
`jcheck-junit` — which download and cache their jars.

Not covered here, and stated so a green run is not mistaken for more than it is: the
false-positive corpus over real third-party projects, a JDK matrix (this checkout is JDK 20 only),
and daemon classpath-change, out-of-memory and stale-cache recovery. The daemon does have a
concurrent-request and lifecycle test, every catalog entry is checked to have an implementation,
and every machine-applicable suggestion is applied and re-checked by a unit test.

## Environment

Built and tested on **JDK 20** because that is what is installed here; SPEC.md assumes JDK 21 or
25. JDK 20 lacks nothing jcheck itself needs (records, sealed types, switch expressions, Unix
domain sockets), but the JDK-21 baseline in the spec matters for the *checked code*: pattern
switch exhaustiveness and `-Xlint:this-escape` need 21+. UI goldens embed javac's own message
wording, which is the reason SPEC.md lists a JDK matrix as a test dimension. The attached plugins
are pinned to Error Prone **2.24.1**, NullAway **0.10.18** (with NullAway's `dataflow-nullaway` and
JSpecify pulled in transitively), the Checker Framework **3.42.0**, SpotBugs **4.8.6**, PIT
**1.15.8**, JaCoCo **0.8.12** and jcstress **0.16**, which are the versions verified against JDK 20
here. All of them are configurable in `jcheck.toml`. Infer is a native binary and is located rather
than resolved; the workspace copy used here is v1.2.0.

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
