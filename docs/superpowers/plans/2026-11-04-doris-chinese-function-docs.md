# Complete Chinese Doris Function Documentation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Bundle available official Doris 4.x Chinese function syntax, parameters, return behavior and examples for offline IDE documentation; account explicitly for aliases and metadata-only functions.

**Architecture:** A reproducible Python importer reads a pinned Apache Doris website checkout, emits safe standalone HTML and a function-name index plus coverage manifest. The existing Doris documentation provider resolves index entries before metadata fallback, including documented functions absent from the metadata. No runtime HTTP requests. Existing object/column/UDF documentation stays scoped.

**Tech Stack:** Python standard library/import-time Markdown renderer, Kotlin, IntelliJ documentation API, Gradle/DataGrip262 tests.

**Spec:** User request in this chat: “把中文语法都补充完整吧”, interpreted in the immediately preceding function-documentation context.

## Global Constraints
- Use official Chinese 4.x documents and retain Apache license/provenance.
- Do not fabricate semantics for undocumented/internal functions.
- Preserve existing DDL and Chinese unknown-function fixes.
- No database changes or SQL execution for documentation verification.

## Review Focus
- Multiple names/aliases on one documentation page and canonical aliases with different server names.
- Markdown fences, tables, MDX tags, relative links, and unsafe HTML.
- Columns/UDFs/literals with names matching built-ins must not receive function docs.
- Functions absent from bundled metadata and special SQL grammar.
- Missing/incomplete source pages must remain visible in coverage reporting.

### Task 1: Import official Chinese documentation
- [x] Audit source pages versus the installed metadata function catalog.
- [x] Write importer tests for frontmatter, heading/name parsing, aliases, tables/code, link normalization and sanitization; observe failure.
- [x] Implement deterministic importer, generated resources, source provenance/license, and coverage report.
- [x] Run importer tests, full corpus validation, and deterministic regeneration checks.

### Task 2: Resolve bundled docs through the IDE
- [x] Add failing tests for representative date/string/array/map/json/bitmap/hll/aggregate/window/table function docs and missing-metadata functions.
- [x] Load generated function index and pages offline; map canonical aliases and function PSI without hijacking columns or qualified UDFs. Preserve engine docs fallbacks.
- [x] Check special grammar functions and aliases; add targeted regression cases.
- [x] Run documentation, highlighting and DDL tests; review entire change with a fresh reviewer.

### Task 3: Package and validate
- [x] Bump custom plugin version and commit intended code changes.
- [x] Build package, install local update and restart DataGrip.
- [x] Validate representative Chinese documentation in the actual console without executing SQL.
- [x] Record exact coverage, remaining source gaps and verification evidence; commit report and deliver package.

## Execution Ledger
- Ruling: official-source pages form the authoritative documentation set; metadata-only functions retain an explicit Chinese fallback, never invented examples.

- Ruling: CAST lives outside the function directory; import its official 4.x conversion page with an explicit URL.
- Ruling: existing shared documentation contract assumed ordinary functions have no engine docs; replace that obsolete negative assertion with direct statement/property fallback tests, retaining separate object/column/UDF negative tests.
- Verified: 6 importer tests passed, deterministic regeneration reproduced all 675 resources byte for byte. Four new runtime cases failed before implementation; 14 docs cases and previous DDL/highlighting regressions passed after fixes. Final corpus invariant test pending.

- Final automated verification: 70 DataGrip262 tests passed, 9 importer tests passed. Corpus invariant verifies all 670 reachable pages and catalog coverage641. All 672 generated pages are retained (XOR/HAMMING_DISTANCE each have duplicate source pages), 789 indexed names after rejecting the false PROPERTIES alias.
- Review findings fixed and re-reviewed: source-file-relative URLs and nested example headings in alias extraction. Stale current-version conversion links are normalized to official4.x pages. Both regressions were observed failing before fixes; reviewer confirmed resolution.

## Live verification and delivery

- Code commit: 7d5d910a385f. Custom version1.6.4.4-dev.202611040536+7d5d910a385f.
- buildPlugin passed. 70 runtime tests plus 9 generator tests passed; regeneration deterministic.
- Native disk installation UI was interrupted by changing app state; exited DataGrip normally via its native menu, verified process closure, backed up the installed1.6.4.3 directory to Downloads, copied the verified ZIP content into the plugin directory and relaunched with the native app tool.
- Verified installed JAR version and672 packaged Chinese HTML pages.
- Actual Chinese UI: Ctrl+J inside SUM and IF calls displayed full Chinese syntax, parameters, return behavior, examples and official links. No SQL executed, no source SQL modified.
- Mouse-move-only automatic hover popup was not independently exercised; shared IDE targets are tested, and the existing hover setting remains enabled from the previous task.
- Delivery: doris-plugin-1.6.4.4-zh-docs.zip plus Chinese coverage report in Downloads/doris-ddl-fix-20261104. Previous ZIPs and exact installed-directory backup retained.
- Limitation:70 metadata definitions lack a matching official4.x Chinese standalone page; these have truthful Chinese fallback content. Do not claim100% semantic documentation for those names.
