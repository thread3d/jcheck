# jcheck: a Rust-style feedback layer for Java

Specification v0.1 · 12 Oct 2026 · @Julian Roland Parramore

## Goal and scope

jcheck gives Java development the Rust experience for an LLM coding agent: one command, one output format, precise actionable diagnostics, whole bug classes caught before runtime, and a clean check that means something.

Java starts much further ahead than a blank slate. javac already reports real source spans and stable diagnostic keys through a public API, the type system is strong, and the ecosystem (Error Prone, NullAway, Checker Framework, SpotBugs) already covers much of what would otherwise need inventing. The work is therefore different from writing an analyzer:

1. Compose the existing analyzers into one invocation with one policy.
2. Normalize their scattered output into one diagnostic model with stable codes, explanations and fixes.
3. Close the remaining gaps, using Rust as the yardstick: ownership and aliasing, data races, silent integer overflow, nullability by default, unchecked exceptions, ignored results.
4. Make it fast. JVM startup is the enemy of an edit-check loop.
5. Make it agent-shaped: root cause first, capped output, diffs, deterministic ordering.

## Non-goals

- No new analyzer where a maintained one exists. Write glue, catalogs and a few custom Error Prone checks, not a new static analysis engine.
- No new language dialect. Standard Java and standard annotations (JSpecify, Error Prone annotations, Checker Framework annotations), compiled by an unmodified javac.
- No soundness claims beyond what each underlying tool provides. The tool reports which checkers ran.

## Rust guarantees mapped to Java

Most Rust guarantees have a Java mechanism today; the biggest remaining gaps are general ownership and aliasing, data races, silent integer overflow, and mutable-by-default code. The Gap column is what remains after the existing tool is configured as strictly as practical.

| Rust guarantee | Java mechanism | Tool | Gap |
| --- | --- | --- | --- |
| Precise errors with spans and suggestions | Diagnostic API (start/end position, key), `-Xdiags:verbose`; Error Prone `SuggestedFix` | javac, Error Prone | Output format differs per tool; no `explain`; cascades |
| Exhaustive match | Sealed types, records, pattern switch (javac enforces exhaustiveness for switch expressions and pattern switches) | javac, Error Prone `MissingCasesInEnumSwitch`, `UnnecessaryDefaultInEnumSwitch` | Old-style statement switch over enums need not be exhaustive; `default` hides new cases |
| No null; `Option<T>` | `@NullMarked` + `@Nullable` (JSpecify), NullAway as an Error Prone plugin; Checker Framework Nullness for deeper checking | NullAway, Checker Framework | Unannotated third-party libraries; needs `@NullMarked` rollout |
| `Result<T,E>`, `#[must_use]` | `@CheckReturnValue` at package level (must-use by default) with `@CanIgnoreReturnValue` to opt out; `Optional` for return values only | Error Prone | Unchecked exceptions bypass any Result convention |
| Ownership, move, drop (RAII) | try-with-resources; `@MustBeClosed`; Resource Leak Checker (`@MustCall`, `@Owning`, `@NotOwning`) | Checker Framework, Error Prone | No move semantics; resource ownership only, not general object ownership |
| Borrow checker / aliasing | Aliasing Checker (`@Unique`, `@NonLeaked`); defensive immutability | Checker Framework | Opt-in and narrow; mitigated by the immutability policy |
| Immutable by default | `final` locals and params, records, `@Immutable` (Error Prone Immutable check), immutable collections | javac lint, Error Prone, forbiddenapis | Mutability is the default; the policy must flip it |
| Send / Sync, no data races | `@GuardedBy` (Error Prone GuardedBy), `@ThreadSafe` / `@Immutable`, Lock Checker, Infer RacerD | Error Prone, Checker Framework, Infer | No compile-time race freedom; runtime stress tools fill part of the gap |
| Overflow checks in debug builds | `Math.addExact` / `multiplyExact` / `toIntExact`; Error Prone `NarrowingCompoundAssignment`, `IntLongMath`, `BadShiftAmount`; Index and Signedness Checkers | Error Prone, Checker Framework | Arithmetic wraps silently by default; needs a ban-plus-helper policy |
| Bounds-checked indexing | JVM throws `ArrayIndexOutOfBoundsException`; Index Checker proves safety statically | Checker Framework | Opt-in per module; annotation burden |
| Definite initialization | javac definite assignment; Checker Framework Initialization for constructors | javac, Checker Framework | Mostly covered |
| `unsafe` | `sun.misc.Unsafe`, JNI, `setAccessible`, `Thread.stop`, untrusted deserialization, `--add-opens` | forbiddenapis, ArchUnit, custom checks | Needs an explicit `@Unsafe(reason=...)` gate |
| `cargo test` with structured output | JUnit Platform with XML reports | Gradle / Maven, JUnit | Needs unified rendering, leak and thread checks |
| `cargo fmt` / `cargo fix` | google-java-format or Spotless; Error Prone patch mode; OpenRewrite | all | Needs one entry point |
| `clippy` | Error Prone, SpotBugs (+ FindSecBugs), PMD | all | Overlapping and noisy; needs curation |

## Architecture

A warm daemon drives javac and its analyzers in-process, merges everything into one diagnostic model, and renders it for humans, agents and CI.

The CLI is a thin client; the daemon resolves the build model, compiles and analyzes in tiers, and hands every finding to one engine that renders four formats.

### Components

1. **Driver and daemon.** A long-lived JVM, like the Gradle daemon, keeps javac and the analyzers warm. The CLI is a thin client (native image or small launcher) talking to it over a Unix socket. Without this, every check pays seconds of JVM startup and JIT warmup.
2. **Build model.** Resolves source roots, classpath, annotation-processor path, JDK/toolchain and flags from the project's build tool. Gradle first (Tooling API), Maven second. jcheck never replaces the build tool for producing artifacts; `jcheck build` delegates to it.
3. **Analysis runner.** Drives javac through `javax.tools.JavaCompiler` in-process with a `DiagnosticListener`. Plugins and processors attach through standard options (`-Xplugin:ErrorProne`, `-processor`, `-processorpath`). Output goes to a scratch directory; no artifacts are produced.
4. **Bytecode analyzers.** SpotBugs, and optionally Infer, run on the scratch class output after a successful compile.
5. **Diagnostic engine.** Normalizes, dedupes, tier-gates and renders (see Diagnostics).

### Phase gating (Java-specific)

javac proceeds parse, enter, attribute, flow, desugar, and stops early on errors by default. Error Prone and NullAway run after flow, so a single type error normally hides every analyzer finding. The Checker Framework has similar ordering behavior. The tool therefore:

- Runs javac with the option that lets it continue through flow analysis after errors (`--should-stop=ifError=FLOW`; hidden form `-XDshould-stop.ifError=FLOW` on older JDKs). Error Prone also requires `-XDcompilePolicy=simple`. Verify on the target JDK.
- Reports in tiers and states the tier reached: **T0** syntax, **T1** symbols and types, **T2** flow (definite assignment, reachability, exhaustiveness), **T3** Error Prone and NullAway, **T4** Checker Framework, **T5** bytecode tools.
- Ends with a line such as `analysis reached T2; T3-T5 skipped due to compile errors`, so the agent does not read a quiet T3 as clean.
- Never reports a higher-tier error as the cause of a lower-tier one.

### Compiler configuration notes (verify each against the target JDK)

- Baseline JDK 21 or 25 (LTS); sealed types, records and pattern switch are required for the exhaustiveness story.
- `-Xlint:all` with documented opt-outs, `-Werror`, `-Xdiags:verbose`, `-parameters`; `-Xmaxerrs` and `-Xmaxwarns` set high internally and capped at render time.
- Pass `-proc:full` or an explicit `-processor` list: JDK 23+ no longer runs annotation processors implicitly.
- Error Prone on JDK 16+ needs the documented `--add-exports` / `--add-opens` flags for `jdk.compiler`; the daemon's JVM args must include them.
- `--release N` follows the project, not the daemon's JDK.

### Strict profile

What makes Java feel like Rust is flipping the defaults. The default profile below is policy, not code, and every rule is overridable in `jcheck.toml`.

**Compiler lints.** `-Xlint:all` minus a short documented exclusion list, with `-Werror` in the strict profile. Notably on: `rawtypes`, `unchecked`, `deprecation`, `fallthrough`, `serial`, `cast`, `overrides`, `try`, `this-escape` (JDK 21+), `static`, `varargs`.

**Error Prone.** All checks at WARNING or above are promoted to ERROR in strict. Specific policy:

- `@CheckReturnValue` applied at package level through `package-info.java` (must-use by default); `@CanIgnoreReturnValue` opts out per method.
- `MissingCasesInEnumSwitch`, `UnnecessaryDefaultInEnumSwitch` and `MissingSwitchDefault` configured for sealed versus enum switches.
- Also on: `Immutable`, `GuardedBy`, `ThreadSafe`, `MustBeClosedChecker`, `NarrowingCompoundAssignment`, `IntLongMath`, `ReferenceEquality`, `EqualsHashCode`, `FutureReturnValueIgnored`, `CatchAndPrintStackTrace`, `EmptyCatch`.

**Nullability.**

- `@NullMarked` on every package (package-level or module-level); `@Nullable` only where null is allowed.
- NullAway in JSpecify mode at ERROR, scoped with `-XepOpt:NullAway:AnnotatedPackages=` or `OnlyNullMarked`.
- Unannotated dependencies are handled through NullAway library models or `@NullUnmarked`. The tool lists which dependencies are treated as unmarked, so the agent knows where the guarantee ends.
- Optional second layer: Checker Framework Nullness for modules that want the stronger guarantee (initialization, map keys, polymorphism).

**Immutability by default.**

- Locals, parameters and fields are `final` unless mutated (`FieldCanBeFinal`, `UnusedVariable`, plus the custom `LocalCanBeFinal`).
- Prefer records; classes with all-`final` fields of immutable types get `@Immutable` and are verified.
- Banned in strict: mutable static state (allow-list by annotation), public mutable fields, `Date` / `Calendar` / `SimpleDateFormat`, and mutable collections returned from public APIs without copying.

**Errors and results.**

- `Optional<T>` for absence; sealed Result-style types for expected failure (a small `jcheck-support` library ships `Result`, `Option` adapters and `Try`); exceptions only for programmer error and genuinely exceptional conditions.
- Banned: empty catch, `catch (Throwable | Exception)` without rethrow or logging, `printStackTrace`, swallowing `InterruptedException`, throwing from `finally`, `System.exit` outside `main`.
- `jcheck-support` provides `Todo()`, `Unreachable()` and `Unimplemented()`, which throw with file and line. Any call raises **J0902** so none ship.

**Resource ownership.**

- Every `AutoCloseable` is closed on all paths: try-with-resources, enforced by `-Xlint:try`, Error Prone `MustBeClosed`, and the Resource Leak Checker for ownership across calls (`@Owning` fields and params, `@MustCall("close")`).
- Threads, executors, `HttpClient`s, streams from `Files.lines` / `Files.walk`, and JDBC objects are treated as must-close or must-shut-down by the custom pack where the JDK does not annotate them.

**Numeric safety.** Banning all raw `+ - *` on `int` and `long` would be unusable, so the policy is narrower and enforceable:

- Error on narrowing compound assignment, implicit truncation to `byte` / `short` / `char`, `int` multiplication assigned to `long`, out-of-range shifts, and `Math.abs(Integer.MIN_VALUE)`-class mistakes.
- Error on `(int)` casts of `long` or `double` without `Math.toIntExact` or a suppression with a reason.
- A `@Checked` annotation on a class or package makes the custom check `UncheckedArithmetic` flag raw `+`, `-`, `*` on `int` / `long` and suggest `Math.addExact` and friends as a machine-applicable fix. Opt-in per module; suggested for parsers, protocol code and anything touching sizes or indices.
- Index Checker for modules with heavy array or list indexing.

**Concurrency.**

- `@GuardedBy` is required on shared mutable fields in classes marked `@ThreadSafe` (custom check `SharedMutableWithoutGuard`); `@Immutable` is verified.
- Banned: raw `Thread`, `Timer`, unbounded executors, `synchronized(this)` on public classes, `Thread.sleep` in tests, double-checked locking without `volatile`.
- Infer RacerD is optional at T5. The runtime harness covers the rest.

**The unsafe gate.** An `@Unsafe(reason = "...")` annotation on a class or method permits `sun.misc.Unsafe` and `jdk.internal.*`, `setAccessible`, JNI and FFM downcalls, `Thread.stop` / `suspend`, native memory APIs, deserialization of untrusted types, and reliance on `--add-opens`. Anything else in that set is error **J0701**. The reason must be non-empty.

**Architecture rules.** ArchUnit rules (package cycles, layering, no test code in main, one logging facade) run as ordinary tests and surface through the same diagnostics.

**Bytecode analyzers.** SpotBugs with FindSecBugs at effort `max` and threshold `medium`, with a curated include and exclude list to control noise. SARIF output is ingested.

## Custom check pack

A small Error Prone check library, `jcheck-checks`, covers what no maintained check expresses. Each check follows Error Prone conventions: a stable name, a `SuggestedFix` where the fix is mechanical, and documentation under `docs/<code>.md`. A check is enabled at deny only after it passes the false-positive corpus gate (see Testing the tool).

| Code | Check | Default level | Notes |
| --- | --- | --- | --- |
| J0301 | UncheckedArithmetic | deny in `@Checked` | Suggests `Math.*Exact`. |
| J0302 | LossyCast | deny | `(int)` long, `(short)` and similar without an exact helper. |
| J0601 | SharedMutableWithoutGuard | deny in `@ThreadSafe` | Shared mutable field with no `@GuardedBy`. |
| J0602 | MutationDuringIteration | deny | Collection modified inside a for-each over itself. Java catches this only at runtime (`ConcurrentModificationException`); this check catches the simple cases statically. |
| J0603 | EscapingMutableInternal | deny | Returning or storing an internal mutable collection or array without copying. |
| J0701 | UnsafeOutsideUnsafe | deny | Unsafe constructs outside an `@Unsafe(reason=...)` region. |
| J0705 | MutableStaticState | deny in strict | Allow-list by annotation. |
| J0706 | LocalCanBeFinal | warn | Suggests `final`. |
| J0902 | TodoMarker | warn | Calls to `Todo()` or `Unreachable()`. |
| J1001 | UnclosedExecutorOrThread | deny | Executors or threads with no visible shutdown path. |

These are initial priorities, not a ceiling.

## Diagnostics

Every tool's output is normalized into one model with stable codes, then rendered for humans, agents and CI.

### Model

```
Diagnostic {
  code:         string   // "J0201", a jcheck-stable code
  source:       string   // javac | errorprone | nullaway | checker:resourceleak | spotbugs | jcheck
  source_key:   string   // raw upstream id, e.g. "compiler.err.not.exhaustive.statement"
  level:        error | warning | note | help
  tier:         0..5
  message:      string
  primary:      Span     // file, line_start, col_start, line_end, col_end
  labels:       [ {span, text} ]
  notes:        [ string ]
  help:         [ string ]
  suggestions:  [ { applicability, message, edits:[{span, replacement}] } ]
  related:      [ Diagnostic ]
  raw:          string?  // original tool text, preserved verbatim
}
```

Spans come from `Diagnostic.getStartPosition` and `getEndPosition`. `applicability` is `MachineApplicable`, `MaybeIncorrect`, `HasPlaceholders` or `Unspecified`, with the same meaning as in rustc.

### Codes and catalog

- Codes are stable forever, assigned by a catalog table `(source, source_key)` to code. Unmapped upstream diagnostics get a `J9xxx` code derived from a hash of the key and are logged for catalog curation; they are never dropped.
- Catalog entries are data files, not code. They supply a better headline, a label for the span, `help:` lines, and a link to `docs/<code>.md`.
- For famously unhelpful javac messages (generic inference failures, cannot find symbol with a near miss) the catalog rewrites using the compiler's own diagnostic arguments and symbol table: nearest identifiers, expected versus actual types in plain form.
- `jcheck explain J0201` prints the long form: why it matters, a failing example, a fixed example.

### Human renderer (rustc-style)

```
error[J0201]: non-exhaustive `switch`: `Shape.Triangle` not handled
  --> src/main/java/geo/Area.java:31:9
   |
31 |         switch (shape) {
   |         ^^^^^^^^^^^^^^ sealed interface `Shape` permits 3 subtypes; 2 handled
   = note: handled: Circle, Rectangle
   = help: add `case Triangle t -> ...`; avoid `default` on sealed types so new subtypes fail to compile
   = source: javac (compiler.err.not.exhaustive.statement)
```

### JSON renderer

`--message-format=json` emits JSON Lines, one diagnostic per line, then a final `{"summary": {...}}` line. The schema is versioned.

### LLM renderer

`--message-format=llm` is compact text shaped for an agent's context window:

- Root cause first (lower tier, earlier in dependency order), cascades removed.
- Default cap of 15 diagnostics, with an "N more omitted" line.
- Each item shows code, level, message, `file:line:col`, two lines of source context with the span marked, `help` lines, and a unified diff when a suggestion exists.
- A tier line such as `analysis reached T3 of 5 (T4 checker-framework: skipped, module not enabled)`.
- A final line: `jcheck: 2 errors, 4 warnings. 'jcheck explain <code>' for details.`
- No ANSI colors, no progress output, deterministic ordering.

### SARIF renderer

`--message-format=sarif` serves CI and IDEs.

### Dedupe and cascades

- Duplicates across tools (the same null issue from NullAway and the Checker Framework; the same unclosed resource from `-Xlint:try` and Error Prone) are merged. The highest-fidelity one is kept and the others go in `related`.
- After a cannot find symbol or an unresolved import, later errors that only mention the same unresolved name, or the resulting erroneous types, are dropped.

## Test and runtime harness

`jcheck test` turns JUnit results, leaks, hangs and crashes into the same diagnostics as static checks, so a green run means something.

**Running tests.** Execution is delegated to the project's build tool or the JUnit Platform Console Launcher, with a fixed XML report location. jcheck parses the reports and renders each failure as a diagnostic: the source line of the failing assertion, an expected-versus-actual diff, and a stack trace with project frames first.

**The jcheck-junit extension** is registered automatically and provides:

- A per-test timeout (default 10 s) with a thread dump on timeout. Deadlocks are found with `ThreadMXBean.findDeadlockedThreads` and reported with the cycle.
- Thread-leak detection: threads alive after the test that were not alive before, minus an allow-list (common pool, JUnit).
- Resource-leak detection on Linux: file-descriptor count delta from `/proc/self/fd`, plus unclosed `AutoCloseable` tracking where instrumentation is cheap (executors, streams).
- Capture of uncaught exceptions in any thread (via the default uncaught-exception handler); any such exception fails the test.
- An option to fail on ERROR log lines unless expected.
- An option for randomized test order, with the seed printed for reproducibility.

**Opt-in, slower checks**

- PIT mutation testing scoped to changed files, reported as diagnostics ("mutant survived: negated condition at Foo.java:42"). High signal against vacuous, LLM-written tests.
- JaCoCo coverage of changed lines.
- jcstress or Lincheck for concurrency-critical classes.
- Test JVM flags: `-ea -esa`, `-XX:+HeapDumpOnOutOfMemoryError`, a small `-Xss` to expose runaway recursion. Confirm that helpful `NullPointerException` messages are enabled on the target JDK.

**Runtime error mapping.** Uncaught exceptions at process level, `OutOfMemoryError` and `StackOverflowError` render as diagnostics with a project-frames-first backtrace, never as a raw stack dump.

## Commands: fix, format, explain, init

One entry point replaces the separate formatter, patcher and linter invocations.

| Command | What it does |
| --- | --- |
| `jcheck check` | Fast path: in-process javac plus analyzers, no artifacts. |
| `jcheck build` | Delegates to the build tool after a clean check. |
| `jcheck test` | Runs tests with the jcheck-junit extension and structured rendering. |
| `jcheck fix` | Applies all MachineApplicable suggestions. Sources: Error Prone patch mode (`-XepPatchChecks`, `-XepPatchLocation`), OpenRewrite recipes shipped in the catalog (add `@Override`, convert to try-with-resources, switch to `List.of`), and the custom check pack. Edits are applied to a copy and `check` is re-run; the result is kept only if the error count did not rise, otherwise it is reverted and reported. |
| `jcheck fmt [--check]` | google-java-format or Spotless, whichever the project already uses. |
| `jcheck explain <code>` | Long-form explanation with a failing and a fixed example. |
| `jcheck init` | Detects build tool and JDK, writes `jcheck.toml`, adds `package-info.java` stubs with `@NullMarked` and `@CheckReturnValue`, adds `jcheck-support` as a dependency, and with `--agent` writes the agent instructions snippet. |

Common flags: `--message-format=human|json|llm|sarif`, `--max-diagnostics N`, `--profile strict|standard|lenient`, `--deny/--warn/--allow <code>`, `--report-suppressions`.

Exit codes: `0` clean (warnings allowed unless `--deny-warnings`), `1` diagnostics at error level, `2` tool or configuration failure, kept distinct from "your code is wrong".

## Performance and incremental behavior

On a warm daemon, a one-file edit in a 200k-line multi-module project should check in under 3 seconds at tiers T0 to T3.

| Scenario | Target |
| --- | --- |
| Warm daemon, one-file edit, T0-T3 | under 3 s |
| Warm daemon, Checker Framework enabled on changed modules | under 10 s |
| Cold start | under 20 s |

- **Daemon.** Keeps javac file managers, symbol tables and analyzer classloaders warm.
- **Incremental unit of work.** Changed files plus their dependents by API-signature hash, the same idea Gradle uses for incremental Java compilation. A full re-check happens when the classpath, JDK, flags, or annotations on public API change.
- **Per-module checker enablement** keeps the expensive tools scoped to where they pay off.
- **Memory.** The daemon heap is configurable. On out-of-memory the daemon restarts automatically and reports a diagnostic; it never hangs.

## Configuration and suppressions

One file, `jcheck.toml`, sets the profile, per-module checker enablement, test limits and per-code overrides.

```toml
[project]
build   = "gradle"            # or "maven"
jdk     = 21
release = 21
modules = ["core", "server"]

[profile]
name = "strict"               # strict | standard | lenient

[javac]
lint  = ["all", "-processing", "-serial"]   # example exclusions
extra = []

[errorprone]
enabled     = true
promote     = "warning->error"
disabled    = ["Slf4jFormatShouldBeConst"]  # example opt-out; document the reason
custom_pack = true

[nullness]
mode     = "nullaway-jspecify"
packages = ["com.example"]
checker_framework = false

[checkers.per-module]
core   = ["resourceleak", "optional", "index"]   # Checker Framework, opt-in
server = ["resourceleak", "lock"]

[bytecode]
spotbugs = { effort = "max", threshold = "medium", findsecbugs = true }
infer    = false

[tests]
timeout_seconds  = 10
thread_leaks     = "fail"
fd_leaks         = "fail"
mutation_testing = false

[llm]
max_diagnostics = 15
context_lines   = 2

[lints]                       # per-code overrides
J0301 = "deny"
J0902 = "deny"
```

**Suppressions.** Every suppression needs a reason.

- `@SuppressWarnings("NullAway")` without a reason is rejected by the custom check `SuppressionNeedsReason` (J0003).
- The supported forms are `@Unsafe(reason = "...")` and `@SuppressJcheck(code = "J0801", reason = "...")`.
- `jcheck check --report-suppressions` lists every suppression so a reviewer or agent can audit them.
- There is no blanket "disable all".

## Agent integration

`jcheck init --agent` writes this snippet into `AGENTS.md` (or `CLAUDE.md`), so the edit-check-fix loop is the same in every project:

```
- After every edit run `jcheck check --message-format=llm` and fix all errors before moving on.
- Fix lower tiers first; higher-tier findings appear only after T0-T2 are clean.
- Use `jcheck explain <code>` when a diagnostic is unclear.
- Never add @SuppressWarnings, @SuppressJcheck or @Unsafe without a real reason.
- Apply `jcheck fix` for machine-applicable suggestions, then re-check.
- Done means: `jcheck check` clean, `jcheck test` passing with no thread, resource or fd leaks.
- Prefer records, sealed interfaces, Optional/Result, try-with-resources, and immutable collections.
- Never use null in @NullMarked code; use @Nullable only at boundaries.
```

A single stable command and a stable output format are what make the loop reliable. Avoid adding modes the agent has to choose between.

## Testing the tool itself

The false-positive corpus is the gate that decides whether a rule may be set to deny.

1. **UI tests, rustc-style:** `tests/ui/**/*.java` with golden `*.stderr` files (human format) and expected JSON for a subset. Every code has a failing case, a passing case, and a tricky case (generics, lambdas, switch patterns, try/finally, inner classes, records).
2. **False-positive corpus.** Run strict and standard over real projects of varied style, including the user's own. Track diagnostics per kLOC for each code; a rule above the agreed noise budget is downgraded until fixed.
3. **Suggestion tests.** Every MachineApplicable fix must compile and re-check clean for that code.
4. **Catalog coverage.** Unmapped upstream keys seen in the corpus are listed (tracked, not failing).
5. **JDK matrix.** The supported LTS versions plus the latest, because compiler plugins break across JDK releases.
6. **Daemon tests.** Concurrent requests, classpath change, out-of-memory recovery, stale cache.

## Milestones

Build M0 and M1 first: they deliver the feedback loop plus strict Error Prone and NullAway, which is most of the value to an agent.

| Milestone | Scope |
| --- | --- |
| **M0: Feedback loop** | Build model (Gradle first), in-process javac via `JavaCompiler` and `DiagnosticListener`, real spans, phase gating with `should-stop`, tiers, cascade suppression, human / JSON / LLM renderers, the daemon, and an initial catalog for the 50 most common javac keys. |
| **M1: Error Prone, NullAway and policy** | Error Prone with promoted levels, NullAway (JSpecify), `-Xlint:all`, `@CheckReturnValue` by default, `init`, `fix` (patch mode), `explain`, `fmt`. |
| **M2: Test harness** | `jcheck test`, report parsing, jcheck-junit (timeouts, thread dump on timeout, thread and fd leaks, uncaught exceptions), project-frames-first stack traces. |
| **M3: Deeper static guarantees** | Custom check pack (J03xx, J06xx, J07xx), the `jcheck-support` library (Result, Todo, `@Unsafe`, `@Checked`), Checker Framework (Resource Leak, Optional, Index, Lock) per module, SpotBugs and SARIF merge, dedupe across tools. |
| **M4: Polish and reach** | Maven support, incremental dependents graph, OpenRewrite catalog, PIT and JaCoCo, jcstress / Lincheck hooks, Infer, an LSP or jdtls bridge, CI SARIF. |

## Open decisions

Seven choices to settle before building; the recommendation is given where one exists.

1. **Gradle only, or Gradle and Maven?** Gradle's Tooling API makes the build model far easier. Recommended: Gradle first.
2. **Daemon or build-tool plugin?** A standalone daemon is faster and build-tool-agnostic; a Gradle plugin gets dependency resolution for free. Recommended: a standalone daemon that asks the build tool for its model.
3. **How strict is strict?** Whether `-Werror` plus all Error Prone checks at ERROR is the default for new projects only, with standard (warnings stay warnings) for existing code. Recommended: yes, with `jcheck init` choosing by whether the project already compiles clean.
4. **Checker Framework scope.** It gives the strongest guarantees (resource ownership, indexing, locking) but adds annotation burden and compile time. Recommended: opt-in per module, not default.
5. **Custom check pack.** Ship it upstream-style or keep it private; this affects the maintenance burden of Error Prone version compatibility.
6. **Kotlin and Scala in mixed projects.** Out of scope for v0.1, since javac tooling does not see them. Decide whether to document a hard boundary.
7. **Supported JDK range.** JDK 21 and 25 are assumed.
