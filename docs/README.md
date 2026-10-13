# jcheck diagnostics

These pages back `jcheck explain <code>`. Each page states why the check exists and shows a
failing example next to a fixed one, so a diagnostic can be understood and resolved without
leaving the terminal.

They are the human-facing half of the catalog: the `(source, source_key) -> code` mapping and
the headlines, labels and help lines live in the TSV resources under
`src/main/resources/jcheck/catalog/`, and the `doc` column of each row points at the page here.

## Index

| Code | Check | Tier | Default level |
| --- | --- | --- | --- |
| J0003 | SuppressionNeedsReason | T2 policy | deny |
| J0201 | NonExhaustiveSwitch | T2 flow | error |
| J0203 | MissingReturnStatement | T2 flow | error |
| J0465 | UnsafeVarargs | T2 flow | warn |
| J0466 | PossibleFallThrough | T2 flow | warn |
| J0467 | AmbiguousOverload | T2 flow | warn |
| J0468 | ThisEscape | T2 flow | warn |
| J0469 | ThisEscapeLocation | T2 flow | warn |
| J0301 | UncheckedArithmetic | T3 jcheck-checks | deny in `@Checked` |
| J0302 | LossyCast | T3 jcheck-checks | deny |
| J0601 | SharedMutableWithoutGuard | T3 jcheck-checks | deny in `@ThreadSafe` |
| J0602 | MutationDuringIteration | T3 jcheck-checks | deny |
| J0603 | EscapingMutableInternal | T3 jcheck-checks | deny |
| J0701 | UnsafeOutsideUnsafe | T3 jcheck-checks | deny |
| J0705 | MutableStaticState | T3 jcheck-checks | deny in strict |
| J0706 | LocalCanBeFinal | T3 jcheck-checks | warn |
| J0801 | UnclosedResource | T3 jcheck-checks | deny |
| J0901 | TestFailure | T5 junit | deny |
| J0902 | TodoMarker | T3 jcheck-checks | warn |
| J1001 | UnclosedExecutorOrThread | T3 jcheck-checks | deny |
| J1101 | MutantSurvived | T5 mutation | warn |
| J1102 | UncoveredLine | T5 coverage | warn |
| J1201 | StressTestFailure | T5 jcstress | deny |
| J1301 | AddOverride | T3 jcheck-checks | warn |
| J1401 | InferFinding | T5 infer | warn |

## Reading a page

Every page follows the same shape: the code and check name, its tier and default level, why it
matters, a failing example, a fixed example, and notes on suppression and false positives.

Suppressions are not blanket: each one needs a non-empty reason, which is itself checked as
J0003.
