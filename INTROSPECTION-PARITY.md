# Introspection parity: `main` (1.5.8) → `grand-unification`

How each introspection behavior of 1.5.8 is provided on this branch, and which test keeps it.
"Shared" means the brikk-sql-intellij-support catalog kit, configured by `DorisCatalogProfile`.

| Behavior | 1.5.8 (`main`) | Branch | Test on the branch |
|---|---|---|---|
| Catalog listing (`SHOW CATALOGS`), internal and external | `DorisIntrospector` database lister | Same code, unchanged | `DorisIntrospectorCatalogScopeTest`, `DorisCatalogFailurePreservationTest`, live `DorisIntrospectionLiveTest` |
| Database listing per catalog (`SHOW DATABASES FROM c`) with catalog-scoped fallback | `DorisIntrospector` database retriever | Same code | `DorisCatalogQueriesTest`, `DorisDescendantFailurePreservationTest`, live lane |
| Tables, views, columns per database (`c.information_schema`) | Schema and portion retrievers | Same code | `DorisIntrospectorObjectSyncTest`, `DorisIntrospectorColumnSyncTest`, live lane |
| Default scope: `internal` deep, external catalogs enumerated | `getDefaultScope` | Same code | `DorisCatalogScopesTest`, live lane (`internal:*|*:`) |
| Explicit catalog selection expands to all its databases | `initSpecificThings` | Same code | `DorisIntrospectorCatalogScopeTest` |
| Replace, not merge (dropped databases and objects pruned) | `DorisModelWrite` sweeps | Same code | `DorisModelWriteTest`, failure-preservation tests |
| Targeted one-element refresh, scope widened by exactly the namespace | `DorisPipesAutoIntrospect` | Shared `AutoIntrospectService` (same scope union, one element, one at a time per data source) | `DorisAutoIntrospectTest`, `DorisMultiCatalogFlowTest` |
| Auto-introspection on completion of `cat.` / `cat.db.` and pipe columns | `DorisCompletionContributor` (4 call sites) | Shared `QualifiedPathIntrospectContributor` + `DorisCompletionContributor.requestMissingColumns` | `DorisAutoIntrospectTest` (backticks, pipe, current namespace, comments/strings), `DorisMultiCatalogFlowTest` |
| Auto-introspection on a query referencing an unloaded namespace or a table without columns | Not in 1.5.8 | `DorisReferenceIntrospectAnnotator`: requests after the highlighting pass, one level at a time | `DorisMultiCatalogFlowTest`, live lane |
| Console namespace (`USE db`, `USE c.db`, `SWITCH c`) for bare and partly qualified names | Platform search path + console read-back | Same; shared path resolver follows the console namespace | `DorisAutoIntrospectTest`, `DorisMultiCatalogFlowTest` (same-named tables in two databases), `DorisRelationHighlightingTest` |
| Three-part names `catalog.db.table` | Multi-catalog import pattern | Same pattern; shared imports | `DorisAutoIntrospectTest`, `DorisStarOverExternalTableTest`, `DorisRealSqlContractTest` |
| Out-of-scope references (qualified names under unloaded namespaces) stay quiet | `DorisHighlightInfoFilter` + `DorisOutOfScope` | Shared `OutOfScopeReferenceFilter`, plus the 1.5.8 rule restored | `DorisOutOfScopeTest`, `DorisAutoIntrospectTest` |
| Columns read through a derived table or enclosing query over an out-of-scope table stay quiet | `DorisHighlightInfoFilter` rule 2 | **Regressed, restored**: the shared filter only covers a column's own query; the 1.5.8 rule is back in `DorisHighlightInfoFilter`, extended through CTEs and to tables listed without columns | `DorisStarOverExternalTableTest`, `DorisMultiCatalogFlowTest` |
| Model refresh and highlighting restart after an introspection | `performFinalDiagnostics` → `updateDataSource` | `performFinalDiagnostics` → PSI cache reset + daemon restart (no data-source-changed broadcast) | `DorisIntrospectionRefreshTest`, live lane (exactly one session per trigger) |
| Banner while introspecting | `DorisPipesNotificationProvider` | Shared `IntrospectBannerProvider` | `DorisAutoIntrospectTest` |
| Introspect intention on an out-of-scope reference | Not in 1.5.8 | Shared `IntrospectNamespaceIntention` | `DorisAutoIntrospectTest` |
| Reload after `CREATE/DROP/REFRESH CATALOG` | Not in 1.5.8 | Shared `ExecutionObserver` | `DorisAutoIntrospectTest` |
| Cancellation propagates out of requests | Coroutine service | Shared service | `DorisAutoIntrospectTest` |
| Flag off (`-Ddoris.catalogs.experimental=false`): one schema level | Single-database introspector | Same code | `DorisAutoIntrospectTest`, `DorisCatalogWiringTest` |

Tests removed with code that is gone: `DorisMetaModelTest` tested a Gate 0 proof-of-shape meta-model that
was never registered (the production model is covered by `DorisModelShapeTest`); `DorisPipesAutoIntrospectTest`
is superseded by `DorisAutoIntrospectTest.testCatalogFlagOnSelectsTheSchemaAndPreservesInternalScope`, which
asserts the same scope union, single task and target.

## Live lane

`scripts/doris-live.sh` starts one `apache/doris:all-in-one-4.1.3` container (6 GB cap, own network, port on
127.0.0.1 only) with two internal databases holding a same-named table and a JDBC external catalog, runs
`DorisIntrospectionLiveTest`, and removes the container on exit. It refuses to start while another engine
container runs. Without `-Pdoris.live.url` the test returns at once.
