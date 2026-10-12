# jcheck

A Rust-style feedback layer for Java: one command, one output format, precise actionable diagnostics, and a clean check that means something.

jcheck composes the existing Java analyzers (javac, Error Prone, NullAway, Checker Framework, SpotBugs) into a single warm-daemon invocation, normalizes their output into one diagnostic model with stable codes, and renders it for humans, agents and CI.

- **[SPEC.md](SPEC.md)** — specification v0.1 (12 Oct 2026)

## Status

Pre-implementation. The specification is landed and the open decisions in its final section are unresolved.
