# B5 catalog refresh failure preservation

Status: **fixed; verification passed**.

## Platform contract

Both supported SDK generations run the database lister before mutating the catalog
family:

```text
prepareParameters
listDatabases
traceDatabases
applyDatabases
retrieveExternalDatabases
```

`applyDatabases` marks all children sync-pending, renews returned rows, removes the
remaining pending children, then sorts. Therefore:

- A thrown lister failure starts no sweep and preserves the existing model.
- A successful empty list removes all old catalogs.
- A successful list renews present catalogs and removes absent catalogs.

The previous implementation caught every non-PCE throwable and returned an empty
list. It converted connection/query failure into a successful empty inventory,
deleting cached catalog nodes and all descendants. This only affected local IDE
metadata; it did not delete server objects.

## Fix

`DorisIntrospector` now calls the inherited `DBTransaction.performQuery` helper,
which preprocesses/traces the query, records statistics, uses `runOnce`, and closes
the runner even when it throws. The result must be non-null and the complete
inventory must validate before it returns to `applyDatabases`.

Validation rejects:

- Missing or blank `CatalogName`.
- Duplicate names under the reused `Ms*` family comparison. Doris itself can
  distinguish case in catalog names, but this model cannot retain both `foo` and
  `FOO`; failing preserves the previous complete tree instead of silently dropping one.
- Duplicate catalog IDs.

ID zero is valid and covered as the internal catalog. A single zero is not treated
as missing because the row class uses primitive Long and Doris assigns internal ID 0.
The code cannot distinguish one absent decoded ID from a legitimate zero; this is
a limitation of the current result layout, but duplicates still fail closed.

ProcessCanceledException is rethrown without a warning. Ordinary Exception values
receive a contextual log message stating that cached catalogs are preserved, then
the same exception is rethrown to the platform's introspection error handler.
Fatal JVM errors are not caught. The old “no internal catalog” warning now runs only
after a successful validated result, including a genuine empty result.

No custom success/failure wrapper or PCE substitution was added. Throwing is the
platform's supported failure channel; returning null is not allowed by the lister
contract and returning an empty collection has destructive reconciliation semantics.

## Offline regression fixture

`DorisCatalogFailurePreservationTest` creates a real DORIS `MsRoot`, initializes a
real `DorisIntrospector`, obtains its platform `DatabaseLister`, and invokes the
actual `listAndApplyDatabases` boundary with a strict recording DBTransaction. It
does not create a datasource, attach a connection, or execute SQL.

The initial hierarchy is:

```text
internal, ID 0
  cached_db
    cached_table
```

The fixture injects these results:

- The same ordinary query exception.
- Null.
- The same ProcessCanceledException.
- Null or blank name.
- Case-colliding catalog names.
- Duplicate catalog IDs.

Each failure leaves the catalog, database and table as the identical objects and
records only `SHOW CATALOGS`. The stock helper closes its runner before propagating.

A successful empty inventory removes all root catalogs. A later successful
`internal` plus `replacement` inventory renews the internal object and its existing
database/table identities, removes an old external catalog, and creates the
replacement. This drives platform reconciliation rather than duplicating the sweep
in test code.

## Verification matrix

All final tests have zero failures, errors or skips:

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 387/387 passed | 387/387 passed |
| 261-built code on DataGrip 2026.2.5, DB-262.10315.132 | 49/49 passed | 49/49 passed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| B5 test repeated five times on 261 | 15/15 passed | 15/15 passed |
| B5 test repeated five times on 262 | 15/15 passed | 15/15 passed |

Both full suites include all 387 repository tests. The 262 target includes the B4
capability containment, lower-level schema/table/column reconciliation and B1/B2/B3/B10
execution safeguards alongside B5.

`verifyEmbeddedPipes --rerun-tasks` passes with fresh and reused configuration caches.
The embedded library set, notices and Java 21 bytecode ceiling are unchanged.

Plugin Verifier 1.410 completed clean-cache offline and online runs. Both configured
targets report Compatible in both modes, with no reported compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed in 3m32s. No failure level or compatibility problem was
suppressed. Existing SDK layout/optional-integration warnings remain visible.

No live database operation, server mutation or actual `SHOW CATALOGS` request was
performed. Final source/diff/history and whitespace review passed. Evidence and
logs are under `/tmp/opencode/doris-b5-audit/`.

## Limits

This fix covers the root catalog inventory. Per-catalog schema/table/column retrieval
already catches failures before calling its explicit model-write reconciliation, so
those paths preserve prior children on thrown failures. Their remaining null-to-empty
uses should be reviewed separately because a nonstandard null result could still be
interpreted as an empty inventory.

The platform has no rollback for a failure after `applyDatabases` starts. This fix
validates all returned rows before that call, but a future exception introduced inside
the per-row `applyDatabase` implementation would need separate handling. No such
failure is present in the current simple renew operation.
