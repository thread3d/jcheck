# Changelog

jcheck follows semantic versioning. The specification revision — the `spec v…` in `--version` — moves
separately and only when the document does.

## 1.4.0 — 23 Oct 2026

Independent verification: a second reader can now check jcheck's claims without trusting its suite.

- **`specs/requirements.yaml`** — twelve behavioural claims restated in the shape CodeWitness reads,
  each naming the files it is about. YAML rather than Markdown because CodeWitness's Markdown reader
  maps a requirement to files by scanning for its identifier, and jcheck's sources carry none.
- **`specs/codewitness.yaml`** — thirteen scenarios covering ten of the claims. CodeWitness generates
  a JUnit test per scenario, runs it against the real classes and judges the result. The run reports
  **10 VERIFIED, 2 UNVERIFIED, 0 FAILED**: every verified claim cites the executed generated test and
  the obligation it discharged.
- **`pom.xml`** — a verification-only Maven descriptor. No compile or runtime dependency; a
  test-scoped JUnit dependency so a verifier's generated tests compile, and Surefire to run them.
  `build.sh` and `tests/run.sh` remain the build and the suite.
- **`src/test/java/jcheck/verify/`** — the test root a verifier needs, deliberately free of jcheck's
  own tests; generated tests are removed after each run.
- README documents the run, what the verdicts mean, and the two claims left `manual` on purpose
  (`JC-008`, fix reversion; `JC-011`, an analyzer that declines to run), because their truth is the
  outcome of a toolchain invocation rather than a value a caller can observe.

## 0.2.0 — 23 Oct 2026

- Catalogue curation: eight javac keys mapped, and an unmapped upstream key is reported once per key
  rather than once per diagnostic.
