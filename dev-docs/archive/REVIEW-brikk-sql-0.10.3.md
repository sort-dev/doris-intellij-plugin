# brikk-sql 0.10.3 upgrade verification

Historical result: **0.10.3 was blocked, not approved for commit or release**.
The current upgrade is covered by [the 0.11.0 report](REVIEW-brikk-sql-0.11.0.md).
The published release
fixes all previously recorded DISTINCT regressions. Its new input-boundary handling
introduces a separate lateral-view alias/projection regression, documented below.
Expected engine safety refusals also required a downstream adapter change, now made.

The candidate remains based on `e1ed71e13b0e1dd31862f771284c5711b4abb0bc`.
No production databases were accessed. No commit, push, or release was made.

## Remaining blocker: lateral namespaces across a boundary

### Qualified lateral column becomes unresolvable

```sql
FROM t LATERAL VIEW EXPLODE(arr) e AS item
|> LIMIT 2
|> SELECT e.item
```

0.10.3 generates:

```sql
WITH __tmp1 AS (
  SELECT * FROM t LATERAL VIEW EXPLODE(arr) e AS item LIMIT 2
), __tmp2 AS (
  SELECT e.item FROM __tmp1 AS t
)
SELECT * FROM __tmp2
```

`e` is not visible in `__tmp2`. The result has no unsupported messages and the
plugin returns `Ok`. Native grammar accepts the syntax, but strict qualification
of the generated SQL throws:

```text
OptimizeError: Column 'item' could not be resolved for table: 'e'.
```

The same check passes with 0.10.2 and with a valid rewrite using a rowset alias.
It does not require a particular generated alias or CTE name. `outputShape` alone
is insufficient here: its lenient resolution returns `item` with type UNKNOWN.

### Base-table star gains lateral output columns

```sql
FROM t LATERAL VIEW EXPLODE(arr) e AS item
|> LIMIT 2
|> SELECT t.*
```

For catalog `t(id INT, category STRING, arr ARRAY<INT>)`:

| Version | Output names |
| --- | --- |
| 0.10.2 | `id, category, arr` |
| 0.10.3 | `id, category, arr, item` |

The new alias `t` refers to the entire lateral-expanded intermediate result rather
than the original base table. This silently widens the requested projection. Strict
qualification passes this case, so the regression also asserts exact output names.
Explicit base aliases reproduce it too; lateral `e.*` produces an unresolved qualifier.

### Cause and acceptable correction

Published `ast/PipeDesugar.kt:372` checks for `Join` when deciding whether an input
boundary can preserve relation namespaces. A `LATERAL VIEW` is not represented by
that node. Lines 445-446 then alias the entire intermediate result as the original
base table. The new boundary must account for lateral relation names and their
column ownership, preserving them correctly or refusing the unsafe lowering.

The two downstream tests accept either valid output with the requested columns or
an explicit handled refusal. They do not accept missing aliases, extra columns,
warnings presented as safe output, or escaped exceptions. No SQL-string workaround
or custom generator was added to the plugin.

Exact published-binary reproducers, compact/pretty SQL, native grammar results,
schema checks, and probe sources are recorded in:
`/tmp/opencode/doris-brikk-0103-audit/LATERAL-BOUNDARY-CONFIRMED.md`.
These are offline checks, not live Doris execution or server-analysis claims.

## Downstream refusal handling

The new upstream safety refusal is intentional:

```sql
FROM t AS a
|> FULL OUTER JOIN u AS b ON a.id = b.id
|> LIMIT 2
|> SELECT a.id
```

```text
UnsupportedError: Cannot preserve a pipe SELECT/DISTINCT input boundary
over joined stars without explicit, uniquely named input columns
```

The previous adapter caught only `ParseError`, so this exception escaped to the
interceptor's existing catch-and-delegate fallback, or out of preview intentions.
`DorisPipesEngine.transpile` now catches only this additional expected exception
type and returns `Transpile.Err` with the engine message. It does not swallow
cancellation or turn arbitrary exceptions into parse errors.

The existing result-dispatch branch was extracted into `dispatchPipeTranslation`
without changing its decisions. A test feeds it an actual engine refusal through
all four Execute wrappers and asserts four reports, zero submissions, and zero
predecessor calls. It also preserves NotPipe delegation and submission's boolean
result. Prefix/annotation tests verify a refusal stays attached to its own statement
and that an earlier safe prefix still translates. These tests failed before the
catch and pass afterward. Notification titles now describe translation failures,
not only syntax errors.

This does not close B10 wholesale: unexpected interceptor or notification failures
still have the pre-existing fallback. It also does not change B3's warning policy.
The new lateral regression returns `Ok` with no warning, so the exception catch
cannot protect against it.

## Published source and runtime audit

Both core and metadata 0.10.3 are available from Maven Central. Core requires
metadata 0.10.3; no other brikk modules were added to this plugin. The published
119 core and 20 metadata Kotlin source files match release commit
`21fb6ef2545d4c0f83bc45181cb785b7d199dde5` from successful
[publication workflow 34040866570](https://github.com/brikk/brikk-house/actions/runs/34040866570).
README commit `0e02451` is later, not the publication commit.

Binary SHA-256 values:

```text
6d847bcb31c874ba77b86dec40dd3cec4cabdd816c9b21d110e2d84465715c68  brikk-sql-jvm-0.10.3.jar
4ef5e992d5190b2c023f6417e1a4eea54f7b7ba07fcd23cb77441a44c6dbda0a  brikk-sql-metadata-jvm-0.10.3.jar
```

All downloads match Central SHA-1 sidecars. Gradle's logical filenames remain
`brikk-sql-jvmMain-0.10.3.jar` and `brikk-sql-metadata-jvmMain-0.10.3.jar`.
POM dependency requirements remain Kotlin 2.4.10 and serialization 1.11.0, with no
added coordinates. Both root and JVM runtime exclusions remain in place.

All 2,358 core and 40 metadata classes are Java 21, major/minor 65.0. Platform
Kotlin 2.3.20 on DB-261.24374.56 and 2.4.0 on IU-262.8665.81, with serialization
1.9.0, pass the static linkage and actual Java 21 probes. All candidate classes
initialize; all five catalogs' 4,441 FunctionDef entries round-trip. A changed
Kotlin-internal NullabilityResult constructor is not used by this plugin.

The 18-file core delta includes:

- Input fences preserving SELECT/DISTINCT after LIMIT, OFFSET, DISTINCT, grouping,
  HAVING and QUALIFY, plus aliases, ranking-order bindings and CTE-name collision
  handling. Explicit unsafe-boundary cases throw UnsupportedError.
- Helper-free native Doris QUALIFY for DISTINCT ON stars and local pagination.
  The previous LIMIT/OFFSET, head DISTINCT ON, and `_row_number` failures pass.
- Generic window-filter modifier ordering and nested-pipe lowering.
- Quoted catalog keys, output type/nullability reconciliation and BY NAME handling.
- BigQuery/Presto transformations and new captured-schema/offline-cache APIs.
  This plugin does not adopt those APIs or activate schema-cache disk I/O.

The source/runtime audit records 752 query observations across versions, output
formats and SDKs, plus 184 SQLite in-memory observations. FULL JOIN and DISTINCT
boundary controls pass; inherited issues are recorded separately. Native QUALIFY
output passes the bundled Doris grammar and projection checks, but SQLite cannot
execute it. It was not rewritten into another form merely to obtain a passing
execution result. No live-server semantic verification is claimed.

Metadata source and binary entry contents are unchanged. Notices now identify the
verified 0.10.3 source commit while retaining existing third-party attribution pins.
The complete source-change inventory and commands are in
`/tmp/opencode/doris-brikk-0103-audit/AUDIT.md`.

## Verification matrix

All requested matrix lanes completed. Only the two lateral-boundary tests fail;
no tests were skipped or relaxed.

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 351 tests: 349 passed, 2 failed | 351 tests: 349 passed, 2 failed |
| 261-built code on DB-262.10315.24, targeted suite | 85 tests: 83 passed, 2 failed | 85 tests: 83 passed, 2 failed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Settings on 261, five extra fresh workers | 45/45 passed | 45/45 passed |
| Settings on 262, five extra fresh workers | 45/45 passed | 45/45 passed |

The previous FULL OUTER JOIN, DISTINCT, Unicode and Java 21 regressions pass.
The new actual-refusal, prefix/annotation, and production result-dispatch tests
also pass. The normal suite's isolation test is a no-op; only the separate
real-loader lanes establish product class ownership.

`verifyEmbeddedPipes --rerun-tasks` passes with a newly stored configuration cache
and with cache reuse. It checks the exact five-JAR distribution, Java 21 bytecode
ceiling, packaged notices and absence of the provider dependency:

```text
antlr4-runtime-4.13.1.jar
brikk-sql-jvmMain-0.10.3.jar
brikk-sql-metadata-jvmMain-0.10.3.jar
doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar
doris-intellij-plugin-1.3.0.jar
```

The final runtime dependency report contains only the intended core/metadata and
ANTLR modules, alongside the existing file-based native parser. No verifier,
database engine, JDBC driver or duplicate Kotlin/serialization runtime was bundled.

The full matrix uses fresh `--rerun-tasks` executions in both SQL Transpiler modes.
The 262 lane runs 261-compiled/instrumented classes on DB-262.10315.24 with all six
compile/instrument exclusions. Settings tests run in five extra workers per IDE/mode.
Real PluginClassLoader lanes include both existing sibling ZIPs, without rebuilding
or modifying them. Trino/DuckDB SHA-256 values:

```text
ccfd2c9d44013a4d371346827122c85768593e67bef35eb01139144b03e4d4d3
64a52bed25dedce550b76cf550fc25d6244419c5887d80f0ca58bd1c69bdcdeb
```

Plugin Verifier 1.410 completed clean-cache offline and online checks of both
configured targets. All four verdicts are Compatible, with zero reported binary
compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed successfully in 5m22s. Verifier runs use separate
initially empty homes and a 15-minute task timeout. No compatibility problems or
failure levels are suppressed. SDK layout warnings and unavailable optional
integration branches remain visible; binary compatibility is not SQL correctness
or proof of every SDK integration. Reports are in
`build/reports/pluginVerifier0103-online/` and `build/reports/pluginVerifier0103-offline/`.
Scripts, logs, and archived JUnit XML are under `/tmp/opencode/doris-brikk-0103-audit/`.

Final status/diff/history review found only the accumulated upgrade changes.
`git diff --check` passes. No commit was created because the two lateral namespace
and projection tests still fail; HEAD remains the B22 baseline.

## Limits

B1 delegation, B2 SQL-aware statement boundaries, and B22 default-off project
policy are preserved. B23 remains deferred. Warning-only LAST_DAY/accuracy loss,
PIPE RENAME, LIMIT followed by WHERE, ordinary QUALIFY helper leakage, and the
older heuristic error mapper remain pre-existing findings. Nested-only pipes remain
outside the plugin's root-PipeQuery interception policy despite the new engine API.

No live Doris/JDBC query, parameter binding, cancellation, request ownership, or
server-error delivery was tested end to end. Grammar acceptance and offline shape
checks do not prove server semantic validity. The candidate remains uncommitted
until the lateral namespace and projection defects are fixed or safely refused.
