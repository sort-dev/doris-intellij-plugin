# RESEARCH: Persisted-model migration across the `doris.catalogs.experimental` flag

*Research spike, 2026-07-08. Read-only bytecode investigation of DataGrip 2026.1.3 (DB-261) against
`/Users/jminard/Applications/DataGrip.app/Contents/plugins/DatabaseTools/lib/**/*.jar`, `javap` from
JBR 17.0.8.1 (same technique as RESEARCH-catalog-introspection.md appendix). This decides the
merge-gate item "model incompatibility (flag on/off)": persisted data-source models serialized under
one meta-model shape fail to deserialize under the other
(`ModelImporter.populateModel: ... 's family not in parent`).*

---

## TL;DR

- The persisted model is **`.idea/dataSources/<uniqueId>.xml`** (plus a binary fast-path cache in
  `.idea/dataSources/<uniqueId>/entities/entities.dat*`). The file header carries `dbms="DORIS"`,
  `family-id="DORIS"`, `format-version="4.55"` — **nothing that distinguishes our two shapes**. Both
  flag states serialize as DORIS; the shape only shows up structurally in the node tree.
- The platform's migration machinery (format-version + hardcoded `Converter_4_NN` list) is **not
  extensible by plugins** and keys only on its own serializer version, never on meta-model shape.
  Its invariant is *one dbms = one meta-model, forever*; our flag is the first thing to break it.
- The observed error is only half the story. `'s family not in parent` is a **`LOG.error` (red
  IDE-internal-error entry), not a throw**; the mismatched node is silently dropped. If the dropped
  node had serialized children, the *next* element throws `ImportException` → the platform's
  corruption path kicks in: file renamed to a timestamped backup, warning balloons, empty model,
  clean rebuild on next introspection (**self-healing but noisy**). If the mismatched nodes had *no*
  children, nothing throws → the stale file **survives and the red error repeats every startup**
  (not self-healing). Neither outcome touches the data-source config itself — no dead shells from
  this failure mode.
- There is a **perfect, race-free hook**: the app-level message-bus topic
  `DataSourceModelStorage.TOPIC` fires `Listener.started(Project)` **synchronously, on the loading
  thread, before any model file is opened**. A listener there can sniff each Doris model file's
  shape and delete stale ones; `readModel` then hits its `Files.exists()` guard and returns null
  **silently** — identical to a never-introspected data source.
- **Recommendation: option (b)** — auto-detect + pre-emptively clear via that listener (~80 lines),
  with the platform's corruption path as the untouched safety net. Details and ranking below.

---

## 1. Where the model lives, its format, and what happens after the error

### 1.1 Location and format

`DataSourceModelStorageImpl.getModelPath(LocalDataSource)` (bytecode, `intellij.database.connectivity.jar`):

```
DataSourceStorage.getStorageDir(project) + "/" + dataSource.getUniqueId() + ".xml"
```
(invokedynamic recipe `/.xml`).

`DataSourceStorage.getStorageDir(Project)` (`intellij.database.core.impl.jar`):
`getStoragePath(project).getParent()` + `"/dataSources"` for directory-based (`.idea`) projects,
or + `"/.ideaDataSources"` for legacy `.ipr` (StorageScheme.DEFAULT) projects. I.e. for every normal
DataGrip/IDEA project: **`<project>/.idea/dataSources/<uniqueId>.xml`**, one file per data source.

Verified against a real file (`~/DEV/Theorem/work/SqlTestIdea/.idea/dataSources/5282…abd.xml`):

```xml
<dataSource name="HSQLDB (in-memory)">
  <database-model serializer="dbm" dbms="HSQLDB" family-id="HSQLDB" format-version="4.32">
    <root id="1"> <ServerVersion>2.6.0</ServerVersion> </root>
    <database id="2" parent="1" name="PUBLIC"> <Current>1</Current> </database>
    <schema id="3" parent="2" name="INFORMATION_SCHEMA"/>
    ...
  </database-model>
</dataSource>
```

Flat id/parent node list — exactly the shape difference we care about is visible in plain text:
flag-OFF Doris writes `<schema id=... parent="1">` directly under root; flag-ON writes
`<database id=... parent="1">` (catalogs) with `<schema parent="<db-id>">` beneath.

**Second persistence layer (matters for cleanup):** a binary "entity storage" fast path in
`.idea/dataSources/<uniqueId>/entities/entities.dat*`. `ModelImporter.deserializeFast` first tries
`BaseModel.restoreFromStorage(model)` (restores from `entities.dat`, skipping XML node parsing when
the file's `format-version` == CURRENT); only on failure does it `LOG.warn` + `clearModel()` and
fall back to parsing the XML hierarchy (`ModelImporter.deserializeFast`, offsets 14–116;
`ModelEntityStorage$Backend.loadInfo/isPersistent/clear`). A shape mismatch fails the fast restore
too, so the observed stack goes through the XML path — but **any manual invalidation must remove
both the `.xml` and the `entities/` dir** to be airtight. (`storage_v2/_src_/` next to it is the DDL
source-text cache, cleared separately by `DatabaseView.ClearSrcStorage`; harmless to leave.)

### 1.2 Exact failure semantics — the error is LOGGED, its knock-on is THROWN

`ModelImporter.populateModel` (`intellij.database.core.impl.jar`, bytecode offsets 217–330):

- For each serialized node, resolves parent from an id→object map, then
  `parent.familyOf(kind)`. When the parent has no family for that kind (a `database` node under a
  single-level `MysqlRoot`, or a `schema` node directly under a multi-level `MsRoot`):
  - offset 308–321: **`LOG.error("<node>'s family not in parent")`** — string recipe
    `'s family not in parent`, exactly the observed
    `database(2:clickhouse_rac) in 1 ObjectId=... 's family not in parent`. `Logger.error(String)`
    manufactures the `java.lang.Throwable` seen in the report and lands in the red "IDE Internal
    Errors" balloon — **but execution continues**.
  - `BulkAppender.next(mem, null)` records the null family; `BulkAppender.create` returns null when
    `myFamily == null` (offsets 8–16) and `commit()` no-ops (offset 0–7) → **the node is silently
    dropped and never registered in the id map**.
- The *children* of the dropped node then hit offsets 232–275: `map.get(parentId) == null` →
  **`throw new ImportException("Object X references parent Y that is not found or not processed
  yet")`** — `ImportException extends RuntimeException`.

### 1.3 What the caller does with the ImportException — the corruption path

Propagation: `populateModel` → `importToModel` (rethrows after `model.shelve(false)`) →
`deserializeFast` → `ModelImporter.deserialize` → `DataSourceModelStorageImpl$Companion.readModel`
→ `DataSourceModelStorageImpl.readModel(dataSource)` — whose Throwable handler (exception table
116→263) fires `DataSourceModelStorage.Listener.failed(project, ds, t)` and **rethrows** — into the
per-data-source loop of the private `DataSourceModelStorageImpl.loadModels` (exception table:
`ProcessCanceledException` rethrown; `java.lang.Exception` caught), which then:

1. consumes **`DasUtil.emptyModel()`** for that data source (offsets 150–153) — the data source
   keeps loading, with no model;
2. calls **`DataSourceStorage.processCorruption(project, modelPath, e)`** (offset 204):
   `LOG.warn(t)` then `backupCorruptedVersion` → **renames** the model file to
   `<name>-<yyyyMMdd-hhmmss>.xml`, writes the error text to a sibling file, and shows a warning
   notification (group "Database configuration", title key
   `notification.title.failed.to.load.data.sources`, content key
   `notification.content.corrupted.backup.copy.created`);
3. `ErrorHandler.addError(...)` → after the loop, `Companion.notifyErrors` posts a second
   notification: `"{0} Data Sources: <a href="sync">refresh</a> required"`.

**Consequences (answers "dropped and rebuilt, or dead shell?"):**

- The data-source **configuration is untouched** — it lives in `.idea/dataSources.xml` /
  `dataSources.local.xml`, a different store. No dead shells: the earlier "dead data-source shells"
  came from the 252-build-on-261 half-load where `Dbms.byName("DORIS")` had no registered dbms;
  here the plugin is loaded and the dbms resolves.
- Because the stale file was **renamed away**, the next startup is clean; the tree is empty until
  the user connects/refreshes, at which point normal introspection rebuilds the model
  (`RefreshActionsLogic.runInitialForLocalDataSourceWithEmptyModel` path). **Self-healing, but
  noisy**: one red IDE-error entry per mismatched top-level node, plus two warning balloons, plus
  backup junk in `.idea/dataSources/` (visible in VCS diffs if `.idea` is committed).
- **Non-healing edge case**: if every mismatched node had *no serialized children* (e.g. flag-ON
  introspected only the catalog list, never expanded any catalog), no `ImportException` is thrown.
  The import "succeeds" with a root-only model, the stale file is **not** renamed, and the red
  `family not in parent` errors **repeat on every startup** with a silently empty tree. The
  platform never heals this by itself (the file is only rewritten after a successful load with
  `wasMigrated == true`, see §2).

## 2. Version/identity in the file, and the platform's own migration story

### 2.1 What the header carries

`ModelExporter` writes exactly: `serializer="dbm"`, `dbms=<Dbms.getName()>`,
`family-id=<Dbms.getName()>` (legacy alias; `deserializeModelHeader` reads
`chooseNotNull(attr("dbms"), attr("family-id"))`), `format-version=<Version>` and optionally
`from-version`. `ModelSerializationVersions` (DB-261): **`CURRENT_VERSION = 4.55`**,
`MIN_VERSION = 2.4`.

**There is no meta-model/shape identity.** Both our shapes write `dbms="DORIS"
family-id="DORIS"`. The entity-storage header is equally blind: `ModelEntityStorage$Info` =
`{String dbms; int version; int idCurrentValue}`.

### 2.2 Platform migration mechanism = converters keyed on format-version, not invalidation

`ModelImporter.deserialize`: if file `format-version == CURRENT_VERSION` → fast path; otherwise
`wasMigrated = true`, `LOG.warn("Reading from xml because of version: …")`, slow XML parse, then
`ModelConverters` applies every `Converter_N_M` with version > file version
(`converters/Converter_2_7 … Converter_4_53`, a **hardcoded list built in the `ModelConverters`
constructor — not an extension point**). After a migrated load,
`DataSourceModelStorageImpl.loadModels` immediately calls `writeModel(dataSource)` (offsets
100–130: branch on the `wasMigrated` component of the pair returned by `readModel`) — i.e. the
platform *rewrites in the new format*, it never "bumps a version to force rebuild".

Plugins can neither register converters nor influence `format-version`. **There is no platform
mechanism we can key our shape change on.**

### 2.3 How the platform handles a changed dbms

It doesn't need to invalidate: `deserializeFast`/`importModel` build the model with
**`ModelFactory.createModel(mem.getDbms())` — the dbms stored in the file**, not the data source's
current dbms. `ModelConverters.alignDbmsWithRoot` even re-aligns the header dbms with the root
node's persisted `Dbms` property. So after a driver/dbms switch the old model still deserializes
under its old meta-model and is simply replaced by the next introspection. The platform's invariant
is *dbms → meta-model is a total, immutable function* (`ModelFacade.getMetaModel` per dbms). Our
flag makes `DORIS` map to two different meta-models across sessions — that's the precise invariant
violation, and why nothing in the platform detects it gracefully.

## 3. Hooks our plugin has

### 3.1 The deterministic pre-read hook: `DataSourceModelStorage.TOPIC`

```java
public interface DataSourceModelStorage$Listener {   // app-level Topic (application message bus)
  void started(Project);                              // bulk: BEFORE any file is read
  void started(Project, LocalDataSource);             // per-DS: after Files.exists, before parse
  void finished(Project, LocalDataSource, DasModel, ModelImporter);
  void failed(Project, LocalDataSource, Throwable);   // fired just before rethrow
  void finished(Project);
}
```

`DataSourceModelStorageImpl.readStateHeavy` bytecode: `syncPublisher(TOPIC).started(project)` at
offset 73, the file-reading `loadModels(...)` at offset 88 — **synchronous, same thread, strictly
before the first model file is opened**. Model loading itself is deferred to
`StartupManager.runAfterOpened` / `runWhenProjectIsInitialized`
(`DataSourceStorage.readStateImpl` → `getModelStorage().loadModels(list)`), so a plugin
`ProjectActivity` would *race* it — the topic listener does not.

In `started(Project)` we can, for every `DataSourceStorage.getProjectStorage(project)
.getDataSources()` with `getDbms() == DorisDbms.DORIS`:

1. build `path = DataSourceStorage.getStorageDir(project) + "/" + ds.uniqueId + ".xml"`
   (both methods public static / public);
2. sniff the shape with a cheap scan — first element matching
   `<(database|schema)\s[^>]*parent="1"` decides `catalogs` vs `flat` (see §1.1; no XML lib
   needed, first few KB suffice);
3. if the shape contradicts `DorisCatalogs.enabled`: delete the `.xml` **and** the sibling
   `<uniqueId>/entities` directory.

`readModel` then fails its `Files.exists()` guard (offsets 66–79) and **returns null silently** —
the exact code path of a data source that has never been introspected. No log error, no
notification, no corruption backup. (Also fully covers the childless-catalog edge case of §1.3.)

Per-DS `started(project, ds)` is too late for a *silent* fix (the exists-check already passed;
deleting there lands in the corruption path), and `failed(...)` is post-hoc — by then
`processCorruption` will run regardless. `DorisIntrospectorFactory`/`DorisModelFacade` never see
the serialized bytes (`ModelFactory.createModel(dbms)` consults the facade only for the
meta-model), so no interception is possible there.

### 3.2 Public API to clear a cached model (post-load)

`LocalDataSource.clearModel()` — public. Clears the introspection cache and empties the
`BasicModModel` (via `DbImplUtilCore.performSrcOperation`). Persisting the cleared state and
re-introspecting is what the platform's own action does —
`RefreshActionsLogic.asyncForgetModelAndRefreshForDataSource`:

```
dataSource.clearModel();
dataSourceStorage.updateDataSource(dataSource);   // triggers SavingListener -> writeModel
runDataSourceGeneralRefresh(project, dataSource); // only for the ForceRefresh variant
```

Usable any time after load as a programmatic "Forget"; for *preventing* the startup error the
file-level pre-read delete of §3.1 is required.

### 3.3 A place to stamp shape, if wanted

`LocalDataSource.setAdditionalProperty(String, String)` / `getAdditionalProperty` — persisted with
the data-source config. Could store `doris.model.shape = flat|catalogs` at introspection time. Note
however: files written by already-shipped plugin versions carry no stamp, so a structural sniff is
needed as the fallback anyway — and the sniff alone is authoritative (the file *is* the state).

## 4. Options, ranked

| # | Option | Code cost | UX on mismatch | Verdict |
|---|--------|-----------|----------------|---------|
| 1 | **(b) auto-detect + pre-read clear** via `DataSourceModelStorage.TOPIC.started(Project)` listener (§3.1) | ~80 lines + listener registration | **Silent.** Tree empty until next connect/refresh (identical to a new data source); no errors, no balloons, no backup junk; covers both flag directions and future default-flips of the flag | **Recommended** |
| 2 | (d) rely on platform self-heal | 0 | Red IDE-error per mismatched node + "Failed to load data sources" + "N Data Sources: refresh required" balloons; backup files accumulate in `.idea/dataSources/`; **and it doesn't heal at all in the childless-catalog case (§1.3) — errors every startup** | Acceptable *safety net*, not a story |
| 3 | (a) do nothing + document "Forget Cached Schemas" | 0 | Same noise as (d), *plus* a manual chore. The action exists: id `DatabaseView.ForgetModelAction`, text **"Forget All Cached Schemas"**, in DB-261 located in the database-tree context menu under the **Diagnostics** popup group (not "Database Tools" as in older docs; `group.DatabaseView.Diagnostics.text=Diagnostics`). Sibling `DatabaseView.ForceRefresh` ("Clear cached schemas and refresh from scratch") both clears and re-introspects. But the damage (error balloons) happens at startup **before** the user can act, every toggle | Documentation-only is not shippable for a flag we expect users to toggle |
| 4 | (c) shape stamp in `additionalProperties` + proactive invalidation | ~stamp writes in introspector + startup compare | Same silent UX as (b) once stamped, but blind for models written by versions that predate the stamp — needs the §3.1 sniff as fallback anyway | Strictly dominated by (b); the file itself is the more reliable "stamp" |

Ranking: **(b) > (d) > (a) > (c)** — (c) is more machinery for less coverage; (a)/(d) leak
platform-red errors to users on every toggle/upgrade.

## 5. Recommendation

**Implement (b): an application-level `DataSourceModelStorage.Listener` whose `started(Project)`
sniffs and deletes stale Doris model files before the platform reads them.** Concretely:

- Register in plugin.xml under `<applicationListeners>` against
  `com.intellij.database.dataSource.DataSourceModelStorage$Listener`.
- In `started(Project)`: iterate `DataSourceStorage.getProjectStorage(project).getDataSources()`,
  filter `getDbms() == DorisDbms.DORIS`, compute
  `getStorageDir(project) + "/" + ds.uniqueId + ".xml"`, sniff first `parent="1"` element
  (`database` = catalogs shape, `schema` = flat shape), compare with `DorisCatalogs.enabled`; on
  mismatch delete the `.xml` and the `<uniqueId>/entities` directory, and log one
  `DorisCatalogs.info(...)` line so testers can grep it. All other listener methods no-op.
- Leave the platform corruption path untouched as the safety net for anything the sniff misses.

Why this one: it is the only option that is *silent* (empty-tree-until-refresh is precisely the
platform's normal "new data source" experience), *complete* (handles ON→OFF, OFF→ON, plugin
upgrades that flip the default, and the non-healing childless case), *race-free* (synchronous
pre-read hook proven in bytecode), and *cheap* (no new persistent state, no platform internals
beyond three public APIs: the Topic, `getStorageDir`, `getUniqueId`). The flag being a read-once
system property means shape changes only ever happen across an IDE restart — so a startup-time hook
has 100 % coverage by construction.

Residual UX to document in the flag's help text: after toggling the flag and restarting, Doris
data-source trees start empty and repopulate on first connect/refresh (auto-introspection on
connect). That is the honest cost of a shape swap; nothing in the platform can migrate a
single-level tree into a two-level one in place.

---

## Appendix: evidence index (class.method → fact)

All DB-261 (`DataGrip 2026.1.3`), `javap -p -c` unless noted.

| Evidence | Where | Fact |
|---|---|---|
| `DataSourceModelStorageImpl.getModelPath` | `intellij.database.connectivity.jar` | `storageDir + "/" + uniqueId + ".xml"` |
| `DataSourceStorage.getStorageDir/getStoragePath` | `intellij.database.core.impl.jar` | `.idea/dataSources` (dir-based) / `.ideaDataSources` (.ipr) |
| `ModelExporter` header writes | core.impl | `serializer="dbm" dbms family-id format-version [from-version]` |
| `ModelSerializationVersions.<clinit>` | core.impl | CURRENT 4.55, MIN 2.4 |
| `ModelImporter.deserialize` | core.impl | version==CURRENT → fast path; else `wasMigrated=true` + converters |
| `ModelImporter.deserializeFast` | core.impl | tries `BaseModel.restoreFromStorage` (entities.dat) first; falls back to XML |
| `ModelImporter.populateModel` off. 296–330 | core.impl | `familyOf==null` → `LOG.error("…'s family not in parent")`, node dropped (no throw) |
| `ModelImporter.populateModel` off. 248–275 | core.impl | orphaned child → `throw ImportException` (RuntimeException) |
| `ModelImporter$BulkAppender.create/commit` | core.impl | null family → create returns null, commit no-ops → silent drop |
| `ModelConverters` ctor + `alignDbmsWithRoot` | core.impl | hardcoded converter list (no EP); dbms re-aligned from root node property |
| `DataSourceModelStorageImpl.readModel` off. 61–79, 116→263 | connectivity | `Files.exists` guard → null (silent); Throwable → `Listener.failed` + rethrow |
| `DataSourceModelStorageImpl.loadModels` (private) off. 100–130, 150–218 + exc. table | connectivity | migrated→`writeModel`; Exception→`emptyModel` + `processCorruption` + `ErrorHandler.addError`; PCE rethrown |
| `DataSourceModelStorageImpl.readStateHeavy` off. 73/88 | connectivity | `started(Project)` fired synchronously **before** file reads |
| `DataSourceStorage.processCorruption/backupCorruptedVersion` | core.impl | rename to `<name>-<yyyyMMdd-hhmmss>.<ext>` + error file + "Failed to load data sources" notification; no-op if file absent |
| `DataSourceModelStorageImpl$Companion.notifyErrors` | connectivity | `"{0} Data Sources: refresh required"` notification |
| `DataSourceStorage.readStateImpl` + `runWhenProjectIsInitialized` | core.impl | model load deferred via StartupManager → ProjectActivity races, listener doesn't |
| `LocalDataSource.clearModel` | connectivity | public; clears introspection cache + model |
| `RefreshActionsLogic.asyncForgetModelAndRefreshForDataSource` lambdas | intellij.database.impl.jar | Forget action = `clearModel()` + `updateDataSource()` [+ refresh] |
| `database-plugin.jar META-INF/plugin.xml` + `DatabaseBundle.properties` | database-plugin.jar | `DatabaseView.ForgetModelAction` = "Forget All Cached Schemas", in **Diagnostics** popup group; `DatabaseView.ForceRefresh` = "Clear cached schemas and refresh from scratch" |
| `LocalDataSource.setAdditionalProperty/getAdditionalProperty` | connectivity | persisted per-DS stamp available (not needed for chosen option) |
| Real file `SqlTestIdea/.idea/dataSources/5282….xml` + `…/entities/entities.dat*` | disk | format ground truth; entity storage location |
