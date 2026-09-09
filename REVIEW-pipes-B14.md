# B14 generated SQL validation

Date: 2026-09-08

Status: **fixed; verification passed**.

## Result

brikk-sql 0.12.0 already refuses the original schema-less PIPE RENAME case. B14 still
reproduced with warning-free head `LIMIT` and `OFFSET` forms: negative, string,
decimal and placeholder values, plus `PERCENT` and `WITH TIES`. The engine generated
SQL, but the bundled Doris parser rejected it.

`DorisPipesEngine.transpile` now validates every warning-free generated payload with
the bundled Doris parser. A rejected payload remains `Transpile.Ok` so Preview can
show the exact SQL and source map, but `validationError` feeds `executionError` and
blocks Execute, run-to-stage and diagnostics submission paths.

Named `:name` parameters use a masked parser-only copy during editor preflight. After
the platform prompts or loads stored values, all selected pipes are retranspiled and
validated before the first request. Surviving placeholders fail final validation.
Parameter discovery uses native tokens and parser-backed disambiguation so Doris array
slices, MAP entries and structured type colons are not rewritten. Tests also cover
parameters inside bracket expressions, nested MAP values, keyword names and compact
operators.

This is a syntax/capability gate. It does not prove name resolution, types, planner
support, server-version support or semantic equivalence of valid generated SQL.

## Verification

- Full DB-261.24374.56 suite, SQL Transpiler absent: 402/402 passed.
- Full DB-261.24374.56 suite, SQL Transpiler installed: 402/402 passed.
- PIPE execution/upgrade/safety tests on DB-262.10315.132, companion absent: 100/100 passed.
- PIPE execution/upgrade/safety tests on DB-262.10315.132, companion installed: 100/100 passed.
- `verifyEmbeddedPipes`: passed; embedded libraries, notices and Java 21 ceiling are unchanged.
- Plugin Verifier 1.410: DB-261.24374.56 and IU-262.8665.81 both Compatible.

The verifier retained the existing notices: 4 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 261; 5 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 262. No compatibility problem was hidden.

No live database operation or SQL request was performed.
