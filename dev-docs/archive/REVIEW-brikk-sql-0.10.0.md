# brikk-sql 0.10.0 upgrade verification

Historical result: **0.10.0 was blocked, not approved for release or commit**.
The current upgrade is covered by [the 0.11.0 report](REVIEW-brikk-sql-0.11.0.md).
This record describes the rejected candidate based on B22 commit
`e1ed71e13b0e1dd31862f771284c5711b4abb0bc`.
Publication was checked on 2026-09-06 UTC, following the 2026-09-05 announcement.
No production database was contacted. Nothing was pushed or released.

## Blocking findings

1. Published core and metadata now require Java 25, class-file version 69, instead
   of Java 21, version 65. Both classes fail to load on the project's configured
   JDK 21 with `UnsupportedClassVersionError`. The inspected 261 SDK declares
   Java 21 as its minimum, even though its bundled JBR is 25. Passing tests on that
   JBR does not establish compatibility with the declared minimum. A compatible
   upstream artifact or an explicit change to supported runtimes is needed.
2. Tokenizer positions changed from UTF-16 units to Unicode code points.
   `PipeStageSplitter` still uses these positions as Kotlin substring indexes.
   Execute-to-stage loses characters after supplementary characters. The plugin's
   exact source-map offsets also shift. Two active regression tests fail; neither
   has been skipped, relaxed, nor converted to an expected-failure test.

Reproducer, with one supplementary character:

```sql
FROM t |> EXTEND '\U0001F600' AS label |> WHERE missing_column > 0 |> LIMIT 5
```

Here `\U0001F600` denotes the actual U+1F600 character, not an SQL escape sequence.
The Kotlin tests use `\uD83D\uDE00` to create it. A standalone probe compiled against
0.9.0 and run unchanged with each release confirms:

| Result | 0.9.0 | 0.10.0 |
| --- | --- | --- |
| WHERE-stage prefix ending | `missing_column > 0` | `missing_column > ` |
| WHERE-stage start/end, inclusive | 34 / 57 | 33 / 56 |
| Exact filtered-column source range | 40..53 | 39..52 |
| Source substring at that range | `missing_column` | ` missing_colum` |
| Full unsliced SQL | Filter and limit retained | Identical, filter and limit retained |

The source change is `parser/Tokenizer.kt:64-89` in the published 0.10.0 source JAR.
`parser/PipeStages.kt:103` still slices UTF-16 strings using the remapped positions.
The plugin consumes them in `DorisPipesEngine.stagePrefixAt` and
`mapServerErrorExact`. No partial coordinate workaround or vendored engine fork
was introduced: the upstream splitter itself also exposes incorrect `rawSql`.
The final prefix test uses `missing_column > 100`: 0.10.0 truncates it to
`missing_column > 10`, which still parses but broadens the requested filter.

## Publication and source review

Both Maven Central version lists contain 0.10.0. The core POM requires metadata
0.10.0; both POMs and both binary JARs are available. There was no publication wait
or speculative pin. Checked endpoints:

- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-jvm/maven-metadata.xml
- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-metadata-jvm/maven-metadata.xml
- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-jvm/0.10.0/brikk-sql-jvm-0.10.0.pom
- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-metadata-jvm/0.10.0/brikk-sql-metadata-jvm-0.10.0.pom

Published source trees correspond to release commits
`587a35dbd9957547ed16ae191864ac759ea484bd` for 0.9.0 and
`2a6e2443c827809590a615892acf3a02fbd77a89` for 0.10.0. The latter was published by
[upstream run 33994463953](https://github.com/brikk/brikk-house/actions/runs/33994463953).
There was no GitHub release/tag to substitute for inspecting the actual artifacts.
Source/binary SHA-1 values match the published 0.10.0 Gradle module declarations.
This is source correspondence and checksum verification, not a reproducible build
or signing-key audit.

Source JAR SHA-256 values:

```text
core 0.10.0     f4e38f7edfa1c8ee15f5e417422bc0d60c7ae059d48045818a1dbbfd668f470f
metadata 0.10.0 f01785fb046c880b33a4b87aa4525df064d94733e39be4dfae5d5e567398d7ec
```

Relevant Doris changes in the published source, including DDL commits `70a3716`,
`7fe4599`, `6cce620`, and `3ec6492`:

- Effective tokenizer recognition of LARGEINT, IP types, legacy date/decimal
  spellings, and aggregate-storage types.
- Typed VARIANT fields/properties and AGG_STATE function signatures/nullability.
- Aggregate keys and column aggregation suffixes, auto partitioning, index
  properties, rollups, and AUTO_INCREMENT starting values.
- LIST/RANGE definitions, numeric dynamic bounds, optional interval units,
  per-partition properties, MAXVALUE forms, and bracket ranges. Multi-column
  LESS THAN tuples no longer turn into lower/upper bracket bounds.
- Doris column KEY, STRUCT colons, computed-column AS syntax, and DEFAULT/ON UPDATE
  date spellings.
- Structured materialized-view refresh and ALTER operations, CREATE/DROP/BUILD
  INDEX, partition/rollup/column/distribution ALTER TABLE operations, administrative
  commands, SHOW forms, DESCRIBE ALL, and CREATE TABLE LIKE WITH ROLLUP.
- Shared atomic AST identities, temporal pseudo-function rendering, qualification,
  shape inference, and annotation invalidation changes. Doris JSON scalar output
  now depends on the source AST's `only_json_types` flag.

The Doris function catalog is byte-identical between releases: 728 definitions,
826 names including aliases, 1,434 overloads. Metadata adds ClickHouse and StarRocks
catalogs. Notices now identify those sources and distinguish newer generated
SQLGlot headers from older retained stamps. New certification refusal rules do
not protect the plugin's `toExecutable` path, which does not call `certify`.

## Candidate changes

Only core and metadata are embedded. The Gradle module descriptors now name cached
artifacts `brikk-sql-jvmMain-0.10.0.jar` and
`brikk-sql-metadata-jvmMain-0.10.0.jar`, though the Maven coordinates/download URLs
still use `jvm`. Origin assertions and the exact packaging allowlist follow the
actual resolved names, rather than forcing POM-only resolution.
The published module attributes omit a minimum JVM version, so successful Gradle
resolution/compilation does not detect the Java 25 runtime requirement.

The POMs require Kotlin 2.4.10 and serialization core/json 1.11.0. New Gradle module
metadata uses serialization's root multiplatform coordinates. Exclusions cover
both root and JVM modules on both engine dependency paths. No verification engine,
native database engine, driver, Kotlin runtime, or serialization runtime was added.

Static runtime symbol review found no missing symbols among 87 referenced
Kotlin/serialization classes and 274 member references in either configured
verifier SDK. DB-261.24374.56 supplies Kotlin 2.3.20, IU-262.8665.81 supplies 2.4.0,
and both supply serialization 1.9.0. Dynamic tests exercise platform serialization
through a metadata round trip. This does not claim every upstream API works;
0.10.0 also removes/changes public APIs unused by this plugin.

No production action, statement-boundary, project-policy, source-map, or delegation
code changed. B23 remains deferred. SQL Transpiler 0.2.0 is an independent optional
companion with its own core and metadata 0.6.0.

## Regression coverage

`DorisPipesUpgradeTest` adds 19 tests, all active. Coverage includes escaping and
string payloads, adjacent string arguments, quoted identifiers, asymmetric date
arguments, date formatting, window arguments, GROUP_CONCAT, LAST_DAY diagnostics,
approximate-count accuracy diagnostics, arrays, storage casts, lateral views,
statement neighbors, trailing filters/limits, stage prefixes/scopes, and exact
source mapping. Successful-output cases require empty `unsupportedMessages` and
acceptance by the bundled throwing native `DorisSqlParser.parseStatement` facade.
The two Unicode desired-behavior assertions remain red.

`DorisEmbeddedDdlUpgradeTest` adds six actual new-release regressions: aggregate
keys/column aggregators, mixed partition bounds/properties, multi-column LESS THAN,
CREATE INDEX arguments/properties, typed VARIANT, and AGG_STATE signatures. Each
requires a structured AST, no raw passthrough, no warnings, exact generated SQL,
stable re-parsing, native grammar acceptance, and `NotPipe` through the plugin
adapter. Standalone differential runs pass all six with 0.10.0 and fail all six
with 0.9.0. They do not make ordinary DDL eligible for PIPE execution.

The existing 310-test baseline remains intact, including B1 simulated installation
orders, B2 comments/strings/Unicode/malformed statement boundaries and replay modes,
and B22 enabled/disabled behavior, completion, reparsing, persistence, and delegation.

## Unchanged limitations

- B3 remains open. Through the actual plugin adapter, `LAST_DAY(d, YEAR)` generates
  `LAST_DAY(d)` with `Date parts are not supported in LAST_DAY.` but still returns
  `Ok`. YEAR, QUARTER, WEEK and approximate-count accuracy diagnostics are tested.
  Passing native parsing does not make the lost arguments semantically safe.
- B14 remains open. PIPE `RENAME x AS y` emits `* RENAME (x AS y)` without warnings.
  The bundled native grammar rejects RENAME. Differential probes show identical
  behavior in 0.9.0. New DDL RENAME support does not fix this PIPE path.
- `DATE_FORMAT(event_at, '%Y-%m-%d %H:%i:%s')` normalizes to `%Y-%m-%d %T` in both
  versions, without a new CAST. `label` is backtick-quoted in both because it is
  reserved. New test expectations follow these documented, equivalent forms;
  filters, limits, and arguments are not discarded to make assertions pass.
- All selected DDL cases pass the bundled July 2026 grammar. That parser cannot
  prove catalog resolution, type correctness, permissions, or actual server
  behavior. Untested new DDL forms and cross-dialect conversions are not certified.
- No live JDBC behavior was tested, including parameters, cancellation, execution
  ownership, or server-error delivery. B10-B13 and B21 are not closed by this work.

## Verification matrix

All requested matrix lanes were run. The candidate remains blocked by the two
Unicode failures and the Java runtime requirement. No tests were skipped.

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 335 tests: 333 passed, 2 failed | 335 tests: 333 passed, 2 failed |
| 261-built code on DB-262.10315.24, targeted PIPE/DDL suite | 69 tests: 67 passed, 2 failed | 69 tests: 67 passed, 2 failed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1 passed | 1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1 passed | 1 passed |
| Settings on 261, five separate fresh workers | 45/45 passed | 45/45 passed |
| Settings on 262, five separate fresh workers | 45/45 passed | 45/45 passed |

Both full-suite and targeted-suite combinations were run again after strengthening
window-clause/alias assertions and the numeric-filter Unicode reproducer. The final
results are identical: only the two Unicode tests fail. The original 310 full tests
and 44 targeted PIPE tests pass. The normal suite's isolation test is a no-op;
the separate real-loader lanes provide the isolation evidence.

`verifyEmbeddedPipes --rerun-tasks` passes both with a newly stored configuration
cache and on cache reuse. An initial new notice-marker assertion captured the
Gradle script and failed cache serialization after its ZIP checks passed. Moving
the markers into task-configuration-local data fixed that error, without disabling
the configuration cache or weakening the checks. Final distribution contents:

```text
antlr4-runtime-4.13.1.jar
brikk-sql-jvmMain-0.10.0.jar
brikk-sql-metadata-jvmMain-0.10.0.jar
doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar
doris-intellij-plugin-1.3.0.jar
```

The final runtime dependency report contains only ANTLR and core/metadata, plus
the existing file-based native parser. Packaged LICENSE, NOTICE, and
THIRD_PARTY_NOTICES.md are present, with the correct version and new catalog
attributions. There is no SQL Transpiler provider dependency.

Plugin Verifier 1.410 via `verifyPlugin` reports **Compatible** on both configured
targets in its documented offline mode:

| Target | Compatibility problems | API notices |
| --- | --- | --- |
| DB-261.24374.56 | 0 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 0 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The normal online run stalled in Marketplace compatible-version lookups.
A thread dump showed workers waiting for HTTP responses; the client polling frame
does not itself prove retries occurred. The final log recorded an IU verdict, but
the process was stopped before it produced the DB verdict. The offline retry used a
temporary init script setting `verifyPlugin.offline=true` and a separate report
directory. No compatibility problems were suppressed and no failure levels were
relaxed. The offline dependency trees are not fully resolved. They contain
unavailable optional integrations, including JavaScript in DataGrip, and unavailable
transitive modules not marked optional: CLion/CIDR and full-line Java/PHP dependencies
on 261, and remote-client/frontend and OS/architecture modules on 262. These appear
under SDK integration components, not as new direct Doris dependencies. The
verifier nevertheless reports Compatible; that verdict must not be read as proof
that every SDK integration/dependency resolved. A complete online dependency check
remains unverified. Reports are in `build/reports/pluginVerifierOffline/`.
Neither these verdicts nor the JBR 25 test runs establish Java 21 compatibility.

Independent diff review found one gap in the new window-function assertions;
those now require each complete OVER clause and alias and pass in both full suites.
Final `git diff --check` passes. The worktree was clean at the start, and no
unrelated changes were reverted. No commit was created because acceptance remains
blocked; HEAD is still the B22 baseline.

All Gradle invocations use `mise exec -- ./gradlew` and `--rerun-tasks`. Full
261 suites use `test -Ptest.sqlTranspiler=absent|installed`. The 262 checks use
261-compiled/instrumented classes with the local DB-262.10315.24 SDK, the contract's
init script, and all six compile/instrument exclusions. The targeted selection is
the contract's five PIPE/settings/action classes plus both new regression classes.

Real isolation uses `-Ptest.pluginIsolation=true` in each IDE/companion combination
and both existing sibling ZIPs. Tests require product-owned PluginClassLoaders,
Doris core/metadata 0.10.0, companion core/metadata 0.6.0 when installed, distinct
class identities, in-memory transpilation, toggle/action stability, and sibling
dialect initialization/parsing. Ordinary flattened fixtures are not counted as
proof of product isolation.

Sibling inputs were not rebuilt or modified:

```text
Trino 0.2.0
ccfd2c9d44013a4d371346827122c85768593e67bef35eb01139144b03e4d4d3
/home/jayson/DEV/sortdev/trino-intellij-plugin/build/distributions/trino-intellij-plugin.zip

DuckDB 0.2.0
64a52bed25dedce550b76cf550fc25d6244419c5887d80f0ca58bd1c69bdcdeb
/home/jayson/DEV/sortdev/duckdb-intellij-plugin/build/distributions/duckdb-intellij-plugin.zip
```

Local evidence, including published artifacts, extracted sources, diffs, standalone
probes, matrix script/logs, and per-run archived JUnit XML:
`/tmp/opencode/doris-brikk-010-audit-20260905/`.
