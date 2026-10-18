# B20 PIPE stage keyword coloring

Date: 2026-09-09

Status: **fixed; verification passed**.

`EXTEND` now receives keyword attributes only when it is the complete first token of
a PIPE stage. The annotator requires adjacent native `PIPE` and `GT` tokens, follows
the project PIPE setting, and does not recolor words already handled by the standard
Doris highlighter.

Completion and coloring share one stage catalog, including OFFSET and supported JOIN
forms. Completion continues through partial multiword heads such as `FULL OUTER`, then
stops after a complete head so column/expression suggestions can take over.

Tests cover enabled/disabled projects, lower-case stages, comments between the
operator and stage, UTF-16 offsets, strings, comments, backticks, ordinary identifiers,
spaced/comment-separated operators, `||>`, Unicode/dollar identifier suffixes, partial
multiword completion, completed heads and unrelated SQL positions.

## Verification

- Full DB-261.24374.56 absent-companion suite: 419/419 passed in one worker.
- Installed-companion suite: all 419 passed across isolated workers, 396 non-execution
  tests plus 23 execution tests. The single-worker lane exposed a pre-existing offline
  console fixture ordering failure; the failed test passed alone and the complete
  execution class passed in its fresh worker.
- B20 highlighting/completion plus settings on DB-262.10315.132: 14/14 passed in each
  companion mode.
- `verifyEmbeddedPipes` passed.
- Plugin Verifier reports DB-261.24374.56 and IU-262.8665.81 Compatible.

The verifier retained the existing API notices. No live database operation or SQL
request was performed.
