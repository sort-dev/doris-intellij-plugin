# brikk-sql 0.11.0 upgrade verification

This is the 0.11.0 verification record. See [the 0.12.0 report](REVIEW-brikk-sql-0.12.0.md)
for the subsequent upgrade and its changes to the limitations recorded here.

Status: **upgrade acceptance passed within the verification scope below**. All
required matrix lanes completed without failing or skipped tests, and no new
upgrade blocker was found. This report covers the accumulated upgrade from B22 commit
`e1ed71e13b0e1dd31862f771284c5711b4abb0bc`, which embeds 0.9.0.

The rejected 0.10.0-0.10.3 candidates remain documented in their historical reports.
No production database was contacted and no push or plugin release is part of this
work. Passing this upgrade gate is not a claim that every PIPE program is safe;
existing limitations are listed below.

## Publication and source review

Both core and metadata 0.11.0 are available from Maven Central. The core POM
requires metadata 0.11.0; both version lists report 0.11.0 as latest. All 119 core
and 20 metadata Kotlin source files match publication commit
`b58d024e33e1a13fffac8ef060ff5d471f063fca` from successful
[workflow 34067451147](https://github.com/brikk/brikk-house/actions/runs/34067451147),
branch `release/0.11.0`. The later README-only commit is not the release commit.

All 24 downloaded JVM/root artifacts match Central SHA-1 sidecars. JVM module
sizes and digests match the binary/source archives. Binary SHA-256 values:

```text
f5951319462b6bac5ba0d237c7958aacda212dc4aaa8c26e42b6852446c3463d  brikk-sql-jvm-0.11.0.jar
e00395175c5391f358c5815b6392f484f96e3e4337f14a11385927a373dc489c  brikk-sql-metadata-jvm-0.11.0.jar
```

Gradle resolves logical filenames `brikk-sql-jvmMain-0.11.0.jar` and
`brikk-sql-metadata-jvmMain-0.11.0.jar`; Maven URLs use `jvm`, not `jvmMain`.
The artifact/source comparison establishes provenance and checksum consistency,
not signing-key ownership or a reproducible binary build.

The complete 0.10.3-to-0.11.0 published source delta is three files:

- `PipeDesugar.kt` replaces the Join-only boundary test with dialect-aware proofs
  of source namespaces and rowset ownership. It preserves a source alias only for
  an unchanged complete rowset and checks nested/local/correlated scopes. Ambiguous
  or unresolved stars refuse lowering rather than emitting incorrect SQL.
- `DorisGenerator.kt` supplies the LIMIT required by standalone OFFSET. The count
  is `Long.MAX_VALUE - offset`, avoiding overflow in Doris's limit-plus-offset
  arithmetic. Negative, nonliteral, fractional and overflowing head offsets refuse.
- `SqlFragment.kt` passes the source dialect into lowering for execution, source
  inventory, standard SQL and shape preparation. It retains the old public
  desugar overload; no old public/protected JVM member becomes unresolved.

Metadata source and unpacked binary contents are unchanged. POMs differ only by
brikk version; module metadata additionally changes artifact sizes/digests. No
verification module, compiler plugin, database engine or driver was added.

## Previously blocked queries

These primary 0.10.3 regressions no longer return bad SQL:

```sql
FROM t LATERAL VIEW EXPLODE(arr) e AS item |> LIMIT 2 |> SELECT e.item
FROM t LATERAL VIEW EXPLODE(arr) e AS item |> LIMIT 2 |> SELECT t.*
FROM t LATERAL VIEW EXPLODE(arr) e AS item |> LIMIT 2 |> SELECT e.*
```

They now throw the intentional engine refusal:

```text
Cannot preserve a pipe SELECT/DISTINCT input boundary over multiple or unresolved source namespaces and stars; project uniquely named input columns first
```

The plugin maps that exception to `Transpile.Err`. Its existing error branch reports
the refusal, submits no request and does not call the preceding Execute action.
Tests preserve the original safe-output-or-explicit-refusal assertions; none were
relaxed to accept unresolved aliases or added output columns.

This is conservative refusal, not a claim that arbitrary qualified lateral stars
now translate successfully. Explicit uniquely named projections, derived rowsets,
and projection-before-LIMIT alternatives pass native grammar, strict qualification
and exact output-name checks. For example:

```sql
SELECT t.id AS base_id, e.item AS item_value
FROM t LATERAL VIEW EXPLODE(arr) e AS item
LIMIT 2 |> SELECT item_value, base_id
```

The FULL OUTER JOIN, DISTINCT/LIMIT/OFFSET, head DISTINCT ON, helper-column and
Unicode regressions from the earlier candidates remain active. Helper-free DISTINCT
ON output uses native Doris QUALIFY; its grammar and projection checks pass, but
SQLite cannot execute that syntax and no live Doris run is claimed.

## Downstream changes

The accumulated changes retain the minimal core/metadata dependency set and update
origin assertions, packaging and source-provenance notices. Kotlin/serialization
exclusions cover both root and JVM coordinates from POM and Gradle module metadata.

`DorisPipesEngine.mapServerErrorExact` converts Doris's zero-based code-point
position on the generated line to a one-based UTF-16 column before exact map-back.
It preserves the server position and rejects invalid/overflowing coordinates.
Regression tests use positions from the bundled native lexer's tokens.

The adapter catches the engine's expected `UnsupportedError` and returns an error
result, rather than allowing an intentional refusal to escape to the existing
interceptor fallback. A small extraction of the production result dispatch allows
all four Execute variants to be tested with a real refusal: notification once,
zero submission, zero predecessor calls. NotPipe delegation and submission results
are unchanged. Notification titles now describe translation failures, not just
parse failures. This does not close the broader B10 catch-all finding.

The two new OFFSET tests cover zero through Long.MAX_VALUE, exact argument
preservation and overflow-safe limits, plus invalid head-OFFSET refusals. An initial
test used malformed `|> OFFSET` input and hit the inherited desugaring exception
before the new generator code. Actual 0.9.0, 0.10.3 and 0.11.0 binaries confirm that
behavior is unchanged. The generator test instead uses
`SELECT id FROM t OFFSET <value> |> ORDER BY id`; its precise UnsupportedError
message is asserted. No existing malformed-input assertion was weakened and no
broad catch was added to hide the old error path. See the limitations section.

B1 cooperative action registration/delegation, B2 SQL-aware statement boundaries
and B22's default-off per-project policy are preserved. SQL Transpiler remains an
optional companion, not an engine provider or an enablement signal. B23 is deferred.

## Runtime and semantic checks

All 2,360 core and 40 metadata classes are Java 21, major/minor 65.0, with no preview
or multi-release class entries. Gradle module metadata still omits its minimum JVM
attribute, so packaging checks enforce the Java 21 ceiling independently.

Published requirements remain Kotlin 2.4.10 and serialization 1.11.0. On actual
JDK 21, DB-261.24374.56's Kotlin 2.3.20 and IU-262.8665.81's Kotlin 2.4.0, both with
serialization 1.9.0, resolve all 96 referenced runtime types and 295 members.
All 2,400 candidate classes initialize and reflect. All five catalogs' 4,441
FunctionDef entries, whole catalogs and selected core data types serialize with
the platform libraries, not replacement runtime JARs.

The artifact audit ran 94 prior query cases and 184 new boundary cases across
versions, formatting modes and SDKs. All 664 returned candidate Doris results pass
the throwing native grammar, empty-diagnostic, source-map-identity and AST-stability
checks. The new probe's 360 returned results also pass strict qualification and
exact generated-output-name checks; expected refusals retain UnsupportedError.

SQLite `:memory:` evaluation has 192 matching candidate observations, six explicitly
characterized inherited mismatches and ten native-QUALIFY exclusions. Generated SQL
was not rewritten to manufacture passing results. A separate independent review
covered 51 focused cases, including child scopes, case rules, modified stars and
OFFSET overflow; all 32 candidate SQLite comparisons matched. No new release
regression was found in those reviews.

These observations are not exhaustive correctness proofs. Ordinary QUALIFY helper
leakage and WHERE-after-LIMIT are among the inherited defects, not passing semantic
cases. Native grammar acceptance alone does not establish alias/type resolution or
server semantics. No production or live Doris/JDBC query was run.

## Verification matrix

All test results are fresh, with zero failures, errors or skips:

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 353/353 passed | 353/353 passed |
| 261-built code on DB-262.10315.24, targeted suite | 87/87 passed | 87/87 passed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Settings on 261, five extra fresh workers | 45/45 passed | 45/45 passed |
| Settings on 262, five extra fresh workers | 45/45 passed | 45/45 passed |

The original 310 full tests and 44 targeted PIPE tests remain intact. The 43 added
tests cover engine/DDL regressions, preserved safeguards, known-issue diagnostics,
OFFSET generation and handled refusals. The normal suite's isolation test is a
no-op; the separate real-loader lanes provide that evidence.

`verifyEmbeddedPipes --rerun-tasks` passes both with a newly stored configuration
cache and with cache reuse. The ZIP contains exactly these five JARs:

```text
antlr4-runtime-4.13.1.jar
brikk-sql-jvmMain-0.11.0.jar
brikk-sql-metadata-jvmMain-0.11.0.jar
doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar
doris-intellij-plugin-1.3.0.jar
```

The task checks the classfile ceiling, packaged LICENSE/NOTICE/THIRD_PARTY_NOTICES,
current version and attribution markers, and removal of the provider dependency.
The final runtime dependency report contains only core/metadata and ANTLR modules,
plus the existing file-based native parser. No extra engine or runtime is bundled.

Plugin Verifier 1.410 reports Compatible for both targets in both clean-cache
offline and online runs, with zero reported compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed in 3m39s. Existing SDK layout warnings and unavailable
optional integration branches remain visible. Compatible is a binary-verification
verdict for the plugin's referenced APIs, not proof of every optional SDK product or
the semantics of all generated queries. Reports are in
`build/reports/pluginVerifier0110-online/` and `build/reports/pluginVerifier0110-offline/`.

All Gradle runs use `--rerun-tasks`. The 262 lane uses 261-compiled/instrumented
classes, DB-262.10315.24, the opt-in init script and all six compile/instrument
exclusions in the execution contract. Five extra settings workers run per SDK/mode.
The isolated product-loader tests include both existing Trino/DuckDB ZIPs, unchanged:

```text
Trino:  ccfd2c9d44013a4d371346827122c85768593e67bef35eb01139144b03e4d4d3
DuckDB: 64a52bed25dedce550b76cf550fc25d6244419c5887d80f0ca58bd1c69bdcdeb
```

Isolation checks Doris's own 0.11.0 core/metadata, SQL Transpiler 0.2.0's private
0.6.0 copies when installed, distinct identities, in-memory transpilation, sibling
parsing and stable action identities. The normal fixture's flattened classpath is
not counted as evidence of real product isolation.

Verifier runs use separate initially empty homes, both configured targets,
repository tracing and a 15-minute task timeout, without relaxing failure levels
or suppressing compatibility problems. No sibling plugin was rebuilt or modified.

Independent source/diff review found no new regression. Final diff/whitespace and
history review passed. Only the accumulated upgrade files are intended for the
commit; unrelated worktree changes were not reverted. The plugin version remains
1.3.0: this task updates the embedded engine, not a plugin release. No remote plugin
CI run or push is claimed for the local commit.

## Remaining limitations

- B3/B14 remain: LAST_DAY date-part and approximate-count accuracy loss carry
  diagnostics that the plugin does not yet reject; PIPE RENAME can produce
  warning-free SQL rejected by the bundled grammar.
- B10's unexpected-exception fallback remains. Malformed pipe OFFSET stages such
  as `FROM t |> OFFSET -1`, `?`, or `1 + 1` throw inherited IllegalStateException;
  fractional/overflowing values can throw NumberFormatException. Quoted `'1'`
  is coerced by that older path. The new typed head-OFFSET refusal does not fix it.
- WHERE after LIMIT, ordinary QUALIFY helper projection, PIPE-AS source-shape
  fallback and the older heuristic error mapper retain pre-existing limitations.
- Native lateral/QUALIFY server execution, parameters, cancellation, request-owner
  behavior and actual server-error delivery remain untested end to end. No new
  captured-schema/cache API or nested-only PIPE interception was adopted.
- Static/runtime SDK checks do not guarantee every unused reflection or compiler
  integration against versions below the POM requirements. No signing-key audit
  or reproducible binary build was performed.

Local evidence is under `/tmp/opencode/doris-brikk-0110-audit/`, especially `AUDIT.md`,
`OFFSET-ERROR-SCOPE.md`, the matrix scripts/logs and archived JUnit XML. Independent
review evidence is under `/tmp/opencode/doris-0110-independent-review/`.
