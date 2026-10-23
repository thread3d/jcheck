# jcheck's self-report log

This directory holds the backlog of problems jcheck found with **itself** while checking projects —
crashes, analyzers that were supposed to run and did not, upstream diagnostics still on a `J9xxx`
fallback code, host limitations, and friction recorded by hand. It is not about the projects jcheck
analysed: their findings are diagnostics, and they stay in the normal output.

## Reading it

```bash
./jcheck issues              # markdown, bugs first, each entry with id / kind / hint
./jcheck issues --json       # the raw JSONL lines, including stack traces
./jcheck issues path         # the file being read
./jcheck issues clear        # after the fixes are in and re-checked
./jcheck issues add "..."    # record friction by hand: --detail <text> --kind bug|gap|env|note
```

A future session asked to "fix the issues jcheck logged" should read the list, treat `kind` as the
triage axis, and clear the log only after the fixes are verified — a recurrence is only visible
again once the old entry is gone.

## Format

`self-report.jsonl`, one JSON object per line, written best-effort by `jcheck.selfreport.IssueLog`:

| Field | Meaning |
| --- | --- |
| `id` | fingerprint of kind + summary + detail; one entry per distinct problem |
| `time` | first sighting, ISO-8601 |
| `kind` | `internal-error`, `tool-failure`, `capability-gap`, `environment` or `friction` |
| `severity` | `bug`, `gap`, `env` or `note` (the kind's triage class) |
| `summary` / `detail` | what happened, and the evidence |
| `hint` | the kind of fix the entry expects |
| `command` / `root` | which jcheck command hit it, in which project |
| `version` / `java` / `os` | the runtime that produced it |
| `stack` | full stack trace for internal errors |

The log is gitignored (`issues/*.jsonl`); this README is the only tracked file here. `--issue-log
<path>`, `$JCHECK_ISSUE_LOG` and the `jcheck.issue.log` property relocate it; `off` disables it. The
project being checked is never written to.
