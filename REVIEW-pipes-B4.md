# B4 catalog-mode DDL containment

Status: **fixed by containment; verification passed**.

## Problem

Catalog mode uses IntelliJ's public SQL Server model family to represent a Doris
hierarchy:

```text
MsDatabase       = Doris catalog
MsSchema         = Doris database
MsTable          = Doris table
MsTableColumn    = Doris column
```

The previous `DorisScriptGenerator` delegated to `MsScriptGenerator` because the
model classes matched. The semantics did not. SQL Server producers treat MsDatabase
as a database and use SQL Server DDL, including `sp_rename`. A selected Doris catalog
could therefore receive database operations or SQL Server rename syntax. No such
script was executed during this work.

The introspected model is also intentionally lossy. It retains catalog ID/name,
table name/type and basic column name/type/order, but not table engine, key model,
aggregation suffixes, distribution, partitions, properties, complete nullability,
defaults or comments. A plausible CREATE/ALTER script could be invalid or recreate
a materially different object.

## Containment

Catalog mode no longer instantiates or calls `MsScriptGenerator`. Its private
`RefusingDorisCatalogScriptGenerator`:

- Returns false for create, create-alone, drop, rename, comment, alter-comment,
  create/alter order, alter-anything, truncate, refresh and recompile capabilities.
- Returns false for every `ScriptCategory`, `canCreateWith`, `canAlter` and
  conditional capability, with no edge versions or supported property values.
- Returns no scripting options, reports every option unsupported and returns null
  from nullable source revision.
- Returns conservative false for model equality and explicit-index decisions.
- Throws `UnsupportedOperationException` from direct `makeScript` calls, including
  the requested category, rather than returning null or an empty script.

Capabilities are the primary UI gate. Empty scripts are not used: platform rename
and structured-editor paths can update local state after blank generation, creating
local/server divergence. The exception is a fail-safe for callers that bypass the
capability check, not the normal user experience.

MySQL's generator is retained only for two model-independent formatting/default-size
queries in catalog mode; those methods do not inspect objects or produce DDL. When
`doris.catalogs.experimental=false`, the public wrapper still delegates every method
to the original `MysqlBaseScriptGenerator`, preserving the flat-model escape hatch.
The startup property must not be toggled without restarting the IDE; each cached
model/extension chooses its mode during construction.

## Coverage

`DorisScriptGeneratorTest` uses a real DORIS model created through the registered
model facade and builds catalog, database, table, column, view and index nodes with
embedded backticks. It does not create a datasource, console or connection.

For every node, tests check all capability getters and all ScriptCategory values,
plus create/alter child/property decisions. They call the platform action gates:

- `CreateObjectAction.isSupported`
- `DropQueryGenerator.canDeleteAnything`
- `RenameQueryGenerator.canRename`

All return false. A DROP_COMPLETE task for every object level calls the registered
generator directly and verifies the explicit unsupported error. DROP applies across
the tested hierarchy; category capability tests cover categories whose task builders
are not valid for every object kind. Options and source revision are also checked.

The flat-mode test constructs the public generator after setting the startup property
false and verifies that it selects `MysqlBaseScriptGenerator`; switching back before
constructing another instance selects the refusing implementation. This constructor
test does not imply production runtime toggling is supported.

## Why no partial Doris implementation

Authoritative grammar/source review confirms several syntaxes, but model semantics
still prevent a safe general generator. Examples:

- External database rename is dangerous because Doris database rename targets the
  internal catalog; a same-named internal database could be changed.
- CREATE CATALOG requires resource/type properties the model does not retain.
- CREATE/MODIFY TABLE and columns require key/distribution/property/default details.
- Column rename has server/table prerequisites and connector-dependent external
  catalog behavior.
- ALTER tasks can contain more than a pure name change; emitting only the recognized
  portion would silently drop changes.

A future generator may enable a narrow operation such as a fully qualified drop or
pure rename, but must prove the full parent chain and reject mixed deltas. It needs
exact SQL tests for catalog/database/table/column object kinds, separately quoted
path components and no SQL Server markers. B4 is fixed because the unsafe behavior
is disabled, not because these operations are implemented.

## Verification matrix

All final tests have zero failures, errors or skips:

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 384/384 passed | 384/384 passed |
| 261-built code on DataGrip 2026.2.5, DB-262.10315.132 | 53/53 passed | 53/53 passed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| B4 test repeated five times on 261 | 15/15 passed | 15/15 passed |
| B4 test repeated five times on 262 | 15/15 passed | 15/15 passed |

The targeted 262 set includes catalog wiring/model-write tests and the B1/B2/B3/B10
execution safeguards. Both full suites include all 384 repository tests.

`verifyEmbeddedPipes --rerun-tasks` passes with a fresh configuration cache and on
cache reuse. The embedded library set and Java 21 bytecode ceiling are unchanged.

Plugin Verifier 1.410 completed clean-cache offline and online runs. Both configured
targets report Compatible in both modes, with no reported compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed in 3m22s. No failure level or compatibility problem was
suppressed. Existing SDK layout/optional-integration warnings remain visible.

No live database, DDL dialog confirmation, refactoring handler or generated SQL
execution was invoked. The tests verify disablement and fail-safe behavior, not a
future Doris DDL implementation. Final diff/history and whitespace review passed.
Evidence and logs are under `/tmp/opencode/doris-b4-audit/`.
