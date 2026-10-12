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
| `jcheck test` | Delegates to the build tool, then renders JUnit XML reports as diagnostics. |
| `jcheck fix` | Applies machine-applicable suggestions, re-checks, reverts if errors rose. |
| `jcheck fmt` | google-java-format, or the project's Spotless task. |
| `jcheck explain <code>` | Long-form explanation: why it matters, failing and fixed example. |
| `jcheck init` | Writes `jcheck.toml`; `--agent` adds the agent instructions snippet. |
| `jcheck daemon start\|stop\|status\|run` | Warm daemon over a Unix domain socket. |

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

**Catalog entries are data.** 185 rows in TSV resources map `(source, source_key)` to a stable
code, tier, curated headline and help lines. Unmapped keys are never dropped: they get a stable
`J9xxx` code derived from an FNV-1a hash of the key, and are flagged as needing curation.

## What is implemented

- **Build model** — Gradle/Maven/flat detection, source root and classpath discovery, `--sources`
  and `--classpath` overrides. Limitations are stated in the output rather than glossed over.
- **Analysis runner** — in-process `JavaCompiler` with a diagnostic listener, real start/end
  spans from `Diagnostic.getStartPosition`/`getEndPosition`, `-Xlint`, `-Xdiags:verbose`,
  `--release`, `-proc:none`, and a retry path when a JDK rejects the hidden phase-gating options.
- **Tier gating T0–T5** with a per-run statement of which tiers ran and why the rest did not.
- **Diagnostic engine** — normalization, catalog codes, cross-tool dedupe, cascade suppression,
  `strict`/`standard`/`lenient` profiles, per-code `--deny`/`--warn`/`--allow`, deterministic
  ordering, and suppression auditing via `--report-suppressions`.
- **Four renderers** — rustc-style human output, JSON Lines with a versioned schema, a compact
  agent-shaped LLM format with diffs and a cap, and SARIF 2.1.0.
- **Check pack (T3, AST-based)** — `J0706 LocalCanBeFinal` with a machine-applicable `final`
  insertion, run through the standard `TaskListener`/`Trees` API rather than a new engine.
- **Daemon** — length-prefixed binary protocol over a Unix domain socket, keyed by a hash of the
  project root so serving a project never writes into it. `jcheck check` uses a running daemon
  automatically and falls back in-process.
- **`init`, `fix`, `explain`, `fmt`, `build`, `test`** — including a JUnit XML parser that lifts
  project stack frames above framework frames.
- **Tests** — 920 assertions: unit tests plus 8 rustc-style UI golden cases under
  `tests/ui/cases/`. A strict hand-written JSON validator checks the JSON and SARIF output.

## What is not implemented yet

Stated plainly, because a tool whose whole premise is "a clean check means something" must not
overstate itself:

- **Error Prone and NullAway are not attached.** The plugin seam exists (`-Xprocessorpath`,
  `-proc` handling, catalog rows and J-code mappings for both), but no plugin jars are resolved,
  so T3 runs the built-in jcheck pack only. `JcheckConfig.errorProne` defaults to `false` for
  exactly this reason, and enabling it emits a note. No NullAway or Error Prone diagnostic has
  been observed by this implementation.
- **Gradle Tooling API / Maven resolution** is not driven. The build tool is detected, and
  `jcheck build`/`test`/`fmt` delegate to it, but the dependency graph is not read, so third-party
  types can appear unresolved. jcheck says so on every run where that applies.
- **Checker Framework (T4) and SpotBugs (T5)** are not wired up; `[checkers.per-module]` and
  `[bytecode]` parse and are reflected in the tier line, but never run.
- **The rest of the custom check pack** (`J0301`, `J0302`, `J0601`–`J0603`, `J0701`, `J0705`,
  `J0902`, `J1001`) exists in the catalog and in `docs/`, but only `J0706` has an implementation.
- **No `jcheck-junit` extension** — timeouts, thread-leak and fd-leak detection are not built;
  `jcheck test` parses JUnit XML produced by the build tool.
- **No incremental dependents graph, PIT, JaCoCo, jcstress/Lincheck, Infer, or LSP bridge.**
- **`jcheck fix` is not a patch engine** — it applies `SuggestedFix`-style edits from the
  diagnostic model, not Error Prone patch mode or OpenRewrite recipes.

## Environment

Built and tested on **JDK 20** because that is what is installed here; SPEC.md assumes JDK 21 or
25. JDK 20 lacks nothing jcheck itself needs (records, sealed types, switch expressions, Unix
domain sockets), but the JDK-21 baseline in the spec matters for the *checked code*: pattern
switch exhaustiveness and `-Xlint:this-escape` need 21+. UI goldens embed javac's own message
wording, which is the reason SPEC.md lists a JDK matrix as a test dimension.

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
  fix/ init/ explain/ testharness/
src/main/resources/jcheck/catalog/*.tsv   185 catalog rows
docs/                                     explain pages
tests/java/                               unit tests and the golden runner
tests/ui/cases/                           rustc-style golden cases
```
