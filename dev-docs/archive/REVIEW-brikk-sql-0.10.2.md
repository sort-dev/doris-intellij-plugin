# brikk-sql 0.10.2 upgrade verification

Historical result: **0.10.2 was blocked, not approved for commit or release**.
The current upgrade is covered by [the 0.11.0 report](REVIEW-brikk-sql-0.11.0.md).
Version 0.10.2 resolves
the FULL OUTER JOIN failure from the [0.10.1 audit](REVIEW-brikk-sql-0.10.1.md),
but its newly preserved pipe SELECT DISTINCT exposes stage-order and output-column
defects. Four active downstream regression tests cover three remaining findings.

The candidate is based on `e1ed71e13b0e1dd31862f771284c5711b4abb0bc`. No production
database was contacted, and no commit, push, or release was made for this candidate.

## Blocking findings

### P1: DISTINCT moves before an earlier LIMIT or OFFSET

Reproducer with deterministic input row selection:

```sql
FROM t
|> ORDER BY id
|> LIMIT 3
|> SELECT DISTINCT category
|> LIMIT 2
```

Actual compact 0.10.2 output:

```sql
WITH __tmp1 AS (
  SELECT DISTINCT category FROM t ORDER BY id LIMIT 3
)
SELECT * FROM __tmp1 LIMIT 2
```

Fixture `t(id INTEGER PRIMARY KEY, category TEXT)`:

```text
(1,'A'), (2,'A'), (3,'A'), (4,'B'), (5,'C')
```

The first LIMIT selects three A rows; the next stage must deduplicate only those
rows. The stage-preserving reference returns `A`, but 0.10.2 returns `A, B` in the
in-memory evaluation. With `LIMIT 3 OFFSET 1` before DISTINCT, the reference returns
`A, B` while 0.10.2 returns `B, C`.

Version 0.10.1 was already wrong because it discarded pipe DISTINCT and returned
`A, A`. Version 0.10.2 retains DISTINCT but puts it at the wrong boundary, restoring
rows that the earlier restriction excluded. Reproductions without explicit ordering
also differ, but the ordered fixture establishes the issue without relying on scan
order. A control with an explicit input subquery produces the correct result.

Published `ast/PipeDesugar.kt:143-152` sets DISTINCT and replaces the accumulating
SELECT's projections before calling `buildPipeCte`. LIMIT/OFFSET are already attached
to that SELECT. A CTE is needed before applying DISTINCT, not only afterward:

```sql
WITH __tmp1 AS (
  SELECT * FROM t ORDER BY id LIMIT 3
), __tmp2 AS (
  SELECT DISTINCT category FROM __tmp1
)
SELECT * FROM __tmp2 LIMIT 2
```

GoogleSQL pipe semantics make each stage consume its immediate input table.
DISTINCT may discard ordering, but may not restore rows excluded by a preceding
LIMIT. An upstream fix must keep ORDER BY with its LIMIT/OFFSET, preserve aliases,
qualified-column resolution and source positions, and retain the later projection
boundary. Generated-SQL string replacement is not an acceptable fix.

### P1: A later DISTINCT erases head DISTINCT ON

The same assignment overwrites an existing row-selection operation:

```sql
SELECT DISTINCT ON (category) id, category FROM t ORDER BY category, id
|> SELECT DISTINCT id, category
```

For `(1,'A'), (2,'A'), (3,'B')`, the reference and 0.10.1 return rows with IDs 1 and 3.
Version 0.10.2 additionally returns ID 2. Its generated SELECT contains only plain
DISTINCT over `(id, category)`, with the head's ON operation gone. The trailing
DISTINCT was redundant for this primary-key projection, so 0.10.1's missing trailing
modifier did not change this fixture; losing the head operation in 0.10.2 does.

This uses brikk's supported DISTINCT ON extension, not a claim that native Doris
accepts that input syntax. Its native-output window rewrite must survive. A fix
guarding only LIMIT/OFFSET will not cover this case. The upstream boundary decision
must also account for an existing DISTINCT ON and its projection/ordering.

### P2: Pipe DISTINCT ON star exposes an internal column

```sql
FROM t |> SELECT DISTINCT ON (category) *
```

The generated inner projection appends `ROW_NUMBER() ... AS _row_number`. Subsequent
bare star projections export the helper. Actual result columns are
`id, category, _row_number`, while the requested star and input schema contain only
`id, category`. Qualified `x.*` and a later pipe `SELECT *` reproduce the leak;
explicit named projections do not.

`generator/Transforms.kt:430-454` contains the inherited DISTINCT ON rewrite.
Capturing pipe ON in 0.10.2 newly activates it for pipe SELECT stages. The same
helper leak already existed for ordinary SELECT heads; this report does not call
that older path a new regression. The engine must hide the helper without losing
user columns, or report that it cannot safely translate the operation.

## Reproduction and evidence quality

These findings use actual published binaries with the plugin-equivalent
`SqlFragment(input, "doris")` and `toExecutable("doris", true, true)` path.
Generated queries pass the bundled throwing Doris grammar, retain identity-tied
source maps, and have empty `unsupportedMessages`. That establishes why syntax
checking and diagnostics alone do not protect the plugin.

Java 21 probes ran with both configured SDKs' platform Kotlin/serialization libraries.
SQLite 3.53.4 executed generated SQL unchanged against `:memory:` fixtures and
stage-preserving reference SQL. Results agree across SDKs and compact/pretty output.
This is not a live Doris/server-analysis claim. A server may additionally reject
particular generated forms; native syntax acceptance does not prove semantic validity.

Independent review also identified an invalid semantic oracle in a newly added
upstream parity test: `FROM t |> DISTINCT |> SELECT category` can preserve duplicate
category values when distinct whole rows differ in other columns. Expecting a single
`SELECT DISTINCT category` does not preserve that sequence. Both earlier binaries
already have that defect. It is not another new 0.10.2 regression, and neither an
upstream test passing nor an initial broad probe matching that reference establishes
correct pipeline semantics. The ordered LIMIT/OFFSET references above explicitly
materialize the input boundary and do not make that assumption.

Evidence directories:

- `/tmp/opencode/doris-brikk-0102-audit/LIMIT-DISTINCT-CONFIRMED.md`: exact SQL,
  AST observations, fixture results, specification references, and reproduction scripts.
- `/tmp/opencode/doris-0102-independent/REVIEW.md`: head DISTINCT ON and helper-column
  findings, independent binary/SQLite comparisons, and test-oracle review.
- `/tmp/opencode/doris-brikk-0102-audit/AUDIT.md`: artifact/source/runtime audit and
  the original FULL OUTER JOIN verification.

## What 0.10.2 fixes

Both core and metadata 0.10.2 are published on Maven Central; core requires metadata
0.10.2. All 117 core and 20 metadata source files match release commit
`3ac3db569f453b0fbb51e4e05603f3f9aca33722` from successful
[publication run 34018589828](https://github.com/brikk/brikk-house/actions/runs/34018589828).
Downloads match Central SHA-1 sidecars and Gradle module hashes/sizes. Binary SHA-256:

```text
6ffbb3bad3c424fc0b1e9863660ac27695b60840400d4c05c08df0e997d8ce69  brikk-sql-jvm-0.10.2.jar
03594bab6c817de0413f58d42f1fd53126c7b0341ce6031e5fd6d3ab8aae5cd9  brikk-sql-metadata-jvm-0.10.2.jar
```

Exactly six core source files changed from 0.10.1:

- `DorisGenerator.kt` restores native FULL OUTER JOIN while retaining the DISTINCT ON,
  SEMI/ANTI and QUALIFY preprocessing chain. All four downstream FULL JOIN regressions
  pass. The original two-fixture/six-case semantic probe now matches all 12 references
  per SDK, versus six mismatches in 0.10.1.
- `Parser.kt`, `PipeNodes.kt`, `Generator.kt`, and `PipeDesugar.kt` retain pipe SELECT
  DISTINCT, including ON keys. Same-column follow-up projection and SELECT ALL-head
  cases pass, but the findings above prevent acceptance of the general change.
- `SourceMap.kt` rejects overflowing or cross-line coordinates and handles LF/CRLF
  content boundaries. Coordinate units remain UTF-16; the plugin's code-point-to-UTF-16
  server-error adapter from the earlier candidate remains necessary and unchanged.

Metadata source and binary entry contents are unchanged. All 10,021 public/protected
non-synthetic JVM member signatures remain identical. All 2,324 core and 40 metadata
classes remain Java 21, major/minor 65.0, without preview or multi-release variants.

The release contains upstreamed client generation and DDL cases, FULL JOIN semantic
tests, and additional core DISTINCT/source-map/stage tests. Those test classes are
not bundled in the plugin; downstream adapter, boundary, settings, and isolation
checks remain necessary.

## Runtime and packaging

POM runtime requirements remain Kotlin 2.4.10 and serialization core/json 1.11.0.
The plugin excludes both root and JVM runtime coordinates, retaining platform
libraries. Gradle resolves `brikk-sql-jvmMain-0.10.2.jar` and
`brikk-sql-metadata-jvmMain-0.10.2.jar`; no verification module or native engine was
added. Notices name the verified 0.10.2 source commit and preserve unchanged
third-party provenance.

On Java 21.0.2, both SDK library sets initialize/reflect all 2,364 brikk classes,
round-trip all 4,441 FunctionDef entries, and exercise core serialization. Static
review finds no missing symbols among 90 referenced runtime types and 274 members.
DB-261.24374.56 supplies Kotlin 2.3.20; IU-262.8665.81 supplies 2.4.0; both supply
serialization 1.9.0. This tests the used API compatibility, not every possible
compiler/reflection integration.

`verifyEmbeddedPipes` retains its exact five-JAR allowlist, notice/descriptor checks,
and Java 21 bytecode ceiling. No missing attribution or accidental runtime/driver
bundle was found in initial inspection. Final matrix results follow below.

## Verification matrix

All requested lanes completed. The candidate remains blocked by the four DISTINCT
regression failures. No tests were skipped or relaxed.

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 346 tests: 342 passed, 4 failed | 346 tests: 342 passed, 4 failed |
| 261-built code on DB-262.10315.24, targeted suite | 80 tests: 76 passed, 4 failed | 80 tests: 76 passed, 4 failed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Settings on 261, five extra fresh workers | 45/45 passed | 45/45 passed |
| Settings on 262, five extra fresh workers | 45/45 passed | 45/45 passed |

The failing tests are the LIMIT boundary, OFFSET boundary, head DISTINCT ON
preservation, and ranking-helper output cases. All four earlier FULL OUTER JOIN
regressions pass, as do the original 310 full tests, 44 targeted PIPE tests, Java 21
checks, and Unicode/coordinate regressions. The ordinary suite's isolation test is
a no-op; only the separate real-loader lanes count as classloader verification.

`verifyEmbeddedPipes --rerun-tasks` passes with a newly stored configuration cache
and with cache reuse. The final distribution contains exactly five JARs:

```text
antlr4-runtime-4.13.1.jar
brikk-sql-jvmMain-0.10.2.jar
brikk-sql-metadata-jvmMain-0.10.2.jar
doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar
doris-intellij-plugin-1.3.0.jar
```

The final runtime dependency report contains only the intended engine/metadata and
ANTLR modules, alongside the existing file-based native parser. All packaged notices
are present and identify 0.10.2. No verification library, database engine, driver,
duplicate Kotlin runtime, or duplicate serialization runtime was bundled.

All Gradle test runs use `--rerun-tasks`, SQL Transpiler absent/installed, and the
execution contract's 261-build/262-runtime procedure with all six compile/instrument
exclusions. Each settings class runs in five additional workers per SDK/mode.
Isolation uses actual product loaders and both unchanged sibling ZIPs:

```text
Trino:  ccfd2c9d44013a4d371346827122c85768593e67bef35eb01139144b03e4d4d3
DuckDB: 64a52bed25dedce550b76cf550fc25d6244419c5887d80f0ca58bd1c69bdcdeb
```

Plugin Verifier 1.410 reports Compatible for both configured targets in both
clean-cache offline and online runs, with zero reported compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed in 3m54s. Verifier runs use separate initially empty homes
for offline/online, the two configured target IDEs, repository tracing, and a
15-minute task timeout. No failure levels or compatibility problems are suppressed.
SDK layout warnings and unavailable optional integration branches remain visible;
Compatible verifies the plugin's referenced binary APIs, not every SDK integration
or generated SQL's semantics. Reports are in `build/reports/pluginVerifier0102-online/`
and `build/reports/pluginVerifier0102-offline/`. Scripts, logs and archived XML are
under `/tmp/opencode/doris-brikk-0102-audit/`.

Final status/diff/history review found only the accumulated upgrade changes.
`git diff --check` passes. HEAD remains the B22 baseline; no commit was created
because query-semantic acceptance remains blocked.

## Remaining scope

B1 cooperation, B2 SQL-aware statement boundaries, and B22 default-off project
policy are unchanged. B23 is deferred. LAST_DAY date-part loss with diagnostics,
approximate-count accuracy loss, invalid PIPE RENAME, and the older heuristic
error-map limitations are not fixed by this upgrade. Generic MySQL FULL JOIN
emulation remains incorrect, but Doris no longer uses it.

No live production or Doris/JDBC database was accessed. Native grammar and SQLite
fixtures do not verify server type resolution, parameters, cancellation, connection
ownership, or server-error delivery. The upgrade remains uncommitted until the
DISTINCT row-set and output-column defects are resolved and verified.
