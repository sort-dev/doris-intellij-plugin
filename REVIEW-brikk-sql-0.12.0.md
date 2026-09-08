# brikk-sql 0.12.0 upgrade verification

Status: **upgrade acceptance passed within the verification scope below**. All
required matrix lanes completed without failing or skipped tests, and no new
upgrade blocker was found. This upgrade starts from the verified 0.11.0 plugin commit
`49caa9a8f187367393852e71cde4fb0de4696beb`; it does not implement the separate plugin
B3/B10 policy changes. No production database, push, or plugin release is involved.

## What the release fixes

The published 0.12.0 binaries contain all four engine-side changes requested in the
handoff. The downstream adapter already handles their public ParseError and
UnsupportedError results; no new production exception catch was needed.

For fixture `t = [(1,'A'), (2,'A'), (3,'B')]`, columns `id, category`:

| Input | 0.11.0 behavior | 0.12.0 behavior |
| --- | --- | --- |
| `FROM t |> ORDER BY id |> LIMIT 2 |> WHERE id > 1` | IDs 2,3 | ID 2 |
| `FROM t |> ORDER BY id |> OFFSET 1 |> WHERE id > 1` | ID 3 | IDs 2,3 |
| Head `DISTINCT ON(category)` ordered by ID, then `WHERE id > 1` | IDs 2,3 | ID 3 |
| Ordinary window QUALIFY, then `SELECT DISTINCT *` | Extra `_w` column | Exactly `id,category`; IDs 1,2 |
| `FROM t |> ORDER BY id |> LIMIT 2 |> OFFSET 1` | IDs 2,3 | ID 2; `LIMIT 1 OFFSET 1` |
| PIPE RENAME without schema expansion | Warning-free invalid SQL | Typed refusal |
| PIPE RENAME with complete schema expansion | Invalid binding | Valid explicit projection |

These are actual published-binary comparisons with independently staged reference
queries. Native syntax checking alone was not used as a semantic oracle.

### WHERE and pagination

WHERE now consumes earlier LIMIT/OFFSET, DISTINCT, grouping and QUALIFY results.
The new filter stays outside the input relation that selects or ranks its rows.
Existing namespace proofs are reused; ambiguous lateral/join stars or unavailable
qualified bindings refuse instead of emitting unresolved aliases.

Pagination composes row slices. A later OFFSET reduces a prior finite LIMIT; a
combined `LIMIT n OFFSET m` stage still skips before taking its own limit. Numeric
counts are restricted to non-negative signed 64-bit integer literals. Overflow,
negative/fractional/string/expression/placeholder counts use UnsupportedError;
invalid syntax still uses ParseError. Standalone head-OFFSET checked arithmetic
from 0.11.0 remains intact.

The formerly inherited IllegalStateException/NumberFormatException pagination
reproducers now return normal `Transpile.Err` through the plugin. This resolves
those particular engine error paths, not the plugin's broader B10 catch-all policy.

### QUALIFY output

Ordinary window-dependent Doris QUALIFY is retained natively, preserving row
filtering and the requested projection without adding an exported helper. Tests
cover genuine `_w` and `_row_number` input columns and caller-selected window
columns; the fix does not delete columns based on their names.

Scalar QUALIFY uses guarded explicit-projection fallbacks. Ambiguous aliases,
unresolved stars or unsupported nested predicates refuse. Generic non-Doris star
QUALIFY lowering is not claimed fixed by the Doris-specific dispatch change.

### PIPE RENAME

Unexpanded star RENAME now throws UnsupportedError rather than producing invalid
Doris SQL without diagnostics. The existing adapter converts that to a handled
error. The old B14 characterization test was replaced because it asserted the
previous defect; its replacement checks the actual refusal and preserved message.

With a complete catalog, `SqlFragment.toStandardSql("doris", catalog, expandStars=true)`
expands simultaneous renames, preserves columns/order/filters/limits, and validates
unknown/ambiguous/repeated sources and output collisions. Invalid schema mappings
use ShapeError. The plugin does not yet supply a catalog on its execution path,
so this is verified core capability, not a claim of automatic schema-aware RENAME
execution in the IDE. The particular warning-free-invalid-SQL counterexample is
fixed, not every concern covered by the broader B14 review finding.

## Published provenance and compatibility

Core and metadata 0.12.0 are published on Maven Central, and the core POM requires
metadata 0.12.0. All 119 core and 20 metadata Kotlin source files match publication
commit `8dbfd85225d6c002769b27c0746263705a90f767` from successful
[workflow 34236711502](https://github.com/brikk/brikk-house/actions/runs/34236711502),
branch `release/0.12.0`. The later README commit is not the publication commit.

All 32 fresh root/JVM downloads across 0.11.0 and 0.12.0 match Central SHA-1 sidecars.
All 20 module file records match their payload sizes and hashes. Binary SHA-256:

```text
23ea5fa2b1d684555a0b22180d9d0c58865836514848e54dddda9256bf8d3a5a  brikk-sql-jvm-0.12.0.jar
ca8fd635f188a981048ee677345b476971494e82591f5979c7e7001703bcf93c  brikk-sql-metadata-jvm-0.12.0.jar
```

The five-file source delta was reviewed in full:

- `PipeDesugar.kt`: WHERE input boundaries, checked row slices, typed pagination
  refusals and a boundary exposing renamed outputs to subsequent stages.
- `DorisGenerator.kt`: native/guarded QUALIFY generation and unexpanded RENAME refusal.
- `Generator.kt`: indentation-aware fragment location for tracked source spans.
- `QualifyColumns.kt`: per-star simultaneous rename validation, collision checks,
  and preservation of identifier spelling, quoting and source positions.
- `SqlFragment.kt`: schema duplicate checks and Doris intermediate-name checks.

No dependency was added. POMs differ only by brikk version; Gradle module metadata
also changes artifact hashes/sizes. Metadata sources and unpacked binary entries
are identical. Notices retain the existing third-party source pins and now identify
the verified 0.12.0 publication.

All 2,368 core and 40 metadata classes are Java 21, classfile 65.0, with no preview
or multi-release variants. The artifact audit initialized all 2,408 classes on
actual JDK 21 and reflected 21,438 members per SDK. All 12,863 old public/protected
members, including synthetic members, remain available with compatible flags.

The published runtime requirements remain Kotlin 2.4.10 and serialization 1.11.0.
DB-261's Kotlin 2.3.20 and IU-262's Kotlin 2.4.0, both with serialization 1.9.0,
resolve all 96 referenced runtime types and 297 members. All five catalogs' 4,441
FunctionDef entries round-trip using platform libraries, not replacement runtimes.

## Regression coverage and semantic evidence

The old suite ran first with only the version pins changed. Its sole failure was
the obsolete expectation that no-schema RENAME returns invalid successful SQL.
That test now requires a typed handled refusal. Six new downstream tests cover
WHERE row boundaries, ranking before a later WHERE, helper-free QUALIFY with
genuine helper-named columns, typed invalid pagination, sequential versus combined
row slices, and schema-aware native RENAME. No statement-boundary, source-map,
feature-gating or delegation assertion was relaxed.

The artifact audit replayed 339 prior case definitions and added 224 definitions
across formatting modes and synthetic fixtures. Per SDK, the new probes recorded
1,034 successful results passing native grammar, strict qualification, exact
columns, diagnostics, AST stability and map checks, plus 188 typed refusals.
DuckDB produced 1,018 matching row/column observations, six inherited mismatches,
eight backtick-dialect exclusions and two unexecuted native-lateral observations.
SQLite independently produced 932 matches, six inherited mismatches and 190
native-QUALIFY exclusions. Generated SQL was executed unchanged where supported,
not rewritten just to make an assertion pass.

Cancellation, Unicode, repeated RENAME tokens, literal SQL text and exact source
spans were checked in compact and pretty modes, including 24 exact identifier
mappings per candidate SDK. All six DDL regressions pass. These observations do not
prove every possible pipeline or every live Doris planner behavior.

## Verification matrix

All test results are fresh, with zero failures, errors or skips:

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 359/359 passed | 359/359 passed |
| 261-built code on DB-262.10315.132, targeted suite | 93/93 passed | 93/93 passed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Settings on 261, five extra fresh workers | 45/45 passed | 45/45 passed |
| Settings on 262, five extra fresh workers | 45/45 passed | 45/45 passed |

The normal suite's isolation test is a no-op; only the separate product-loader
lanes provide classloader evidence. They verify Doris's own 0.12.0 core/metadata
and the optional companion's separate 0.6.0 copies, plus in-memory transpilation,
sibling parsing and action identity.

`verifyEmbeddedPipes --rerun-tasks` passes with a newly stored configuration cache
and with cache reuse. Its exact five-JAR allowlist, Java 21 bytecode ceiling,
packaged notices and provider-dependency check all pass:

```text
antlr4-runtime-4.13.1.jar
brikk-sql-jvmMain-0.12.0.jar
brikk-sql-metadata-jvmMain-0.12.0.jar
doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar
doris-intellij-plugin-1.3.0.jar
```

The final runtime dependency report contains only the intended core/metadata and
ANTLR modules, alongside the existing file-based native parser. No extra runtime,
verification module, native database engine or driver was bundled.

Plugin Verifier 1.410 completed both clean-cache offline and online runs. Both
targets report Compatible in both runs, with zero reported compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed successfully in 3m26s. Existing SDK layout warnings and
unavailable optional integration branches remain visible. The verdict is a binary
API check, not proof of all optional SDK integrations or all generated SQL semantics.
Reports are in `build/reports/pluginVerifier0120-online/` and
`build/reports/pluginVerifier0120-offline/`.

All runs use `--rerun-tasks`. The full suite builds against DB-261.24374.56.
The runtime lane uses those 261-built/instrumented classes on the newly installed
DataGrip 2026.2.5, DB-262.10315.132, retaining all six compile/instrument exclusions
from the execution contract. This is a newer patch runtime than the 0.11.0 audit.

Companion installed/absent modes remain separate. Real product-classloader tests
include both existing Trino/DuckDB ZIPs, not rebuilt or modified, with SHA-256:

```text
ccfd2c9d44013a4d371346827122c85768593e67bef35eb01139144b03e4d4d3
64a52bed25dedce550b76cf550fc25d6244419c5887d80f0ca58bd1c69bdcdeb
```

Verifier targets remain DB-261.24374.56 and IU-262.8665.81. Separate initially empty
verifier homes, repository tracing and a 15-minute timeout avoid silently accepting
partial results. No failure level or compatibility problem is suppressed.

Final source/diff/history review and whitespace checks pass. Only the intended
upgrade files are included in the commit. No production Kotlin change was needed
beyond selecting the new embedded dependencies. The plugin version remains 1.3.0;
no plugin release, push or remote plugin-CI result is claimed for the local commit.

## Remaining limits and ownership

- Plugin B3 is still open. Lossy LAST_DAY date-part and approximate-count accuracy
  results carry warnings that automatic execution does not yet reject.
- Plugin B10 is still open. Unexpected failures after claiming a request can still
  reach its broad catch-and-delegate path. Typed engine refusals are handled, but
  that does not fix all cancellation, notification or submission failure behavior.
- ORDER after LIMIT still has an inherited row-set defect in both releases:
  `FROM t |> ORDER BY id |> LIMIT 2 |> ORDER BY id DESC` returns IDs 3,2 for the
  shared fixture instead of reordering selected IDs 2,1. This was not counted as
  a new regression or a passing semantic case.
- Two older exploratory cases remain unchanged: a correlated scalar `SELECT t.*`
  can fail strict resolution, and an explicit negative head OFFSET with LIMIT can
  produce native-grammar rejection outside the repaired pipe-pagination path.
- B1/B2/B22 safeguards remain in place; B23, schema-aware execution, nested-only
  PIPE interception and captured-schema/cache adoption remain separate work.
- No live production or Doris/JDBC database was accessed. Server type/planner
  behavior, parameters, cancellation and request ownership are not verified end to
  end. SDK API checks do not guarantee every unused compiler/reflection path.
- No signing-key audit, reproducible binary build, plugin release or push was done.

The untracked handoff document was preserved unchanged and is not part of the
upgrade commit. Local audit evidence, generated SQL, row/column results, scripts,
and archived JUnit XML are under `/tmp/opencode/doris-brikk-0120-audit/`.
