# brikk-sql 0.10.1 upgrade verification

Historical result: **0.10.1 was blocked, not approved for commit or release**.
The current upgrade is covered by [the 0.11.0 report](REVIEW-brikk-sql-0.11.0.md).
Published 0.10.1 fixes
both blockers found in the [0.10.0 audit](REVIEW-brikk-sql-0.10.0.md), but further
review found a silent FULL OUTER JOIN semantic regression relative to the committed
0.9.0 baseline. It is present in both 0.10.0 and 0.10.1.

The candidate remains based on `e1ed71e13b0e1dd31862f771284c5711b4abb0bc`.
No production databases were accessed. Nothing was pushed or released.

## Remaining blocker: FULL OUTER JOIN

The plugin's actual Doris-to-Doris PIPE path accepts:

```sql
FROM a |> FULL OUTER JOIN b ON a.id = b.id |> AGGREGATE COUNT(*) AS n
```

0.9.0 retains a native FULL OUTER JOIN and one aggregate. Both newer releases
instead generate:

```sql
WITH __tmp1 AS (
  SELECT
    COUNT(*) AS n
  FROM a
  LEFT OUTER JOIN b
    ON a.id = b.id
  UNION ALL
  SELECT
    COUNT(*) AS n
  FROM a
  RIGHT OUTER JOIN b
    ON a.id = b.id
  WHERE
    NOT EXISTS(
      SELECT
        1
      FROM a
      WHERE
        a.id = b.id
    )
)
SELECT
  *
FROM __tmp1
```

With one matching row in each table, this returns two rows, `(1)` and `(0)`,
instead of one row `(1)`. `unsupportedMessages` is empty, the bundled native
Doris grammar accepts the SQL, and the plugin adapter returns `Ok`.

Additional fixtures use tables with columns `id, category`:

```text
a = [(1, 'g'), (2, 'g'), (4, 'left')]
b = [(1, 'g'), (3, 'g'), (5, 'right')]
```

| Case | Reference and 0.9.0 | 0.10.0 and 0.10.1 |
| --- | --- | --- |
| Total count | `(5)` | `(3), (2)` |
| Grouped count | `('g',3), ('left',1), ('right',1)` | `('g',2), ('left',1), ('g',1), ('right',1)` |
| DISTINCT category | `('g'), ('left'), ('right')` | `('g'), ('left'), ('g'), ('right')` |
| OR filter and LIMIT | Matching row, then right-only row | Matching row twice |

The last case is:

```sql
FROM a
|> FULL OUTER JOIN b ON a.id = b.id
|> WHERE a.id = 1 OR b.id = 3
|> ORDER BY 1 DESC, 3
|> LIMIT 2
```

The rewritten right branch appends `AND NOT EXISTS(...)` without grouping the
original OR predicate. The matching row enters both branches, consumes the LIMIT,
and displaces another qualifying row.

### Binary reproduction and cause

The probe was compiled once against 0.9.0 with `--release 21`, then run against the
actual published releases with each configured SDK's Kotlin/serialization libraries.
It asserts `PipeQuery` and calls `toExecutable("doris", true, true)`, matching the
plugin adapter. All six case outputs have no warnings and pass the native grammar.
Outputs are identical between SDKs and between 0.10.0 and 0.10.1.

SQLite 3.53.4 evaluated the emitted strings unchanged against `:memory:` tables,
comparing them with native FULL OUTER JOIN reference SQL. This checks the common
SQL subset, not live Doris or JDBC behavior. Per SDK, 0.9.0 matches all 12 fixture
evaluations; each newer release differs in six. Bare-join and simple-filter controls
pass. No dependencies or database engines were added to the plugin.

Published 0.10.1 source references:

- `dialects/MysqlGenerator.kt:864-869` adds unconditional `eliminateFullOuterJoin`
  to the SELECT handler that Doris inherits.
- `generator/Transforms.kt:238-273` copies aggregation, GROUP BY, and DISTINCT into
  separate branches before combining them with UNION ALL.
- `generator/Transforms.kt:175-182,269` combines the OR predicate with the right-only
  condition without preserving its grouping.

The smallest upstream Doris correction is to override its SELECT handler and retain
the previous `eliminateDistinctOn`, `eliminateSemiAndAntiJoins`, `eliminateQualify`,
and `selectSql` chain, omitting MySQL's full-join elimination. Doris supports native
FULL OUTER JOIN. The general MySQL rewrite needs a separate semantic fix or refusal;
changing UNION ALL to UNION does not repair aggregation.

No local SQL postprocessing, custom generator fork, or reduced feature support was
introduced to hide this regression. Four active desired-behavior tests require the
native join for total aggregation, grouped aggregation, DISTINCT, and OR/filter/LIMIT.
They must pass with a corrected upstream release before this candidate is committed.

## Resolved blockers and adapter change

0.10.1 removes `Tokenizer.remapAstralPositions` and documents UTF-16 token, stage,
source-map offset, and output-column contracts. Only those four core source files
changed from 0.10.0; metadata source contents are identical. The prefix filter
`missing_column > 100` now stays intact after one or two supplementary characters,
with space, LF, and CRLF separators. Stage scopes and whole-query limits remain
intact. Exact original-source offsets no longer shift.

Every published core class, 2,324 total, and metadata class, 40 total, now targets
Java 21, major version 65, rather than Java 25, major 69. No preview minors or
multi-release class variants were found. Gradle module metadata still omits its
minimum JVM attribute; `verifyEmbeddedPipes` now independently checks every bundled
class against major version 65 so a newer test JBR cannot hide this regression.

The release clarifies a separate caller obligation. Doris reports zero-based
code-point error columns, while the engine expects one-based UTF-16 columns.
The plugin previously only added one. `DorisPipesEngine.mapServerErrorExact` now
converts on the generated line first, preserves the reported server position, and
returns null for invalid lines, out-of-line columns, and overflowing integers.
It does not change the source map or guess a nearby node.

Two new regressions failed before this adapter change and passed afterward.
The same-line test uses the bundled Doris lexer's actual `charPositionInLine`,
not only a synthetic UTF-16 index, and checks both source offsets and original lines.
The older heuristic fallback remains unchanged; its ambiguous matching and invalid
coordinate handling are not covered by this exact-path fix.

## Publication, runtime, and notices

Maven Central's core and metadata version lists both contain 0.10.1; both POMs and
binary JARs are available. Core requires metadata 0.10.1. Published source files
match upstream commit `f5b9cc750fd9e2c07f744ce24987f3e82d41c00f` from
[publication run 34006272839](https://github.com/brikk/brikk-house/actions/runs/34006272839).
All downloaded files match Central SHA-1 sidecars and module artifact hashes/sizes.
Binary SHA-256 values:

```text
ec4036eb4761fb8f028a8dad3cb7790f3761d7938aee2efc5f944d7b23197506  brikk-sql-jvm-0.10.1.jar
979c9979699d281351f52bb3b6566c776aaebc28c80431354ece10ebf10d144b  brikk-sql-metadata-jvm-0.10.1.jar
```

The Gradle module descriptors retain `brikk-sql-jvmMain-0.10.1.jar` and
`brikk-sql-metadata-jvmMain-0.10.1.jar` as resolved filenames. POM requirements
remain Kotlin 2.4.10 and serialization 1.11.0. Root and JVM serialization exclusions
retain platform-provided runtimes; only core, metadata, the existing native parser,
and ANTLR are bundled alongside the product JAR.

On Java 21.0.2 with each verifier SDK's platform libraries, the standalone audit
initializes and reflects all 2,364 brikk classes, transpiles SQL, round-trips all
4,441 FunctionDef entries across five catalogs, and exercises core serialization.
Both SDKs pass. DB-261.24374.56 supplies Kotlin 2.3.20, IU-262.8665.81 supplies 2.4.0,
and both supply serialization 1.9.0. Static review of 90 referenced runtime types
and 274 members finds no missing symbols. No replacement runtime JAR was used.

Notices now identify 0.10.1 and its source commit. All third-party source headers
and metadata catalogs are unchanged from 0.10.0, so the previous provenance review
still applies, including mixed SQLGlot stamps and ClickHouse/StarRocks attribution.

## Verification matrix

Final results include all four new FULL OUTER JOIN regressions, with no skipped tests:

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 341 tests: 337 passed, 4 failed | 341 tests: 337 passed, 4 failed |
| 261-built code on DB-262.10315.24, targeted suite | 75 tests: 71 passed, 4 failed | 75 tests: 71 passed, 4 failed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Settings on 261, five extra fresh workers | 45/45 passed | 45/45 passed |
| Settings on 262, five extra fresh workers | 45/45 passed | 45/45 passed |

Only the four FULL OUTER JOIN tests fail. The old 310 full tests, 44 targeted PIPE
tests, the formerly failing Unicode cases, and both new exact-coordinate tests pass.
The first expanded matrix passed 337/337 and 71/71 before the four semantic
regressions were added; those early green results were not treated as acceptance.
The full and targeted suites were rerun after adding all four tests and strengthening
the source-map test to use the native lexer's positions.

`verifyEmbeddedPipes --rerun-tasks` passes both with a newly stored configuration
cache and on cache reuse. The ZIP contains exactly these five JARs:

```text
antlr4-runtime-4.13.1.jar
brikk-sql-jvmMain-0.10.1.jar
brikk-sql-metadata-jvmMain-0.10.1.jar
doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar
doris-intellij-plugin-1.3.0.jar
```

The task verifies all bundled classes' Java 21 bytecode ceiling, packaged licenses
and notices, correct core/metadata attribution versions, and no provider dependency.
The final dependency report confirms no added runtime, verification, or native-engine
dependencies.

The matrix uses fresh `--rerun-tasks` executions, SQL Transpiler absent/installed,
and 261-compiled/instrumented code on DB-262.10315.24 with all six exclusions from
the execution contract. Settings tests run five extra times per SDK/mode in separate
workers. Real PluginClassLoader isolation runs on both IDE generations with both
companion modes and both existing sibling ZIPs.

Trino and DuckDB distributions were not rebuilt or modified. Their SHA-256 values
remain `ccfd2c9d44013a4d371346827122c85768593e67bef35eb01139144b03e4d4d3` and
`64a52bed25dedce550b76cf550fc25d6244419c5887d80f0ca58bd1c69bdcdeb` respectively.
Isolation verifies Doris's 0.10.1 core/metadata and the companion's separate 0.6.0
copies, plus in-memory transpilation, sibling parsing, and action identity.

Plugin Verifier 1.410 completed both offline and online runs with separate initially
empty verifier homes. Both targets report Compatible in both runs, with zero reported
compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task finished successfully in 5m27s. A temporary init script enabled
repository tracing and a 15-minute task timeout. It did not alter failure levels,
suppress problems, change target IDEs, or add dependencies to the plugin. Unlike
the earlier 0.10.0 check, this run was allowed to finish both target verifications.
Reports are in `build/reports/pluginVerifier0101-online/` and
`build/reports/pluginVerifier0101-offline/`.

Verifier checks of the declared platform/database dependencies do not certify every
optional SDK integration. SDK layout warnings and unresolved optional integration
branches remain visible. The verifier can reconstruct resolved optional edges without
their optional marker, so an unavailable transitive leaf alone does not establish a
missing mandatory Doris dependency. Binary-compatible verdicts do not detect the SQL
semantic regression, which is why the query regression tests remain a separate gate.

Final status/diff/history review found only this task's changes. `git diff --check`
passes. No commit was created because the four semantic regressions remain unresolved.

## Limits and follow-up

B1 delegation, B2 SQL-aware statement boundaries, and B22 default-off per-project
policy remain unchanged. B23 is still deferred. The 0.10.0 audit's native DDL,
quoting/escaping, function-argument, stage-scope, and source-map tests remain active.
LAST_DAY date-part loss with warnings and invalid PIPE RENAME without warnings remain
pre-existing B3/B14 issues, not fixes delivered by 0.10.1.

The native grammar validates syntax, not catalog/type resolution or execution
semantics. No live Doris/JDBC queries were executed. Parameters, request ownership,
cancellation, and server-error delivery remain untested end to end. SQLite fixture
results do not claim live Doris coverage.

Local evidence is under `/tmp/opencode/doris-brikk-0101-audit/`, especially
`AUDIT.md`, `FULL-OUTER-JOIN-CONFIRMED.md`, `run-full-outer-probes.sh`, `run-matrix.sh`,
`run-final-tests.sh`, and the per-run archived JUnit XML. The worktree remains
uncommitted until the semantic regression is fixed and verified.
