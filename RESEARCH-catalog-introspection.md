# Surfacing Doris External Catalogs in DataGrip's Database Tree

*Research spike, 2026-07-07. Evidence gathered by bytecode introspection of DataGrip 2026.1
(`plugins/DatabaseTools/lib/modules/*`), `javap` from JBR 17.0.8.1. Commands and raw outputs in
the appendix. No production code was changed; this document is the sole deliverable.*

## Verdict up front

Apache Doris is a **multi-catalog** server: `internal` plus any number of external catalogs
(`hive_archive`, `iceberg_*`, ...), each holding databases, each holding tables — a genuine
**catalog → database → table** three-level namespace. DataGrip's data model already has a name for
this shape (**root → database → schema → table**, the Postgres/SQL-Server family), but the MySQL
model our plugin inherits **collapses the database level** (root → schema → table, "single
database"). That collapse — not the wire protocol, not Connector/J — is why external catalogs are
invisible. Issue #2's protocol analysis is correct and confirmed here.

**Recommended architecture: a phased (c) → (a) path.**

- **Ship now — (c) per-catalog data sources as the *blessed, documented, tooled* pattern.** Zero
  platform risk, works today, and — decisively — it is the *only* option that does not fight a
  hard `checkcast` somewhere in the inherited MySQL stack (see F7/F9). Add a "Create data sources
  for all catalogs" helper action + docs so the UX is a feature, not a workaround.
- **Build behind a gate — (a) a true two-level model + custom multi-database introspector.** This
  is the only path that yields one tree with catalog nodes *and* native 3-part
  `catalog.db.table` resolution. The platform provably supports the shape (SQL Server ships it:
  `MsRoot extends BasicModMultiLevelMultiDatabaseRoot`, F3, and switches databases over JDBC
  exactly as Doris `SWITCH` does). It is gated because the model family and **every** MySQL helper
  we inherit by fallback are a matched, hard-cast pair (F6/F7): the model is the large unknown and
  the inherited `MysqlBaseObjectBuilder` casts model nodes to `MysqlBaseTable` (F7) — the
  generalization of the historic `GenericImplModel`-cast crash.
- **Reject — (b) flatten `catalog.database` into dotted schema names.** Medium introspector work
  that still lands you needing the *same* undocumented custom-resolve machinery as (a) (3-part
  references will not resolve against 2-level names, F8), for a strictly worse tree. It is a trap;
  demoted.

**Honest effort estimate:** (c) tooled + documented: **2–4 days**. (a) to a working catalog tree
with 3-part resolution: **4–8 weeks**, front-loaded on a 1-week Gate-0 spike that either clears the
inherited-cast minefield or kills the path. **Biggest risk of (a):** not the introspector — it is
that the MySQL SQL-support helpers we inherit via `<extensionFallback DORIS→MYSQL>`
(`sqlObjectBuilder`, and anything else that assumes a `MysqlBase*` model) `checkcast` the model to
MySQL node types and will `ClassCastException` on a foreign model unless each is overridden or the
model stays `MysqlBase`-compatible.

---

## 1. What the bytecode proves

Facts, each verified against DB-261 jars (commands in appendix):

| # | Fact | Evidence |
|---|------|----------|
| F1 | The tree depth is **data-driven by which `Basic…Root` interface the model root implements**, not hardcoded per dbms | `MysqlBaseRoot extends …,BasicModMultiLevelSingleDatabaseRoot`; `PgRoot extends …,BasicModMultiLevelMultiDatabaseRoot`; `MsRoot extends …,BasicModMultiLevelMultiDatabaseRoot` |
| F2 | **MySQL collapses the database level** (this is the whole problem) | `MysqlBaseRoot` directly holds `getSchemas()`; `MysqlBaseSchema extends …,BasicModMultiLevelSchema`, parent is the root. No database level exists between root and schema. |
| F3 | **Postgres & SQL Server model the exact shape Doris needs** (root → database → schema → table) | `PgRoot.getDatabases() : …<PgDatabase>`; `PgDatabase.getSchemas() : …<PgSchema>`. `MsRoot extends BasicModMultiLevelMultiDatabaseRoot`; `MsDatabase extends …,BasicModMultiLevelMateDatabase`; `MsSchema extends …,BasicModMultiLevelSchema` |
| F4 | The `Single` vs `Multi` split is one clean interface pair | `BasicMultiLevelSingleDatabaseRoot extends BasicSingleDatabaseRoot,BasicMultiLevelDatabase,BasicMultiLevelRoot` vs `BasicMultiLevelMultiDatabaseRoot extends BasicMultiDatabaseRoot,BasicMultiLevelRoot` |
| F5 | The **introspector base mirrors the model split**; a database level is a genuine third type parameter | `BaseNativeIntrospector<MR extends BasicModRoot, D extends BasicModDatabase, S extends BasicModSchema>`; `BaseSingleDatabaseIntrospector<R,S> extends BaseNativeIntrospector<R,R,S>` (D=R, collapsed); `BaseMultiDatabaseIntrospector<MR,D,S> extends BaseNativeIntrospector<MR,D,S>` (distinct D) |
| F6 | **Model family and introspector are a hard-cast matched pair** | `MysqlBaseIntrospector<R extends MysqlBaseRoot, S extends MysqlBaseSchema> extends BaseSingleDatabaseIntrospector<R,S>`, and its bytecode `checkcast`s directly to `MysqlBaseRoot` (≥4 sites) and `MysqlBaseSchema`. A non-`MysqlBaseRoot` model → `ClassCastException`. `PgIntrospector extends …<PgRoot,PgDatabase,PgSchema>` is the multi-database analog. |
| F7 | **The inherited MySQL *editor* helpers also hard-cast the model** — this is the real minefield | `MysqlBaseObjectBuilder` `checkcast`s to `MysqlBaseTable`/`MysqlBaseTableColumn`. DORIS inherits `sqlObjectBuilder dbms="MYSQL"` by fallback. This is the general form of the historic `GenericImplModel`-cast crash. |
| F8 | **Qualified-name resolution is model-driven, not dialect-driven** | `SqlImplUtil.resolveQualified(SqlReferenceExpression)` / `processQualifier(...)` walk the qualifier chain, resolving each segment against the previous segment's `DasObject` children. Depth that resolves == depth the *model* exposes. MySQL and Postgres share this identical machinery; only their models differ. |
| F9 | **The wiring is per-dbms EPs with fallback**; each concern is independently overridable | `intellij.database.dialects.mysql.xml` registers `<introspector>`, `<modelFacade>`, `<jdbcHelper>`, `<sqlObjectBuilder>`, `<dialect>`, … all `dbms="MYSQL"`. DORIS registers only `dbms`, `modelFacade`, `definitionProvider`, `extensionFallback`, `errorHandler` — everything else resolves to MYSQL via `<extensionFallback DORIS→MYSQL>`. |
| F10 | **The JDBC helper is protocol/driver-bound, not model-bound** | `MysqlJdbcHelper extends MysqlBaseJdbcHelper` exposes only `create/bindClassLoader/parseVersion/detect/extractVersion` over `java.sql.Connection`/`Driver`. It has no model types in its surface. A two-level model needs **no** helper change. |
| F11 | **Parsing depth is already sufficient** (issue #2 confirmed) | Corpus `doris/18-three-part-catalog-names.sql` pins `SELECT … FROM hive_archive.acme_archive.events`, 3-part `INSERT`, and cross-catalog `JOIN` as regressions; `19`/`20` pin `SWITCH` and `USE catalog.db`. The MySQL-based DorisSQL dialect parses 3 parts today. |
| F12 | The `BasicMetaModel` is a public value object (`root:BasicMetaObject`, `apiClass`, `modelFactory`) but its `modelFactory` produces a fully-generated `BasicModModel` | `BasicMetaModel(Dbms, BasicMetaObject<?>, Class<M>, Function<ModelTextStorage,? extends M>)`; `MysqlMetaModel.MODEL` / `PgModelFacade` reference generated `…ImplModel` classes. Hand-authoring an ObjectKind graph + working model factory is the large unknown for a *bespoke* Doris model. |

The one thing bytecode can't hand us for free: a **multi-database model whose node types the
inherited MySQL editor helpers will accept**. Section 6 is about that gap; it is what gates (a).

## 2. The MySQL model has no catalog level (Q1)

```
MysqlBaseRoot   extends …, BasicModMultiLevelSingleDatabaseRoot   // root == the single database
   └─ getSchemas() : ModNamingFamily<? extends MysqlBaseSchema>
MysqlBaseSchema extends …, BasicModMultiLevelSchema               // parent == root; no db above it
   └─ getTables() / getViews() / getRoutines()
```

versus the shape Doris needs, already shipped for Postgres **and** SQL Server:

```
PgRoot / MsRoot  extends …, BasicModMultiLevelMultiDatabaseRoot
   └─ getDatabases() : …<PgDatabase / MsDatabase>       ← Doris CATALOG maps here
        └─ getSchemas() : …<PgSchema / MsSchema>        ← Doris DATABASE maps here
             └─ getTables()                             ← Doris TABLE
```

The hierarchy is **not** hardcoded per dbms. It is entirely determined by which `Basic…Root`
interface the model root implements (F1/F4). `Single` collapses the database level to the root;
`Multi` keeps it. `BasicMetaModel` (F12) then materializes that shape as an `ObjectKind` graph
(`getChildKinds`/`getParentKinds`/`getPathsToRoot`) which the whole tree/introspection/resolution
stack reads generically. **SQL Server is the load-bearing prior art**: it is a multi-database model
that switches databases over a single JDBC connection (`USE`/`setCatalog`) — mechanically identical
to Doris `SWITCH <catalog>`.

## 3. What our ModelFacade returns, and who consumes it (Q2)

`src/main/kotlin/dev/sort/doris/DorisModelFacade.kt` returns MySQL's single-database model verbatim:

```kotlin
override fun getMetaModel(): BasicMetaModel<*> = MysqlMetaModel.MODEL   // root = MysqlRoot (single-DB)
override fun getModelHelper(): ModelHelper    = MysqlBaseModelHelper()
```

**Consumer chain.** `ModelFacade` is a `DbmsExtension<ModelFacade>` (`ModelFacade.EP`,
`forDbms(Dbms)`). Its `getMetaModel()` supplies the `BasicMetaObject` root graph; `createModel(...)`
builds the live `BasicModModel` the introspector fills and the tree renders. The introspector is a
*separate* per-dbms EP (`<introspector dbms=…>`, F9) that receives a `ModelFactory` and casts the
model it produces (F6). So **model shape and introspector must agree** — they are wired
independently but fail together.

**Could it return a different family's model while the SQL dialect stays MySQL?** Yes for *parsing
and resolution* (F8: resolution reads the model, not the dialect; F11: the dialect already parses 3
parts). **No for free**, because of two coupling points:

1. `DorisSqlDialect.getDatabaseDialect()` currently must return `MYSQL`
   (`DatabaseDialects.findByDbms(Dbms.MYSQL)`). The metadata dbms this exposes drives the matched
   MySQL set (modelFacade + introspector + helpers). Point it elsewhere and `MysqlBaseIntrospector`
   casts a foreign root to `MysqlBaseRoot` → CCE (the documented reason the code pins it, now
   explained by the F6 `checkcast`).
2. Even keeping the introspector aside, the **inherited editor helpers** cast the model (F7).

Consistency is therefore enforced *by hard casts at runtime* (F6/F7), not by any declarative check.
That is the whole risk surface.

## 4. Introspector fit for a two-level model (Q3)

A two-level Doris model needs a `BaseMultiDatabaseIntrospector<DorisRoot, DorisDatabase,
DorisSchema>` (F5) — **not** `MysqlBaseIntrospector` (which is `SingleDatabase`, F6). It would:

- enumerate the database level with `SHOW CATALOGS` → one `BasicModDatabase` per catalog
  (`internal` + externals);
- for each catalog, `SWITCH <catalog>` (or qualified `SHOW DATABASES FROM <catalog>` /
  `<catalog>.information_schema`) → `BasicModSchema` per database;
- per schema, the usual `information_schema`/`SHOW` table+column queries.

All of these are ordinary statements over Connector/J (issue #2, confirmed). `BaseMultiDatabaseIntrospector`
already provides `checkDatabaseIsAccessibleInCurrentConnection` / `checkConnectionApplicable` —
the platform expects exactly this "switch into each database to introspect it" rhythm, which is how
SQL Server (F3) works. Registration: `<introspector dbms="DORIS"
implementationClass="dev.sort.doris.DorisIntrospector$Factory"/>` overriding the inherited MySQL
factory. Nothing about SQL-over-MySQL-protocol blocks this; the introspector just has to *ask*.

## 5. Resolution & the JDBC layer (Q4, Q5)

**Q4 — resolution is model-driven (F8).** `SqlImplUtil.resolveQualified` /
`processQualifier` resolve `a.b.c` by resolving `c` in the scope of `b` in the scope of `a`, each
step consulting the previous element's `DasObject` children. The *number of levels that resolve*
equals the number of levels the **model** exposes — the dialect only decides how many dotted parts
are *parsed* (three, already — F11). Consequences:

- With a **two-level (multi-database) model**, `catalog.db.table` resolves natively, no
  resolve-layer code — exactly how Postgres resolves `db.schema.table`. This is the clean win of (a).
- With the **current single-database model**, a 3-part reference has no catalog level to bind its
  first segment; it will not resolve. Flattening catalog.db into a single dotted schema name (option
  b) does **not** help: the reference is still three parsed segments and the resolver still walks
  three levels, so it will not match a schema whose *name* happens to contain a dot. Option (b)
  therefore needs a custom `SqlResolveExtension`/scope contribution — the same undocumented tax as
  (a), for a worse tree. (Reject.)

**Q5 — the JDBC helper does not care about model depth (F10).** `<jdbcHelper dbms>` binds to the
dbms and runs in the out-of-process JDBC layer; `MysqlJdbcHelper` only wraps the driver/connection.
DORIS inherits it by fallback and that is correct for Connector/J. A two-level model needs **no**
helper change; `SWITCH`/`setCatalog` go through the existing MySQL helper unchanged. (The "Forced
helper" log line is this fallback selection, not a model concern.)

## 6. Risks — every cast that breaks under a model-family switch (Q6)

The historic `GenericImplModel`-cast crash was not a fluke; it is the *general behavior* of a stack
built around `MysqlBase*` node types. Inventory of landmines found:

| Landmine | Class | Cast | Fires when | Mitigation for (a) |
|---|---|---|---|---|
| Introspector root/schema | `MysqlBaseIntrospector` | → `MysqlBaseRoot`, `MysqlBaseSchema` (F6) | every introspection | replace with `DorisIntrospector : BaseMultiDatabaseIntrospector` casting to *our* root |
| **Editor object builder** | `MysqlBaseObjectBuilder` (inherited via `sqlObjectBuilder dbms="MYSQL"`) | → `MysqlBaseTable`, `MysqlBaseTableColumn` (F7) | PSI→model in the SQL editor (DDL, virtual objects) | **must override `sqlObjectBuilder dbms="DORIS"`** or keep table nodes `MysqlBase`-compatible |
| Metadata dialect | `DorisSqlDialect.getDatabaseDialect()==MYSQL` | drives matched MySQL set | always | keep MYSQL for parsing/scripting; ensure the introspector/model no longer assume it implies a single-DB model |
| Model factory | `BasicMetaModel.modelFactory` (F12) | produces a generated `…ImplModel` | model creation | biggest unknown — need a multi-database model whose nodes the surviving MySQL helpers accept |

**The biggest risk is F7, not F6.** Swapping the introspector (F6) is mechanical and self-contained.
But the SQL *editor* stack (object builder, and by extension anything else inherited from MYSQL that
narrows the model to `MysqlBase*`) will `ClassCastException` on a foreign model during ordinary
editing. Making (a) safe means either (i) auditing and overriding **every** inherited helper that
casts to a `MysqlBase*` model type, or (ii) building the two-level model so its node types still
implement the `MysqlBase*` interfaces the helpers demand — a model that is simultaneously
multi-database *and* MySQL-node-typed. Whether (ii) is achievable is precisely Gate 0.

Secondary risks: script generation / DDL export inherited from `MysqlBaseScriptGenerator` (no model
cast found in its top frame — lower risk, verify at Gate 1); platform drift (the `Basic*` interfaces
and `SqlImplUtil` resolution are the most stable trio in the plugin, so low); maintenance of a
bespoke model against each DataGrip generation.

## 7. Recommended sequencing & gates

```
NOW ──► (c) Blessed per-catalog pattern — SHIP THIS
  │   • Doc: one data source per catalog, Options → Startup script `SWITCH <catalog>;`
  │   • Helper action "Add data sources for all Doris catalogs" (runs SHOW CATALOGS on the
  │     current connection, clones the data source per catalog, sets the startup script).
  │   • Zero platform risk; no inherited cast is touched. 2–4 days incl. docs.
  │
  ├─ Gate 0 (1-week spike, NO ship): the inherited-cast probe — the go/no-go for (a).
  │   Stand up a throwaway two-level model + a DorisIntrospector : BaseMultiDatabaseIntrospector
  │   producing catalog→db→table from SHOW CATALOGS. Register introspector+modelFacade for DORIS.
  │   GO if: tree renders catalog nodes AND 3-part `catalog.db.table` resolves in a console AND
  │          NO ClassCastException from MysqlBaseObjectBuilder or any inherited helper during
  │          browse + a trivial DDL edit.
  │   NO-GO if: any inherited MySQL helper CCEs on the foreign model and the override surface is
  │          open-ended → stay on (c), file the YouTrack ask (below), revisit next platform bump.
  │
  ├─ Gate 1 (2–3 weeks): productionize the model + introspector.
  │   • Decide model source: (ii) a Doris model whose nodes implement the MysqlBase* interfaces
  │     the surviving helpers demand (preferred — minimizes overrides), else a neutral
  │     multi-database model + override every casting helper found in an exhaustive
  │     `checkcast …/model/Mysql` audit across the inherited EP set.
  │   • Per-catalog introspection queries hardened (SWITCH vs qualified information_schema),
  │     `internal` catalog parity with today's behavior.
  │   GO if: introspection of a real multi-catalog Doris is correct AND the golden editor
  │          behaviors (completion, resolve, DDL) survive.
  │
  └─ Gate 2 (1–2 weeks): polish — icons/labels for catalog nodes, cross-catalog join resolution
      sanity, scripting/DDL export, drift test against the recorded model.
```

**What would change the calculus overnight:** a JetBrains YouTrack ask for either (a) a public
"custom multi-level model" recipe for a MySQL-protocol dbms, or (b) an introspection hook that adds
a database level without a bespoke model. File it before Gate 1; the answer may collapse (a)'s cost
or confirm (c) as the ceiling. (Same posture as the parser doc's issue #1/#2 asks.)

## 8. Why not (b)

(b) — a `DorisIntrospector : MysqlBaseIntrospector` that keeps the single-database MySQL model but
adds `SHOW CATALOGS` + per-catalog `SHOW DATABASES`, materializing each external database as a
schema named `catalog.db` — is attractive because it keeps the F6/F7 casts satisfied (still a
`MysqlBase` model). But it dead-ends on resolution (F8, §5): a schema *named* `hive_archive.acme_archive`
is one identifier, while `hive_archive.acme_archive.events` is three parsed segments the resolver
walks as three levels. Making 3-part references resolve then requires a custom scope/resolve
contribution — the very undocumented machinery (a) needs — while delivering a flat, dotted,
non-hierarchical tree. It buys the hard part of (a)'s cost for a worse result. Rejected.

---

## Appendix — evidence log

All against `/Users/jminard/Applications/DataGrip.app/Contents` (DB-261), `javap` from
`~/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/…/bin/javap`. Classpath = every jar under
`plugins/DatabaseTools/lib` + `lib`.

```
DG=/Users/jminard/Applications/DataGrip.app/Contents
CP=$(find $DG/plugins/DatabaseTools/lib -name '*.jar' | tr '\n' ':')$(find $DG/lib -name '*.jar' | tr '\n' ':')
JAVAP=~/Library/Java/JavaVirtualMachines/jbr-17.0.8.1/Contents/Home/bin/javap

# F1/F2 — MySQL is single-database (root -> schema, no db level)
$JAVAP -cp "$CP" com.intellij.database.dialects.mysqlbase.model.MysqlBaseRoot   | head
#   extends …, BasicModMultiLevelSingleDatabaseRoot ; getSchemas() : …<MysqlBaseSchema>
$JAVAP -cp "$CP" com.intellij.database.dialects.mysqlbase.model.MysqlBaseSchema | head
#   extends …, BasicModMultiLevelSchema ; parent = MysqlBaseRoot

# F3 — Postgres AND SQL Server model root -> database -> schema (the shape Doris needs)
$JAVAP -cp "$CP" com.intellij.database.dialects.postgres.model.PgRoot     | head   # BasicModMultiLevelMultiDatabaseRoot; getDatabases()
$JAVAP -cp "$CP" com.intellij.database.dialects.postgres.model.PgDatabase | head   # getSchemas() : …<PgSchema>
$JAVAP -cp "$CP" com.intellij.database.dialects.mssql.model.MsRoot        | head   # BasicModMultiLevelMultiDatabaseRoot
$JAVAP -cp "$CP" com.intellij.database.dialects.mssql.model.MsDatabase    | head   # BasicModMultiLevelMateDatabase

# F4 — the Single/Multi split is one interface pair
$JAVAP -cp "$CP" com.intellij.database.model.basic.BasicMultiLevelSingleDatabaseRoot
$JAVAP -cp "$CP" com.intellij.database.model.basic.BasicMultiLevelMultiDatabaseRoot

# F5 — introspector base mirrors the split (3rd type param = the database level)
$JAVAP -cp "$CP" com.intellij.database.dialects.base.introspector.BaseNativeIntrospector      | head -3
$JAVAP -cp "$CP" com.intellij.database.dialects.base.introspector.BaseSingleDatabaseIntrospector | head -3  # <R,S> -> <R,R,S>
$JAVAP -cp "$CP" com.intellij.database.dialects.base.introspector.BaseMultiDatabaseIntrospector  | head -3  # <MR,D,S>

# F6 — introspector hard-casts the model root/schema (matched pair)
$JAVAP -cp "$CP" com.intellij.database.dialects.mysqlbase.introspector.MysqlBaseIntrospector | head -3
$JAVAP -cp "$CP" -p -c com.intellij.database.dialects.mysqlbase.introspector.MysqlBaseIntrospector \
  | grep checkcast | grep -E 'MysqlBaseRoot|MysqlBaseSchema'
$JAVAP -cp "$CP" com.intellij.database.dialects.postgres.introspector.PgIntrospector | head -2  # <PgRoot,PgDatabase,PgSchema>

# F7 — inherited EDITOR helper casts model nodes to MysqlBase types (the real minefield)
$JAVAP -cp "$CP" -p -c com.intellij.database.dialects.mysqlbase.MysqlBaseObjectBuilder \
  | grep checkcast | grep -E 'MysqlBaseTable|MysqlBaseTableColumn'

# F8 — resolution is model-driven (walks qualifier chain over DasObject children)
$JAVAP -cp "$CP" -p com.intellij.sql.psi.impl.SqlImplUtil | grep -E 'resolveQualified|processQualifier'

# F9 — per-dbms EP wiring + DORIS fallback set
unzip -p $DG/plugins/DatabaseTools/lib/modules/intellij.database.dialects.mysql.jar \
  intellij.database.dialects.mysql.xml | grep -E 'introspector|modelFacade|jdbcHelper|sqlObjectBuilder|dialect '
#   <introspector dbms="MYSQL" …MysqlBaseIntrospector$Factory/>  <modelFacade …MysqlModelFacade/>
#   <jdbcHelper …MysqlJdbcHelper/>  <sqlObjectBuilder …MysqlBaseObjectBuilder/>
# our src/main/resources/META-INF/plugin.xml: DORIS overrides only dbms, modelFacade,
#   definitionProvider, extensionFallback, errorHandler → rest inherited MYSQL via extensionFallback

# F10 — jdbc helper is driver/protocol-bound, no model types
$JAVAP -cp "$CP" com.intellij.database.remote.jdbc.helpers.MysqlJdbcHelper   # create/bindClassLoader/parseVersion/detect

# F11 — parsing depth already OK (pinned regressions)
sed -n 1,3p src/test/resources/corpus/doris/18-three-part-catalog-names.sql
sed -n 1,2p src/test/resources/corpus/doris/19-switch-catalog.sql
sed -n 1,2p src/test/resources/corpus/doris/20-use-catalog-dot-db.sql

# F12 — BasicMetaModel is a value object; model factory produces generated ImplModel
$JAVAP -cp "$CP" com.intellij.database.model.meta.BasicMetaModel | head -12
$JAVAP -cp "$CP" com.intellij.database.dialects.mysql.model.MysqlMetaModel
```

Cross-references: the `getDatabaseDialect()==MYSQL` pin and the `MysqlBaseIntrospector` cast are
recorded in `DorisSqlDialect.kt` (lines 51–53) and the `project_version_gating_deadend` memory; the
protocol facts are in issue #2's analysis (`gh issue view 2 --repo sort-dev/doris-intellij-plugin
--comments`). Nothing here contradicts issue #2 — it *deepens* it: the block is not merely "the
introspector never asks," it is that asking requires the introspector's *matched two-level model*,
and switching model families detonates the inherited MySQL editor casts (F7). That coupling is the
finding issue #2 did not have.

---

## Gate 0 results

*Executed 2026-07-07 on branch `freezeth-catalogs`. Bytecode audit against DB-261 jars (slim
classpath = `plugins/DatabaseTools/lib/**/*.jar`), `javap` from JBR 17. Compile-proof built and run
against the remote SDK. New code: `src/main/kotlin/dev/sort/doris/model/DorisMetaModel.kt` +
`src/test/kotlin/dev/sort/doris/model/DorisMetaModelTest.kt`. Nothing registered in `plugin.xml`.*

### VERDICT: **GO** for path (a) — with the reuse strategy, not a bespoke model.

**The one decisive fact:** the inherited-MySQL cast minefield (F7) that gated the whole path is
**bounded and fully overridable, not open-ended**. A complete audit of every EP DORIS inherits from
MYSQL by fallback found **exactly 5** implementation classes that hard-cast model nodes to
`MysqlBase*` types, and **all 5 are `dbms`-keyed `DbmsExtension` EPs** we can override with
`dbms="DORIS"` (proven by shipped precedent: `<introspector dbms="VITESS">`, `<sqlObjectBuilder
dbms="MEMSQL">`, and the TIDB/OCEANBASE/VITESS dbms-only `+ extensionFallback` pattern that mirrors
DORIS exactly). The Gate-0 NO-GO trigger was "the override surface is open-ended" — it is not; it is
a closed set of five. Combined with the compile-proof that a multi-database meta-model is
constructible from public API, path (a) clears the gate.

### How `extensionFallback` actually works (mechanism, so we know what it covers)

`<extensionFallback dbms="DORIS" fallbackDbms="MYSQL"/>` is **not** per-EP. `DbmsExtension<T>`
(the base class of *every* `com.intellij.database` dbms-keyed EP: `ModelFacade.EP`,
`introspector`, `sqlObjectBuilder`, …) carries a single static `FALLBACK`
`KeyedExtensionCollector<Dbms,Dbms>`; `extensionFallback` registers one `DORIS→MYSQL` edge into it,
and every `DbmsExtension.forDbms(DORIS)` that finds no DORIS registration calls
`copyFromFallback(DORIS)` → resolves the MYSQL implementation. So DORIS inherits **all** MYSQL
dbms-keyed EPs at once, and each is **independently** overridable simply by registering a
`dbms="DORIS"` bean for that EP (which pre-empts the fallback copy). Today DORIS overrides only
`modelFacade`, `definitionProvider`, `errorHandler`; everything else is MYSQL by fallback.

### Cast audit — every inherited EP, does it cast model nodes to `Mysql*`, is it overridable

21 dbms-keyed EPs inherited by DORIS from MYSQL. Casting consumers = **5**; overridable = **5/5**.
(`javap -p -c … | grep checkcast`, class + all inner classes, per class to avoid javap batch
truncation.)

| EP | inherited impl | casts model → `Mysql*`? | override for DORIS? |
|---|---|---|---|
| `introspector` | `MysqlBaseIntrospector` | **YES** — `MysqlBaseSchema`×25, `MysqlBaseTable`×17, `…TableColumn`×13, `…Routine`×11, `MysqlBaseRoot`×5, +14 more node types | **YES** — `<introspector dbms="DORIS">` (VITESS precedent). *Replaced wholesale anyway*: a single-database introspector cannot enumerate catalogs; path (a) supplies `DorisIntrospector : BaseMultiDatabaseIntrospector`, so these casts never execute. |
| `sqlObjectBuilder` | `MysqlBaseObjectBuilder` | **YES — the real F7 minefield** — 23 casts: `MysqlBaseRoutine`×9, `…View`×5, `…Event`×5, `…TableColumn`×4, `…Table`×4, `…Trigger`×3, `…Index`×3 | **YES** — `<sqlObjectBuilder dbms="DORIS">` (MEMSQL precedent). Must map DorisSQL editor PSI → the reused family's node types (Gate 1 work). |
| `scriptGenerator` | `MysqlBaseScriptGenerator` | **YES** — `MysqlBaseUser`×1 | **YES** — `<scriptGenerator dbms="DORIS">`. |
| `hookUpHelper` | `MysqlBaseHookUpHelper` | **YES** — `MysqlBaseLikeColumn`×1 | **YES** — `<hookUpHelper dbms="DORIS">`. |
| `predicatesHelper` | `MysqlBasePredicatesHelper` | **YES** — `MysqlBaseLikeTable`×1 | **YES** — `<predicatesHelper dbms="DORIS">`. |
| `dialect`, `jdbcSourceLoader`, `jdbcMetadataWrapper`, `jdbcHelper`, `namingService`, `sqlEffectAnalyzer`, `dataImporter`, `executionEnvironmentHelper`, `geoHelper`, `dmlHelper`, `typeSystem`, `explainPlanProvider`, `domainRegistry`, `optionProvider`, `introspectorStatsProvider`, `routineExecutionHelper` (16 EPs) | (various `MysqlBase*`/`Generic*`) | **NO model-node casts** — safe to keep inheriting from MYSQL | n/a (`introspectorStatsProvider` casts only to the introspector *class*, not a model node) |

Net override surface for path (a): **`sqlObjectBuilder` + `scriptGenerator` + `hookUpHelper` +
`predicatesHelper`** (4 editor helpers) **plus the `introspector`** (which we replace regardless).
F6 is confirmed (introspector casts `MysqlBaseRoot`/`MysqlBaseSchema`) but moot since it is replaced.
F7 is confirmed and **bounded to 4 helpers**.

### Model-construction findings (Q: reusable impls? sealed? bespoke feasible?)

- **The live model is a monolithic, code-generated, sealed graph — a bespoke hand-authored Doris
  model is NOT feasible.** `MysqlImplModel` is **package-private, `final`**, extends `BaseModel`,
  and holds a deep nest of **package-private generated** node impls (`MysqlImplModel$Root/$Schema/
  $Table/$TableColumn/$View`, plus `$LightRoot$LightSchema$LightTable$…` meta descriptors — 36
  inner classes). Its `META : BasicMetaModel` is assembled in a `static{}` block wiring
  `BasicMetaObject` references through the internal `MysqlGeneratedModelUtil.bind(...)`. There is
  **no public generator, no public/instantiable node constructor**, and the generated nodes cannot
  be reparented under a new database level. Hand-writing a node impl means reimplementing the
  generated property-storage boilerplate over dozens of `BasicMod*` supertypes per kind — the exact
  "moat = the generator + the DSL source, not access control" parallel to the parser doc.
- **But the model *shape* is definable from public API, and an existing multi-database family is
  reusable wholesale.** `BasicMetaObject` and `BasicMetaModel` have **public constructors**;
  `BasicMetaModel.getChildKinds` reads only the `BasicMetaObject.children` graph (the ctor merely
  DFS-indexes `kind → metaObjects`; it never invokes the node/model factories). And
  `PgMetaModel.MODEL` / `MsMetaModel.MODEL` are **public** multi-database meta-models that already
  expose ROOT → DATABASE → SCHEMA → TABLE. **Productionization = reuse one of them** as
  `DorisModelFacade.getMetaModel()`, then override the 5 casting EPs above (so they cast to that
  family's node types / reuse that family's helpers) while keeping the DorisSQL (MySQL-based)
  parsing dialect. The disjunction in Gate 0's charter resolves cleanly: option (i) "nodes still
  satisfy the `MysqlBase` casts" is **impossible** (a `MysqlBaseRoot` is by its own supertype
  `BasicModMultiLevelSingleDatabaseRoot` single-database — it cannot also be multi-database); option
  (ii) "all casting consumers overridable for DORIS" is **true** (5/5).

### Compile-proof status: **PASS**

`DorisMetaModel.buildMultiDatabaseSkeleton(dbms)` builds ROOT → DATABASE(catalog) → SCHEMA(doris db)
→ TABLE → COLUMN via the public `BasicMetaObject`/`BasicMetaModel` constructors (node factories are
deliberate stubs — the kind-walk never calls them, and the finding above is *why* they cannot be
real). `DorisMetaModelTest` (2 tests, green) instantiates it and asserts the walkable kind chain,
and — as a regression guard — asserts `MysqlMetaModel.MODEL` has **no** `DATABASE` child under
`ROOT` (single-database, the root cause). This is the in-memory proof that a third-party plugin can
stand up the two-level shape. Full suite: **50 tests, 0 failures** with the new code present.

### Revised effort estimate for path (a)

Unchanged in magnitude but **de-risked**: **4–6 weeks** (was 4–8). Gate 0 removed the largest
unknown (bespoke model authoring, which is *off the table* — reuse Pg/Ms instead) and bounded the
cast surface to 4 overridable editor helpers + 1 replaced introspector. Remaining Gate-1 work:
(1) pick Pg vs Ms as the reused family and wire `modelFacade`/`introspector` for DORIS;
(2) `DorisIntrospector : BaseMultiDatabaseIntrospector` populating that family's nodes from `SHOW
CATALOGS` / per-catalog `SHOW DATABASES`; (3) the 4 editor-helper overrides mapping DorisSQL PSI to
the reused node types; (4) `internal`-catalog parity. Risk now concentrated in (2)/(3)'s
fit-and-finish, not in platform possibility.

### Should fallback (c) be promoted?

**No change to the phasing.** (c) remains the *ship-now* pattern (2–4 days, zero platform risk).
Gate 0 does not make (a) shippable overnight — it makes it a **funded, de-risked build** rather than
a spike that might hit an unoverridable wall. Ship (c); build (a) behind the gate with the concrete
class plan above.

---

## Gate 1 log

*Executed 2026-07-07 on branch `freezeth-catalogs` (worktree `wt-catalogs`). Implements Milestone 1:
a sandbox-testable build where, behind an opt-in flag, a Doris data source's tree shows catalogs as
the DATABASE level. Bytecode audited against DB-261 jars with `javap` (JBR 17); all new code compiles
against the remote DataGrip 2026.1.3 SDK. Full suite green (59 tests), `buildPlugin` green.*

### 1. Model family chosen: **SQL Server (`Ms*`)** — reused wholesale

Gate 0 established a bespoke model is infeasible and the productionization path is to reuse an
existing *public* multi-database meta-model. Gate 1 picks **SQL Server's `MsMetaModel.MODEL`** over
Postgres. Evidence:

| Criterion | SQL Server (`Ms*`) — CHOSEN | Postgres (`Pg*`) |
|---|---|---|
| **Introspection rhythm (decisive)** | `MsIntrospector extends BaseMultiDatabaseIntrospector` **directly**; introspects every database over **one JDBC connection by switching into it** (`USE`) — mechanically identical to Doris `SWITCH <catalog>` over the MySQL protocol. | `PgIntrospector extends PgGPlumBaseIntrospector` (an extra Greenplum layer) and uses a **separate connection per database** (Postgres cannot switch DB on a live connection). Does **not** match Doris's single MySQL-protocol connection. |
| Base-class ergonomics | Cleanest possible `BaseMultiDatabaseIntrospector<MsRoot,MsDatabase,MsSchema>` subclass; abstract surface is 3 methods (`createDatabaseLister`, `createDatabaseRetriever`, `createSchemaRetriever`). | Extra intermediate base (`PgGPlumBase*`) adds Greenplum concerns irrelevant to Doris. |
| Node-set baggage | `MsRoot→MsDatabase→MsSchema→MsTable→MsColumn` maps 1:1 to catalog→database→table→column. Extra kinds (synonyms, sequences, CLR types) are simply never populated. | Comparable shape but more irrelevant kinds (extensions, languages, operators, collations, FDW). |
| Editor-helper reuse | `MsObjectBuilder` (public, no-arg), `MsScriptGenerator(Dbms)`, `MsPredicatesHelper(Dbms)` all **public + instantiable** (compile-verified on the plugin classpath). | Pg equivalents exist but are not needed once Ms is chosen. |
| Compile-classpath availability | `MsMetaModel`, `MsModelHelper`, `MsObjectBuilder`, `MsScriptGenerator`, `MsPredicatesHelper`, `BaseMultiDatabaseIntrospector`, `Layouts` all **resolve at compile time** (probed with a throwaway `compileKotlin` — Gate 0 had used a String ref precisely to avoid depending on this; it is now confirmed present). | Also present, but moot. |

**Two-sentence rationale:** SQL Server is the only shipped multi-database family whose introspection
rhythm — one connection, switch into each database to read it — is mechanically what Doris
`SWITCH <catalog>` does over the MySQL protocol; Postgres uses a connection-per-database model that
does not fit. It is also the direct `BaseMultiDatabaseIntrospector` subclass (Postgres adds a
Greenplum layer), and its editor-helper impls are public and instantiable for the DORIS overrides.

### 2. Opt-in flag

- **System property `doris.catalogs.experimental`** (constant in `dev.sort.doris.DorisCatalogs`),
  read **once per JVM session** into `DorisCatalogs.enabled` (a `val`). Default **false** =
  bit-for-bit today's behaviour. Set `-Ddoris.catalogs.experimental=true` to opt in.
- Read-once is deliberate: `plugin.xml` extension beans are static, so every `dbms="DORIS"` bean is
  instantiated regardless of the flag. A single session-constant value guarantees every extension
  makes the **same** routing decision, with no per-call cost and no way for the two modes to
  interleave within a session.
- All experimental-path log lines are prefixed `DorisCatalogs:` for `grep`-ability in `idea.log`.

### 3. Wiring + dual-mode EP overrides (the hard requirement)

Because EP registrations are static XML, a `dbms="DORIS"` bean is live flag-off too. Each of the five
overrides is therefore **dual-mode**: it reproduces today's MySQL behaviour flag-off and is `Ms*`-safe
flag-on. Registered in `plugin.xml` (`com.intellij.database` ns):

| EP | DORIS impl | flag OFF delegate (= today) | flag ON delegate |
|---|---|---|---|
| `modelFacade` | `DorisModelFacade` | `MysqlMetaModel.MODEL` + `MysqlBaseModelHelper` | `MsMetaModel.MODEL` + `MsModelHelper` |
| `introspector` | `DorisIntrospector$DorisIntrospectorFactory` | pass-through to `MysqlBaseIntrospector.Factory` (Kotlin `by` delegation) → stock `MysqlBaseIntrospector` | constructs `DorisIntrospector`; `supportsMultilevelIntrospection=true` |
| `sqlObjectBuilder` | `DorisObjectBuilder` | `MysqlBaseObjectBuilder` | `MsObjectBuilder` |
| `scriptGenerator` | `DorisScriptGenerator(dbms)` | `MysqlBaseScriptGenerator(dbms)` | `MsScriptGenerator(dbms)` |
| `predicatesHelper` | `DorisPredicatesHelper(dbms)` | `MysqlBasePredicatesHelper(dbms)` | `MsPredicatesHelper(dbms)` |
| `hookUpHelper` | `DorisHookUpHelper(dbms)` | `MysqlBaseHookUpHelper(dbms)` | **also** `MysqlBaseHookUpHelper(dbms)` (see below) |

**Design mechanics.**
- The four editor helpers use **Kotlin interface delegation whose delegate is chosen once from
  `DorisCatalogs.enabled`** (`class DorisObjectBuilder : SqlObjectBuilder by (if (enabled) MsObjectBuilder() else MysqlBaseObjectBuilder())`).
  Flag-off this is a *pure* `MysqlBase*` instance — byte-for-byte what `extensionFallback DORIS→MYSQL`
  produces. The injected `Dbms` we forward is the same `DORIS` value the fallback passes: verified
  that `DbmsExtension.copyFromFallback(DORIS)` calls `doGetInstance(class, aload_1)` where `aload_1`
  is the **original** dbms, not the fallback's `MYSQL`.
- The introspector factory uses the same delegation for its capability methods and only overrides
  `createIntrospector` / `supportsMultilevelIntrospection` / `isIncremental` to branch on the flag.
- **`hookUpHelper` is intentionally MySQL in both modes.** SQL Server ships no `MsHookUpHelper`, and
  `MysqlBaseHookUpHelper`'s only model cast (`getAttributes`) is **guarded** by
  `instanceof MysqlBaseLikeColumn` (verified in bytecode) — so it does *not* `ClassCastException` on
  an `Ms*` column, it just returns default attributes. Keeping it also preserves the MySQL-flavoured
  filter/sort language, which is correct for Doris's MySQL wire protocol. (This is the one Gate-0
  "F7" helper that turned out to be already `Ms*`-safe.)

**Why this is safe flag-off:** `DorisCatalogWiringTest` asserts, with the flag at its default off,
that `DorisModelFacade` returns the *same* `MysqlMetaModel.MODEL` instance and a `MysqlBaseModelHelper`,
and that the introspector factory's advertised capabilities equal the stock MySQL factory's.

### 4. Introspector implementation status

> **Design revision: stateless-first (review feedback).** The first cut of M1 used
> `SWITCH <catalog>` before every per-catalog read. Review (user's catch) pointed out that `SWITCH`
> mutates connection **session state** (the current catalog) — on pooled/shared/keep-alive
> connections that is a heisenbug source, and it forces per-catalog phase ordering. The introspector
> was refactored to **stateless catalog-qualified queries** as the primary path
> (`SHOW DATABASES FROM <c>`, `<c>.information_schema.*`): no session mutation, half the round
> trips, and per-catalog failures isolate naturally. `SWITCH` + unqualified queries survive only as
> a per-catalog **fallback** for older Doris versions where the qualified forms fail
> (`DorisIntrospector.runCatalogScopedOrFallback`). Coherence note for §1's "Ms rhythm" rationale:
> what we reuse from SQL Server is the **seam structure** (database lister / database retriever /
> schema retriever) and the public multi-database model; the Ms family's literal "switch into each
> database" rhythm now applies only to the fallback path — in the primary path the switch step is a
> no-op because each query carries its own catalog qualification.

`dev.sort.doris.catalog.DorisIntrospector : BaseMultiDatabaseIntrospector<MsRoot,MsDatabase,MsSchema>`
implements the full M1 object surface (catalogs, databases, tables, views, columns; routines/keys/
triggers deliberately skipped) via the framework's own three seams, mirroring `MsIntrospector`:

- **DATABASE level (`createDatabaseLister`)** — `SHOW CATALOGS` → one `MsDatabase` per catalog via the
  base `renew(family, CatalogId, CatalogName)`. This is the headline feature and uses the cleanest,
  best-understood seam.
- **SCHEMA level (`createDatabaseRetriever().retrieveSchemas`)** — per catalog:
  `SHOW DATABASES FROM <catalog>` (stateless) → `database.schemas.createOrGet(name)`.
  *Fallback on failure:* `SWITCH <catalog>` + `SHOW DATABASES`.
- **TABLE/VIEW/COLUMN level (`createSchemaRetriever().process`)** — per database (stateless):
  `SELECT ... FROM <catalog>.information_schema.tables WHERE TABLE_SCHEMA = ?` and
  `... <catalog>.information_schema.columns WHERE TABLE_SCHEMA = ?` →
  `schema.tables|views.createOrGet(...)` and `table.columns.createOrGet(...)`.
  *Fallback on failure:* `SWITCH <catalog>` + the unqualified `information_schema` forms.

Catalog names are backtick-quoted identifiers embedded in the query text (identifiers cannot be JDBC
`?` parameters); schema names remain `?`-bound. The fallback triggers **per catalog and per query
family**: any throw from a qualified query logs a `DorisCatalogs:` warning ("falling back to SWITCH +
unqualified (older Doris?)") and retries that read via `SWITCH`; a failure of the fallback itself
propagates to the per-catalog/per-schema catch, which logs and skips just that catalog/schema.

Queries run through the platform layouted-query facade (`DBTransaction.query(SqlQuery).run()` /
`.command(sql).run()`); `SqlQuery` + `Layouts` are public and construct from plugin code (the finding
that makes the introspector implementable at all). Column **data types are not set** in M1
(`BasicModTypedElement.setStoredType(DasType)` needs a type-system-built `DasType`; deferred to
Gate 2 — columns render by name). Every per-catalog and per-database step is wrapped so a broken/slow
external catalog is logged (`DorisCatalogs:`) and skipped, never aborting the whole introspection.

**Verified offline:** compiles + registers; primary (qualified) and fallback query text and
row-struct field names (`DorisCatalogQueriesTest`); flag-off equivalence and the chosen model's
catalog level (`DorisCatalogWiringTest`); `buildPlugin` green. **NOT verifiable without a live
Doris:** that `SHOW CATALOGS` column labels actually bind to the structs, that the target Doris
version supports the qualified forms (pre-flight probes below make this a 30-second console check),
and that the framework's per-schema *level bookkeeping* accepts eager `createOrGet` population
without wiping nodes on expand. These are the runtime test's job (below). Per the honesty contract:
the mechanism is proven present and the code is a coherent full-surface implementation, but its live
correctness is unproven from this environment.

### 5. Runtime test script (user-facing)

Prereq: a Doris FE reachable over the MySQL protocol (default port 9030), ideally with the built-in
`internal` catalog **plus** at least one external catalog (e.g. a `hive` catalog) so the multi-catalog
tree is exercised.

**Pre-flight (30 seconds, any Doris console — BEFORE the sandbox test).** Confirm the target Doris
version supports the stateless qualified forms the primary path uses (substitute your external
catalog's name):

```sql
SHOW DATABASES FROM <external_catalog>;
SELECT count(*) FROM <external_catalog>.information_schema.tables;
```

Both succeed → the primary path will be used throughout. Either fails → the introspector will log a
`DorisCatalogs: ... falling back to SWITCH + unqualified (older Doris?)` warning per catalog/query
and use the SWITCH fallback; the tree should still populate, just with the extra round trips.

**A. Launch the sandbox with the flag ON**

```
cd wt-catalogs
./gradlew runIdeCatalogs        # == runIde + -Ddoris.catalogs.experimental=true
```
(Plain `./gradlew runIde` launches flag-OFF and must behave exactly as the shipped plugin — run it
first as the control.) For an **installed** plugin instead of the sandbox, add the VM option via
Help → Edit Custom VM Options: `-Ddoris.catalogs.experimental=true`, then restart.

**B. Create/refresh the Doris data source, then read the tree**

1. Add a **FRESH** Apache Doris data source (host/port/user/password), test the connection. (M2's
   default-scope fix only applies to a data source whose introspection scope was never touched —
   delete/recreate the data source rather than reusing one where you already picked schemas. An
   existing explicit selection is deliberately never clobbered.)
2. Right-click → Refresh (or expand the data source root).
3. **Expected flag-ON tree — with ZERO manual scope selection (M2):**
   `<data source>` → all **catalog** nodes at the top level (`internal`, `hive_archive`, …; the
   level is labelled **"catalogs"**, not "databases" — M2 terminology) → `internal` is
   **deep-introspected by default**: expand it → its databases → tables/views → columns, no clicks
   in the schemas pane needed. External catalogs are **enumerated but not deep-introspected**:
   the catalog node is visible, `internal`'s content is there, but external contents load only
   after you opt in (schemas pane → tick databases under that catalog) — deliberate, they can
   front huge hive metastores. `internal` should carry the "current" highlight (bold).
   Log marker: `DorisCatalogs: supplying default introspection scope: internal deep, external
   catalogs enumerated` — its absence on a fresh data source means the scope was not empty (not
   actually fresh) or the default-scope hook never ran.
4. **Expected flag-OFF control tree (plain `runIde`) — issue #5 check:** single-level as shipped
   (schemas directly under the data source, no catalog level, externals invisible), but a FRESH
   data source now defaults to **the connection's current database** selected and populated
   (parity with a genuine MySQL data source) instead of nothing. If the connection reports no
   current database (no database in the URL), behaviour is unchanged from shipped (nothing
   selected) — set a database in the URL/database field to see the default.
   Log marker: `DorisCatalogs: supplying flag-off default introspection scope (current database:
   <name-or-null>)`.
5. **SQL editor, flag-ON (M3) — 3-part references:** open a console on the data source and type
   `SELECT * FROM <extcat>.<db>.<table>`. Expected: catalog names complete at qualifier position 1,
   that catalog's databases at position 2, tables at position 3, and the full reference resolves
   (no red on any segment; Cmd+B on each segment navigates). 2-part references into the current
   catalog must keep working. Precondition: the external catalog's contents must be introspected
   (opt into it in the schemas pane first — M2's default leaves externals enumerated-only, and a
   catalog with no introspected children can neither complete nor resolve its contents).
   On failure collect: whether the FIRST segment alone resolves (caret on the catalog name, Cmd+B);
   whether toggling "Suggest all objects" in SQL completion settings changes anything (it bypasses
   the import gate M3 fixed — a difference implicates `DorisSqlDialect.getBaseImports`); and the
   schemas-pane state of the external catalog.
6. **Console namespace switcher, flag-ON (M4):** the console's top-right namespace dropdown should
   now be a **two-level stepped popup** — pick a catalog, then one of its databases (no more flat
   cross-catalog schema list). Picking `extcat` → `somedb` must run ``use `extcat`.`somedb` ``
   (per-part backtick-quoted — visible in the console output) and succeed; picking under
   `internal` likewise. Log marker: `DorisCatalogs: console switch to '<catalog.db>' -> use ...`.
   **Quoting fallback:** if Doris rejects the per-part-quoted form (older versions), the composer
   is the single function `DorisCatalogQueries.sqlSwitchSearchPath` — swap its SCHEMA branch to
   the unquoted user-proven form (`"use " + catalog + "." + db`), or to two statements
   (``SWITCH `catalog` `` then ``use `db` ``), rebuild, retest. Report which form worked.
   Flag-OFF control: the switcher must look and behave exactly as shipped (flat list, MySQL `use`).
7. **Portion introspection + column types, flag-ON (M5):** force a subset introspection — tick a
   few schemas in the schemas pane (or let the console switcher trigger it). Expected: **no
   SEVERE** ("doesn't support the requested retriever" is fixed); only the selected schemas load;
   log marker `DorisCatalogs: portion introspection: N schema(s) of catalog '<c>' ...` followed by
   the usual per-database `-> N tables, M views` lines. Untouched schemas/catalogs must keep their
   previous state (nothing reset). Columns should now show **real types** in the tree and
   completion — `varchar(65533)`, `decimal(27,9)`, and Doris exotics displayed by name (`variant`,
   `bitmap`, `hll`, `array<int>`, ...) instead of blank/unknown; columns ordered by ordinal
   position. In an external (hive) catalog, engine-specific type strings must display as-is, not
   error. On failure grep `DorisCatalogs:` (portion marker present? per-schema skip warnings?) and
   report a sample table with wrong/empty column types together with the console output of
   `SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE FROM <catalog>.information_schema.columns WHERE
   TABLE_SCHEMA = '<db>' AND TABLE_NAME = '<table>'`.
8. **Console context stability, flag-ON (M6) — the read-back regression:** open a console and run
   any statement (e.g. `SELECT 1;`). The namespace dropdown must **keep** showing
   `<catalog>.<database>` afterwards — it must NOT reset to `<database>`, and completion must stay
   table-scoped. Acceptance cases:
   - `SWITCH extcat; USE somedb;` then run any statement → header reads **`extcat.somedb`** (NOT
     `somedb.<database>` — the M6-fixed mis-binding where the bare database name was seated in the
     catalog slot) and completion is scoped to that database.
   - bare `SWITCH extcat;` only, then any statement → header reads **`extcat`** with the database
     placeholder; completion scoped to the catalog.
   - The switcher popup should now be the **stepped** catalog → database shape (M6 corrected the
     M4 branch reading: the stepped popup needs `searchPathObjectKind == SCHEMA` + a composable
     DATABASE-kind switch, both of which now hold).
   Expected `DorisCatalogs:` trail: one `switcher popup inputs: searchPathObjectKind=SCHEMA,
   DATABASE-probe sql=SWITCH ...` line per session; per execution a `search-path read-back:
   catalog=<c> (via current_catalog()|SHOW CATALOGS IsCurrent|assumed default), database=<db> ->
   <path>` line; per switcher pick a `console switch to ... -> use ...` line. On failure, collect
   those lines — the `via <source>` tells which probe stage produced the catalog, and a wrong
   header with a CORRECT read-back line would implicate the platform's path binding rather than
   the loader.
9. **Fresh-connection default scope, flag-ON (M7):** create a **brand-new** Doris data source
   (delete/recreate rather than reusing one — the default only applies to a data source whose
   introspection scope was never touched), then expand its root once. Expected: **without any
   manual scope ticking**, the `internal` catalog is deep-introspected — expand `internal` → its
   databases → tables/views → columns are there; external catalogs appear as (empty-until-opened)
   nodes. There should be **no need** to open the schemas pane and select "all databases"/"all
   schemas". Log markers (on first expand):
   `DorisCatalogs: default introspection scope (fresh data source): 'internal' deep-introspected,
   external catalogs enumerated -> <serialized pattern>` (proves the seam fired and shows exactly
   what the platform received), followed by `SHOW CATALOGS -> [...]` and, for internal's databases,
   `catalog 'internal' db '<db>' -> N tables, M views`. If the tree is still empty: the *absence*
   of the "default introspection scope" line means the seam did not run (data source not actually
   fresh, or the flag is off); a `no 'internal' catalog ...` warning means the server has no
   `internal`.

**C. Collect on failure** — Help → Show Log in Finder (`idea.log`), then:
```
grep 'DorisCatalogs:' idea.log
```
Marker lines emitted, in order:
- `DorisCatalogs: SHOW CATALOGS -> [internal, hive_archive, ...]` — catalog enumeration worked.
  *Absent / empty* → `SHOW CATALOGS` failed or column label `CatalogName` did not bind (check the
  same-line warning; the fix is the struct field name in `DorisCatalogQueries.CatalogRow`).
- `DorisCatalogs: catalog '<c>' databases -> [...]` — per-catalog database listing (stateless
  `SHOW DATABASES FROM <c>`).
- `DorisCatalogs: catalog '<c>' db '<d>' -> N tables, M views` — per-database object listing
  (stateless `<c>.information_schema`).
- `DorisCatalogs: catalog '<c>': qualified <what> query failed; falling back to SWITCH + unqualified
  (older Doris?)` — the qualified form is unsupported for that catalog; the SWITCH fallback ran.
  Expected on older Doris (matches a failing pre-flight probe); on a current Doris this line is a bug
  report in itself — capture the attached stack trace.
- `DorisCatalogs: ... failed; skipping ...` (with stack trace) — a specific catalog/database was
  broken/slow and was skipped (fallback included); the rest should still populate.
Also capture any `ClassCastException` mentioning `Mysql*`/`Ms*` (would indicate an un-overridden
casting helper) and the full stack of any introspection error.

**D. Rollback** — remove `-Ddoris.catalogs.experimental=true` (or just run plain `./gradlew runIde`)
and Refresh. The tree returns to shipped single-database behaviour with zero code changes. Because the
flag defaults off, no shipped user is ever affected.

### 6. Deviations / risks for review

1. **Live-introspection correctness is unverified** (no Doris available in the build environment).
   Highest-risk unknowns, in order: (a) jdba `structOf` column-label binding for `SHOW CATALOGS`
   (case sensitivity of `CatalogName`); (b) whether the framework's per-schema level bookkeeping
   tolerates eager `createOrGet` in `createSchemaRetriever.process()` or clears children on expand;
   (c) which Doris versions support the qualified forms (`SHOW DATABASES FROM <c>`,
   `<c>.information_schema.*`) — probe-able up front via the §5 pre-flight, and self-healing via the
   per-catalog SWITCH fallback. All three surface immediately in the `DorisCatalogs:` log during the
   runtime test.
2. **Column data types not populated** in M1 (names only) — deferred to Gate 2 (`setStoredType`
   needs a `DasType` from the type system).
3. **`getDatabaseDialect()` still returns MYSQL** — kept for parsing/scripting parity and flag-off
   safety; harmless flag-on because the introspector is now DORIS-keyed, not selected via the
   metadata dialect. Left unchanged.
4. **`hookUpHelper` stays MySQL flag-on** (no Ms sibling; its cast is guarded-safe) — a deliberate,
   documented exception to the "route to Ms flag-on" pattern.
5. **`verifyPlugin` (IntelliJ Plugin Verifier) is not wired** in this project (no verifier IDE
   configured — pre-existing), so structural verification relied on `buildPlugin` + a class-name/EP
   cross-check + the compile probe, not the verifier.
6. **`MsScriptGenerator`/`MsPredicatesHelper` receive the DORIS `Dbms`** (not `MSSQL`) flag-on. Not
   exercised by the M1 tree feature; if DDL export / data-grid filtering misbehaves under the flag,
   that is the place to look. Out of M1 scope.

---

## Gate 1 log — M2 (default introspection scope + terminology)

*Executed 2026-07-07, after the user's successful M1 runtime test (catalogs + contents visible; the
three M1 runtime unknowns cleared). Fixes the two M1 UX findings: a fresh data source looked empty
until scopes were hand-picked, and the top tree level said "databases" instead of "catalogs".*

### 1. The default-scope mechanism (bytecode findings)

**Where the default comes from — it is introspector-keyed, not dbms-, driver-, or model-keyed:**

- `DatabaseIntrospectionSession.performTasksInTheConnectedSession` (database-plugin.jar): when
  `LocalDataSource.getIntrospectionScope()` **isEmpty()** and the loader context allows changing DS
  settings, it runs `introspectNamespaces()` then `updateDataSourceScope()`, which copies
  **`DBIntrospector.getDefaultScope()`** into `LocalDataSource.setIntrospectionScope(...)`.
  Because it fires *only on an empty scope*, a user's explicit selections are never clobbered —
  exactly the "first-connect only" semantics M2 needs. Nothing in `doris-drivers.xml` or the dbms
  registration participates.
- Shipped defaults: `BaseSingleDatabaseIntrospector.getDefaultScope()` = `SINGLE_DB_SCOPE` — a
  `TreePattern` selecting the SCHEMA named **`@`** (`DataSourceSchemaMapping.CURRENT_NAMESPACE_NAME`);
  `BaseMultiDatabaseIntrospector` = `MULTI_DB_SCOPE` (`@` DATABASE → `@` SCHEMA).
- **Why DORIS defaulted to nothing while MySQL works:** `@` is resolved at match time against
  `BasicNamespace.isCurrent()` (`DataSourceSchemaMapping.match`, bytecode: `matchedChildren(...,
  CURRENT_NAMESPACE_NAME, ...)` guarded by `BasicNamespace.isCurrent()`). Flag-ON, M1's
  `DatabaseLister` inherited the base `isCurrent(index,row) = (index == 0)` — an *arbitrary* first
  `SHOW CATALOGS` row — and no schema was ever marked current, so `@→@` matched nothing → empty
  tree until manual selection. Flag-OFF (issue #5), Doris connections frequently report no current
  database over the MySQL protocol, so no `MysqlBaseSchema` gets `isCurrent` → `@` matches nothing,
  while genuine MySQL data sources resolve it fine.

### 2. What was wired (both modes)

| Mode | Class | Default scope now |
|---|---|---|
| flag ON | `DorisIntrospector.getDefaultScope()` → `DorisCatalogScopes.multiCatalogDefaultScope()` | **All catalogs enumerated at the database level; `internal` deep-introspected; externals enumerated-but-not-deep-introspected.** Pattern: DATABASE group with (a) positive node `internal` carrying a wildcard SCHEMA group (all its databases), and (b) a negative node (matches every catalog *except* `internal`) with **no** SCHEMA group — the catalog is in scope as a bare database node, none of its schemas selected. The platform's `TreePattern` vocabulary expresses "enumerate but don't introspect" directly (a scope node without child groups), so no gap to document. External deep-introspection stays per-catalog opt-in via the schemas pane. |
| flag OFF (#5) | `DorisSingleDatabaseIntrospector` (a `MysqlBaseIntrospector` subclass, produced by the DORIS factory flag-off) overriding only `getDefaultScope()` → `DorisCatalogScopes.singleDatabaseDefaultScope(super.getDefaultScope(), getCurrentDatabase())` | The platform's own `@` pattern (exact MySQL parity when `isCurrent` lands) **union** the connection's reported current database **by name** (`getCurrentDatabase()` = jdba `ConnectionInfo.databaseName`), so the default resolves even when the `isCurrent` flag never does. No current database reported → base pattern returned untouched (unchanged, and identical to genuine MySQL in the same situation). |

Also fixed flag-ON: the catalog lister now overrides `isCurrent(index,row)` to honor Doris's own
`IsCurrent` column from `SHOW CATALOGS` (new nullable `CatalogRow.IsCurrent` field; `Yes/true/1`),
falling back to `internal` when the column is absent — replacing the arbitrary first-row marking.
This drives the tree's "current" highlight and any user-configured `@` patterns.

**Dual-mode note (deliberate M1 exception):** flag-OFF is no longer literally byte-for-byte — the
factory now returns `DorisSingleDatabaseIntrospector` instead of the stock `MysqlBaseIntrospector`.
The subclass changes *only* `getDefaultScope()` (the #5 fix, per M2 tasking); it was made a
**subclass, not a wrapper**, precisely so `instanceof MysqlBaseIntrospector` checks in platform code
(`MysqlIntrospectorStatsProvider`) keep matching and every inherited behaviour stays identical.

### 3. Terminology verdict: DONE flag-ON (cheap, per-dbms by design)

Kind display names are produced by `ModelHelper.getName(kind, plural)` which consults the per-dbms
**`ModelHelper.getCustomName(kind, plural)`** hook before falling back to the static `ObjectKind`
bundle name. Verified consumers: `DvTreeModelLayer` (database-explorer family nodes),
`DbPresentationCore` (object presentation), `DbNamespacesTree` (the schemas pane). This is exactly
how Cassandra renames SCHEMA→"keyspace" (`CassModelHelper`). Implemented flag-ON as
`DorisCatalogModelHelper : ModelHelper` (returned by `DorisModelFacade.getModelHelper()` flag-ON):
`getCustomName(DATABASE)` → "catalog"/"catalogs". Flag-OFF keeps `MysqlBaseModelHelper` — labels
unchanged.

Two documented limitations: (a) `MsModelHelper` is `final`, so the flag-ON helper extends the
generic `ModelHelper` base instead; the Ms-specific extras it loses (LOGIN/ROLE custom names, MSSQL
grant controller, creation-template examples) concern object kinds the Doris introspector never
populates. (b) Only kind-name-driven labels change; fixed platform bundle strings that hardcode the
word "database(s)" outside the kind-name mechanism (if any surface) are out of this hook's reach.

### 4. Tests (offline)

`DorisCatalogScopesTest`: flag-ON pattern shape (internal matched + deep via wildcard SCHEMA group;
externals matched + shallow with no SCHEMA group), flag-OFF composition (null/blank current → base
returned *same instance*; named current → selectable by name, `@` preserved, unrelated names not
selected), and terminology (`getName(DATABASE)` = catalog/catalogs; SCHEMA/TABLE not renamed).
Full suite 63 tests / 0 failures; `buildPlugin` green.

### 5. Residual risks

- The `updateDataSourceScope` copy happens **once**: users who already made manual selections (the
  M1 runtime tester included) keep them; the M2 defaults are only observable on a *fresh* data
  source. The §5 runtime script step B now says so explicitly.
- Whether the tree *renders* an in-scope-but-schemaless external catalog node on all tree-filter
  settings ("Show all namespaces" off) is a runtime question; the catalogs are always enumerated
  into the model by the lister, and the scope now explicitly includes them, which is the strongest
  claim the scope machinery can express.
- Flag-OFF's named-current union depends on jdba's `ConnectionInfo.databaseName` being filled for
  Doris (Connector/J reports the URL database via `getCatalog()`); if Doris leaves it null the
  behaviour degrades exactly to today's (issue #5 then needs the URL to include a database —
  documented in the runtime script).

---

## Gate 1 log — M3 (3-part resolution & completion in the SQL editor)

*Executed 2026-07-07, after the user's successful M2 runtime test. Gap: flag-ON,
`SELECT * FROM extcat.somedb.sometable` neither resolves nor completes past level 2, while the tree
has all three levels. 2-part references into the current catalog work.*

### 1. Where qualification depth actually lives (bytecode evidence)

The M1 deviation list suspected `DorisSqlDialect.getDatabaseDialect()` (still MYSQL). The audit
shows depth does **not** live there. Three mechanisms participate; two were already correct:

| Mechanism | Class/method | Depth source | Flag-ON status |
|---|---|---|---|
| Expected kinds per qualifier position | `SqlImplUtil.getParentTypes` → `SqlLanguageDialectEx.getParentDbTypes` | **`DbImplUtilCore.getMetaModel(dialect.getDbms())`** = `ModelFacade.forDbms(DORIS).getMetaModel()` → `metaModel.getParentKinds(kind)` — *our own facade*, so SCHEMA's parent already includes DATABASE flag-ON | already correct |
| Qualified-chain walking | `SqlImplUtil.processQualifierImpl` | resolves the qualifier, then feeds `DasObject.getDasChildren(kind)` directly — **no import/dialect gate** | already correct |
| **Head-segment namespace gate (the blocker)** | `SqlFileImpl.processDeclarationsImpl` → `importedCondition` lambda: a DATABASE/SCHEMA node is fed to resolution only if `SqlDialectImplUtilCore.checkImports(importState, ds, node)` passes (bypass: only completion's include-all mode — `SqlCompletionScopeProcessor.shouldIncludeAllNamespaces` = "suggest all objects" setting or 3rd completion invocation — and even that requires `hasChildren`) | import state seeded by the **SQL dialect's `getBaseImports`**; `MysqlDialectBase.getBaseImports` = default-namespace anchoring + `getSchemaBaseImports` — **schema-level only, cannot express a DATABASE level** | **broken — fixed in M3** |

Failure chain, precisely: the head segment `extcat` of a qualified chain resolves through the
*unqualified* machinery; `extcat` is a DATABASE-kind node; the Mysql-built import state has no
DATABASE groups; `checkImports` fails; the head never resolves; every downstream segment shows
unresolved and completion cannot advance. Why 2-part into the current catalog still worked: Mysql's
`getBaseImports` first branch anchors at the data source's **default namespace das object**
(`SqlDialectImplUtilCore.createObjectPattern(names, defNs)` builds its real path — which under the
Ms model *includes* its catalog), so the current catalog + current schema pass the gate; everything
outside doesn't.

### 2. The `getDatabaseDialect()` consumer audit → do NOT flip it

86 classes reference `getDatabaseDialect` (bytecode scan across database-plugin + modules). The
depth-relevant resolve path uses it only for search-path **sorting** (`SqlReferenceImpl.
sortBySearchPath`) and presentation. The heavyweight consumers are **wire-facing SQL generation**:
`DatabaseTableGridDataHookUp`/`DataBusGridDataHookUp` (data-grid paging SQL), `BaseSelectGenerator`/
`BaseInsertRowsGenerator` (DML generation), `DbScriptDataExtractor`/`DbObjectFormatter` (extractors),
`JdbcEngine`/`JdbcEngineUtils`/`SearchPathStorage` (console execution + schema switching),
`DatabaseDialectEx.qualifiedIdentifier`/`catToScript` (DDL qualification/quoting). Flipping DORIS's
metadata dialect to MSSQL would emit **T-SQL** (bracket quoting, TOP/OFFSET-FETCH paging, T-SQL
switching) at a MySQL-protocol server. `getDatabaseDialect()` therefore stays **MYSQL in both
modes** (flag-off additionally for the historic dialect/model/introspector-agreement constraint);
the fix belongs on the `SqlLanguageDialect` layer, which the tasking anticipated ("if depth is keyed
elsewhere, implement there and document").

### 3. What changed (flag-ON only)

`DorisSqlDialect.getBaseImports(dataSource, names)` now returns
`union(super.getBaseImports(...), DorisCatalogScopes.allCatalogsImportPattern(names))`:

- `super` (MySQL) part unchanged — keeps the default-namespace anchoring that made current-catalog
  references work.
- `allCatalogsImportPattern`: `dataSources(names) → DATABASE(wildcard)` — every catalog becomes an
  importable, resolvable, completable **qualifier head**. The wildcard node deliberately carries
  **no schema group**, so no external catalog's contents are swept into the unqualified scope
  (completion noise / cross-catalog table flooding). Once the head resolves, levels 2 and 3 are
  supplied by the gate-free `getDasChildren` walk.
- Flag-OFF: `getBaseImports` returns the `super` result untouched (asserted by construction; suite
  green with the flag off).

Completion at qualifier position 1 (catalog names) needs nothing extra: imported namespaces are
offered by the standard completion path once they pass the same `checkImports` gate.

### 4. Expected editor behaviour (next runtime pass, flag-ON)

- `extcat.` → catalog names complete at position 1; `extcat.somedb.` → that catalog's databases
  complete; `extcat.somedb.sometable` → tables complete and the full reference resolves (no red).
- 2-part `internal_db.sometable` (current catalog) — unchanged, still resolves.
- Constraint: the external catalog's databases/tables must be **in the das model** (M2 default
  enumerates external catalogs but does not deep-introspect them; a catalog with zero introspected
  children cannot offer or resolve children — `hasChildren` guard. Opt into the catalog in the
  schemas pane first, as M2 documents).
- On failure grep `idea.log` for `DorisCatalogs:` (introspection side) and capture: whether the
  head segment alone (`SELECT * FROM extcat.x.y`, caret on `extcat`) resolves (Cmd+B), whether
  "Settings → ... → Code Completion → SQL: Suggest all objects" changes completion behaviour (it
  toggles the include-all bypass — if things only work with it ON, the import fix regressed), and a
  screenshot of the schemas pane showing the external catalog's introspection state.

### 5. Tests

`DorisCatalogScopesTest.testAllCatalogsImportPatternShape` (pattern rooted at the data-source
level; DATABASE group matches any catalog name incl. `internal`/`extcat`; no schema children) and
`testMetadataDatabaseDialectStaysMysql` (wire-facing dialect pinned to MYSQL). Live resolution
itself requires a das model + console and is left to the runtime pass, per tasking. Suite: 65
tests / 0 failures; `buildPlugin` green.

---

## Gate 1 log — M4 (console namespace switcher)

*Executed 2026-07-07, after the user's M3 runtime pass. Two switcher bugs flag-ON: (A) selecting any
entry ran ``use `catalog.schema` `` — the whole dotted path quoted as ONE identifier — which Doris
rejects ("Unknown database"); manually typing unquoted `use catalog.schema` works. (B) the dropdown
was a flat list of schema names across all catalogs with no catalog grouping.*

### 1. The switcher machinery (bytecode findings)

- **UI + composition entry point:** `com.intellij.database.run.actions.ChooseSchemaAction`. It gets
  the database dialect via `DbImplUtil.getDatabaseDialect(JdbcConsoleBase)` — resolving through the
  **dbms-keyed `<dialect>` EP** (DORIS → `extensionFallback` → the final
  `com.intellij.database.dialects.mysql.MysqlDialect`). *Not* through
  `SqlLanguageDialect.getDatabaseDialect()` (the M3-pinned editor-side accessor) — the two are
  independent seams.
- **BUG A — the composer:** `ChooseSchemaAction.switchSearchPath` →
  `DatabaseDialectEx.sqlSetSearchPath(SearchPath)`. `MysqlBaseDialect`'s implementation takes
  `SearchPath.getCurrent()` (an `ObjectPath`), renders **`ObjectPath.getDisplayName()`** — the full
  dotted path, two components under the flag-ON Ms model — and quotes that single string via
  `NamingService.catToScript(name, kind, quotesPriority)`, then `format("use %s", ...)`. One name in,
  one pair of backticks out: ``use `catalog.schema` ``. (Reference: SQL Server's
  `AbstractTsqlDialect.sqlSetSearchPath` composes only the DATABASE component for `use`, and its
  `getSearchPathObjectKind()` returns `DATABASE`.)
- **BUG B — the popup selector:** `ChooseSchemaAction.createInitialStep` branches on
  `dialect.getSearchPathObjectKind()`: `DATABASE` → `DbScStep` (the **two-level stepped
  database→schema popup** SQL Server users get, i.e. catalogs grouped); otherwise
  `supportsSearchPath()` → `SearchPathStep`; else `SingleScOrDbStep` — the flat single-level list
  the user saw (MySQL's default kind is `SCHEMA`, from `AbstractDatabaseDialect`).
  **[CORRECTED IN M6: this reading was inverted — kind `DATABASE` jumps AWAY from `DbScStep`
  (`if_acmpeq`), which is why the runtime pass still showed a flat list. See the M6 section for the
  verified branch; M4's kind override is reverted there.]**

Both bugs share one root: the dbms-keyed database dialect was MySQL's, whose search-path surface is
single-component. The seam is the `<dialect dbms="DORIS">` EP.

### 2. The fix — `DorisDatabaseDialect` (`<dialect dbms="DORIS">`, dual-mode)

`dev.sort.doris.catalog.DorisDatabaseDialect : MysqlBaseDialect` (the fallback's `MysqlDialect` is
`final`, but it only adds `getDbms`/`getDisplayName` over the public base — replicated, so flag-OFF
behaviour is identical; bytecode-audited: no platform code casts to the final `MysqlDialect` class).

- **Flag-OFF:** everything inherited from `MysqlBaseDialect` unchanged; `getDisplayName()` stays
  "MySQL"; `getSearchPathObjectKind()`/`sqlSetSearchPath` delegate to super. The suite's
  `DorisDatabaseDialectTest.testFlagOffDialectMatchesMysql` pins output equality with a real
  `MysqlDialect` instance.
- **Flag-ON:**
  - `getSearchPathObjectKind()` → `DATABASE` → the console shows the stepped catalog→database
    popup (**fixes BUG B** — no platform UI work needed; the grouping UI already ships for the
    DATABASE kind).
  - `sqlSetSearchPath` → `DorisCatalogQueries.sqlSwitchSearchPath(current)`, per path shape,
    **each part quoted separately**:

    | Selected path | Composed SQL |
    |---|---|
    | catalog + database (SCHEMA with DATABASE parent) | ``use `catalog`.`db` `` |
    | bare database (SCHEMA, no parent) | ``use `db` `` (MySQL-identical) |
    | bare catalog (DATABASE) | ``SWITCH `catalog` `` |
    | anything else | null (not composable) |

    Composition logs `DorisCatalogs: console switch to '<path>' -> <sql>`.
- **Quoting fallback (one-line swap):** the composer is the single small function
  `DorisCatalogQueries.sqlSwitchSearchPath`. If a live Doris rejects the per-part-quoted form,
  change that function to (in order of preference) unquoted `use catalog.db`, or two statements
  ``SWITCH `catalog` `` + ``use `db` ``. The user-proven form is unquoted `use catalog.db`;
  per-part backtick quoting is standard Doris identifier quoting and expected to pass.

### 3. Tests

`DorisDatabaseDialectTest`: composed SQL per path shape (catalog-qualified, bare schema, bare
catalog, non-namespace kind → null; embedded backticks doubled per part, never fused across the
dot), and flag-OFF equivalence against a live `MysqlDialect` (same `searchPathObjectKind`, same
`sqlSetSearchPath` output, "MySQL" display name, injected DORIS dbms preserved). Suite: 68 tests /
0 failures; `buildPlugin` green.

### 4. Residual risks

- Whether Doris accepts ``use `catalog`.`db` `` (per-part quoted) on all supported versions is the
  one runtime unknown — mitigated by the documented one-line fallback swap.
- `tryToLoadSearchPath` (reading the CURRENT namespace back from the connection) stays MySQL's
  (`SELECT DATABASE()`-based): flag-ON it reports the database name without its catalog, so the
  switcher's *displayed current* entry may show unqualified. Cosmetic; deferred.
- The `DbScStep` stepped popup enumerates databases/schemas from the das model; catalogs that are
  enumerated-but-not-introspected (M2 default) will show no databases beneath them until opted in —
  consistent with the tree behaviour.

---

## Gate 1 log — M5 (portion retriever + column types)

*Executed 2026-07-07, after M4. Item 1 fixes the SEVERE from the user's runtime pass
(`RuntimeException: The introspector DorisIntrospector doesn't support the requested retriever` at
`BaseNativeIntrospector.createLevelOneRetrieverForPortion`, triggered by "Introspect the Portion of
15 schemas (full) on level 1" from the schemas pane / console switcher). Item 2 replaces the
name-only M1 columns with real stored types.*

### 1. Item 1 — the portion (level-one) retriever seam

**Which seams existed vs. which was missing (bytecode):** a sweep of
`BaseNativeIntrospector.thisRetrieverIsNotSupported` call sites shows **exactly one**
retriever-factory seam with a not-supported default: `createLevelOneRetrieverForPortion(tran,
SchemaPortion)`. Every other factory the framework can request (`createDatabaseLister`,
`createDatabaseRetriever`, `createSchemaRetriever`, `createNativeRetriever` — defaulted by
`BaseMultiDatabaseIntrospector` — and `createServerObjectsRetriever` — defaulted to
`BaseServerObjectsRetriever`) was already implemented or safely defaulted in M1. `MsIntrospector`
fills the same seam with `MsLevelOneRetriever : BaseDatabaseSchemasRetriever<MsDatabase,MsSchema>`
(ctor `(DBTransaction, D, List<S>)`, override `process()`; its L1 is a bulk names sweep —
`retrieveAliases/retrieveMajorNames/retrieveMinorNames/finalizeLevel1`).

**Implementation:** `DorisIntrospector.createLevelOneRetrieverForPortion` returns an anonymous
`BaseDatabaseSchemasRetriever<MsDatabase, MsSchema>(transaction, portion.database, portion.schemas)`
whose `process()` loops **exactly the portion's schemas** through the shared per-schema retrieval
(extracted as `retrieveSchemaObjects`, also used by the deep `createSchemaRetriever`). Log marker:
`DorisCatalogs: portion introspection: N schema(s) of catalog '<c>' (mode ...)`.

**Portion semantics:** out-of-portion schemas are never touched (no family-wide
`markChildrenAsSyncPending`/`clear`; only `createOrGet` on the requested schemas' own families);
the databases (catalog) level is never reset. Per-schema failures log + skip that schema only.
One honest simplification: our "level one" performs the full table/view/column retrieve — the only
object surface defined so far — rather than Ms's names-only L1 sweep; if the platform later
requests a deeper level for the same schema, the retrieval re-runs idempotently (`createOrGet`).
Slightly more work per portion than a true L1, never less correct.

### 2. Item 2 — column stored types

**Parse path (the platform's own generic recipe, verified in `JdbcIntrospectorHelper` bytecode):**
`DataTypeFactory.of(spec)` → `DasUnresolvedTypeReference.of(dataType)` →
`BasicModTypedElement.setStoredType(dasType)`. `DataTypeFactory.of` parses full specs
(`varchar(65533)`, `decimal(27,9)`) into a `DataType` with size/scale;
`DasUnresolvedTypeReference` resolves lazily against the dbms type system, and a name the type
system does not know stays an unresolved reference **that preserves the type-name string** — the
exact guard the tasking requires: no parse crash, and the UI shows `variant` instead of unknown.
(`MsIntrospector` itself uses an internal `makeDasType`; the `DataTypeFactory` path is the public
equivalent used by the platform's generic JDBC introspection.)

- Source columns: `information_schema.columns.COLUMN_TYPE` (full spec, MySQL layout — preferred)
  with `DATA_TYPE` (bare name) as fallback; both now selected by the qualified and the SWITCH-
  fallback query forms. Both null/blank → column stays untyped (never crashes).
- Mapping table (offline-tested, `DorisColumnTypesTest`): MySQL-likes keep sizes
  (`varchar(65533)`, `decimal(27,9)`); Doris exotics preserve names (`variant`, `bitmap`, `hll`,
  `largeint`, `agg_state`, `json`, `ipv6`); generics and hive-side strings parse without crashing
  (`array<int>`, `map<string,int>`, `struct<a:int,b:string>`, `decimal(38,18)`).
- Bonus: `ORDINAL_POSITION` now populates `setPosition(short)` so columns order as in the table
  definition instead of retrieval order.

### 3. Tests / build

`DorisColumnTypesTest` (type table above) + updated `DorisCatalogQueriesTest` (COLUMN_TYPE in both
query texts and the row struct). Suite: 73 tests / 0 failures; `buildPlugin` green; flag-off
untouched (all changes live inside the flag-ON-only introspector and the pure query object).

### 4. Residual risks

- The full-retrieve-as-L1 simplification (above): portions do more work than a names-only L1;
  revisit only if portion introspection of large external schemas proves slow.
- `COLUMN_TYPE` availability on external catalogs' FE-served `information_schema` is assumed but
  unverified; the `DATA_TYPE` fallback covers absence, and blank values leave columns untyped
  rather than failing.
- Level bookkeeping relies on the framework's own marking around the retrievers (base
  `markSchemaIntrospected` is a no-op; Ms's override is MSSQL-specific state, not required for
  correctness). If the runtime pass shows schemas re-introspecting on every expand, this is the
  knob to look at.

---

## Gate 1 log — M6 (console search-path read-back — the "console context lost" regression)

*Executed 2026-07-07, after the user's M4/M5 runtime pass reported a badly degraded console
flag-ON: (1) after any statement the namespace dropdown reset to `<database>` and completion
degraded to keyword junk; (2) the switcher popup was still a flat mixed list. A follow-up
screenshot pinned the exact failure: after `SWITCH extcat; USE somedb;` the header read
`somedb.<database>` — the one-level MySQL read-back result seated in the CATALOG slot.*

### 1. Verified popup branch (M4's reading was inverted)

Full `ChooseSchemaAction.createInitialStep` bytecode (offsets):

```
14: kind = dialect.getSearchPathObjectKind()
21: if (kind == DATABASE) goto 69                        // if_acmpeq — DATABASE *skips* DbScStep!
24: // kind != DATABASE:
31:   if (JdbcUrlParserUtil.isDatabaseBounded(target)) goto 55
49:   if (dialect.sqlSetSearchPath(SearchPath.of(ObjectPath("test", DATABASE))) == null) goto 69
55:   return DbScStep.create(console)                    // the stepped two-level popup
69: if (dialect.supportsSearchPath()) return SearchPathStep
87: return SingleScOrDbStep.create(console)              // the flat list
```

The stepped `DbScStep` popup engages only when **kind != DATABASE** and the dialect either has a
database-bounded URL or **composes a switch for a DATABASE-kind path**. M4 set the kind to
`DATABASE`, which routed straight to line 69; `MysqlBaseDialect.supportsSearchPath()` is `false`
(bytecode: `iconst_0`), so the console fell to the flat `SingleScOrDbStep` — the observed flat
mixed list. **The flat list was caused by M4's kind override, not by the read-back.** Fix: the kind
returns to **SCHEMA** in both modes, and the stepped popup is enabled by our composer answering the
DATABASE-kind probe with ``SWITCH `test` `` (M4 already implemented that part).

### 2. The read-back (the actual context-loss root) — verified and fixed

- **Machinery:** `JdbcEngine` (two call sites) re-reads the current namespace after execution via
  `DatabaseDialectEx.tryToLoadSearchPath(connection)`, resolving the dialect through the dbms-keyed
  EP (`DbImplUtilCore.getDatabaseDialect(connection.getDbms())`) — i.e. our `DorisDatabaseDialect`
  on both sites. The returned `SearchPath` is passed through **as-is**; the path *shape* is
  entirely loader-defined (there is no re-shaping), so returning a correct two-level path fixes the
  seating at the source (coordinator point (c)): the observed `somedb.<database>` header was the
  one-level `SearchPath.of(ObjectPath("somedb", SCHEMA))` from `MysqlBaseDialect.tryToLoadSearchPath`
  (bytecode: `select database()` → single SCHEMA path) being bound positionally into the first
  (catalog) slot of the two-level display.
- **Fix (flag-ON only):** `DorisDatabaseDialect.tryToLoadSearchPath` now returns the **full
  two-level path**, sources defensively chained:
  1. `select current_catalog()` (Doris built-in) + `select database()` — both via the platform's
     own `DbImplUtilCore.concatStringResults(connection, connection.dbms, sql, 1, NO_CONCAT)`
     (byte-identical helper shape to MySQL's read-back);
  2. catalog probe failed (older Doris without `current_catalog()`): `SHOW CATALOGS` → the
     `IsCurrent` row (remote-statement iteration, same pattern as `DorisDefinitionProvider`);
  3. still unknown: assume `internal` (connect-time default) so the path always binds.
  Mapping (pure, unit-tested `DorisCatalogQueries.currentSearchPath`): catalog+db →
  `DATABASE(catalog) -> SCHEMA(db)`; catalog only → `DATABASE(catalog)`; **never** a bare
  database-name path that the platform would seat in the catalog slot. Flag-OFF: MySQL's read-back
  untouched.
- **Runtime log trail (`DorisCatalogs:`):** `switcher popup inputs: searchPathObjectKind=SCHEMA,
  DATABASE-probe sql=SWITCH `test` ...` (once per session, at the popup decision input we control);
  `console switch to '<path>' -> <sql>` (each switch composition); `read-back probe '<sql>' failed
  (...); falling back` (probe chain); `search-path read-back: catalog=<c> (via <source>),
  database=<db> -> <display-path>` (each post-execution re-sync).

### 3. Addendum — flat-list dotted-item fallback: NOT reachable from plugin seams (evidence)

`ChooseSchemaAction.getText(ObjectPath)` renders popup items via **`ObjectPath.getName()`** — the
last path component only — hard-coded in the action (bytecode: `getName()` →
`shortenTextWithEllipsis`; the only other branch is the "unnamed" bundle label using
`ObjectKind.getPresentableName()`). No dialect, `ModelHelper` (kind names only), or presentation EP
participates in the item text, and injecting dotted names into model node names would corrupt the
model. A dotted `catalog.database` flat list is therefore sealed platform UI; the stepped popup
(§1) remains the only viable shape — and with the branch fix it should engage.

### 4. Console-path MySQL leftovers audit (coordinator item 3)

Dialect methods the console/session machinery reads (verified callers), status:

| Seam | Caller | Status |
|---|---|---|
| `tryToLoadSearchPath` | `JdbcEngine` ×2 (post-execution re-sync) | **fixed flag-ON (M6)** |
| `sqlSetSearchPath` | `ChooseSchemaAction.switchSearchPath`, `DatabaseEditorHelper`, `DbImplUtil.canSwitchTo` | fixed flag-ON (M4); `canSwitchTo` = `sqlSetSearchPath(path) != null` → our non-null answers make catalog+schema entries switchable |
| `getSearchPathObjectKind` | `ChooseSchemaAction.createInitialStep` (popup branch) | **reverted to SCHEMA (M6)** |
| `supportsSearchPath` | `createInitialStep` line 69 | MySQL `false`, correct for Doris (no PG-style search path) — unchanged |
| `sqlResetSearchPath` | `JdbcEngine` ×1 | MySQL returns `null` (no-op) — safe, unchanged |
| `shouldSwitchThroughJdbc(kind)` | session switching | inherited default — untouched; if runtime shows JDBC-level setCatalog interference, this is the next knob |
| `tryToLoadDatabaseList` / `supportsLoadDatabaseList` | data-source config UI | inherited MySQL — cosmetic surface, deferred |

### 5. Tests / build

`DorisDatabaseDialectTest` gains: two-level read-back path mapping (catalog+db / catalog-only /
assumed-internal), probe SQL constants, and the popup-branch inputs (kind==SCHEMA in both modes +
non-null DATABASE-kind composer answer — the two conditions line 21/49 check). Flag-off equivalence
against a live `MysqlDialect` still pinned. Suite: 76 tests / 0 failures; `buildPlugin` green.

### 6. Residual risks

- `current_catalog()` availability by Doris version is unverified — covered by the SHOW CATALOGS
  fallback and, last resort, the `internal` assumption (each stage logged).
- The read-back runs up to two extra statements per execution re-sync (catalog probe + fallback);
  negligible against console round-trips, noted for perf review.
- `isDatabaseBounded(target)` (branch line 31) may also engage `DbScStep` on its own for URLs with
  a database — either way the popup lands on the stepped shape; only the flat list would indicate a
  regression (the once-per-session `switcher popup inputs` log line tells which inputs the platform
  saw).

---

## Gate 1 log — M7 (default introspection scope for a new connection — spike)

*Spike, 2026-07-07. Goal: a fresh flag-ON Doris data source must not come up empty — `internal`
(Doris's built-in OLAP store, always present) should be the default introspected scope so content
loads without the user ticking "all databases"/"all schemas". Investigate-first; implement only a
clean seam.*

### 1. Seam findings (bytecode evidence) — the canonical hook already exists

**The default introspection scope of a new data source is set by `DBIntrospector.getDefaultScope()`,
consulted in two proven places (DB-261):**

1. **At introspector init (first connect, before any task is built):**
   `BaseIntrospector.init(model, config, scope)` does
   `this.introspectionScope = selectIntrospectionScope(scope, ...)` (bytecode: `invokespecial
   selectIntrospectionScope` → `putfield introspectionScope`), and
   `BaseIntrospector.selectIntrospectionScope` returns **`getDefaultScope()` when the passed scope
   `isEmpty()`** (bytecode: `TreePattern.isNotEmpty` guard else `invokevirtual getDefaultScope`).
   A fresh `LocalDataSource` has an empty scope (`DataSourceSchemaMapping.myIntrospectionScope`
   starts empty), so the effective scope of the first introspection *is* `getDefaultScope()`.
2. **Persisted after the first namespaces pass:** `DatabaseIntrospectionSession.updateDataSourceScope`
   copies `getDefaultScope()` into `LocalDataSource.setIntrospectionScope(...)` **only when the DS
   scope is empty** — so it seeds the default and **never overrides a user's later explicit
   selection**.

**No competing seam exists.** Audit results:
- `DatabaseDialect`/`DatabaseDialectEx`: **no** `getDefaultSchema`/`getStartupSchema`/`getInitialScope`
  hook (grep of the full surface returned nothing). The only namespace-ish method is the SQL-editor
  import anchor `getDefaultNamespace` (protected static), which M3 already covers — it is *editor
  import scope*, not introspection scope.
- Driver config (`DatabaseDriverImpl`, `doris-drivers.xml`): **no** default-scope/default-schema
  attribute to set at creation.
- `getDefaultScope` readers: only the two above (plus each dialect's introspector override). It is
  **introspector-keyed**, i.e. we own it flag-ON via `DorisIntrospector`.

**Conclusion: the seam the coordinator asked for is `getDefaultScope()`, and it is already
implemented in M2** — `DorisIntrospector.getDefaultScope()` returns `internal` deep-introspected +
external catalogs enumerated. It is the platform-canonical default, applied on first connect and
persisted, non-overriding. The "default current catalog = internal" framing and the "default scope
includes internal" framing converge on this one method; the scope-default is the real lever (it
makes content *load*, not just move a cursor), and it is what we already register.

### 2. Implement vs. wall

**No new mechanism implemented** — adding a second one (e.g. mutating the persisted data-source XML
or reflecting into settings internals to set a scope at creation time) is exactly the hacky path the
spike says to avoid, and would risk double-application/conflict with the canonical seam. Instead,
**diagnostic hardening** of the existing seam (clean, flag-ON only, zero behaviour change) so the
next runtime pass is self-explaining:

- `getDefaultScope()` now logs the **serialized** applied pattern:
  `DorisCatalogs: default introspection scope (fresh data source): 'internal' deep-introspected,
  external catalogs enumerated -> <serialized TreePattern>`. If this line is present in `idea.log`
  on first connect, the seam fired and this is exactly what the platform received.
- The catalog lister now **warns if `SHOW CATALOGS` returns no `internal`** — the one case where the
  default's deep target is absent and the tree can legitimately look empty until the user opts into
  a catalog (satisfies the spike's "degrade safely + log" requirement; the static pattern already
  degrades without crashing — a missing `internal` node just matches nothing).

### 3. Degradation

- `internal` present (normal): deep-introspected on first connect; externals enumerated. Content
  loads with zero manual ticking.
- `internal` absent (pathological): default scope's `internal` node matches nothing, externals still
  enumerate; **no crash**; a `DorisCatalogs:` warning explains the empty content.
- User makes an explicit scope selection later: `updateDataSourceScope`'s empty-scope guard means
  the default is never re-applied — the user's choice wins (unchanged platform behaviour).

### 4. Tests / build

`DorisCatalogScopesTest.testDefaultScopeSerializesNonEmptyAndNamesInternal` pins the applied scope
as non-empty and naming `internal` (the diagnostic the log emits). Suite: 77 tests / 0 failures;
`buildPlugin` green; flag-off untouched (`getDefaultScope`/lister live only in the flag-ON
`DorisIntrospector`).

### 5. If the empty tree persists next pass

Because the seam is proven present and correct, a persistent empty tree would point at
*application*, not a missing seam. The `DorisCatalogs:` trail on a **fresh, first-expand** flag-ON
data source pinpoints it:
- **`default introspection scope (fresh data source): ... -> <pattern>` absent** → the DORIS
  introspector's `getDefaultScope` was not reached (introspector not selected? flag not read on?),
  or the DS scope was not empty (data source not actually fresh — delete/recreate).
- present, **`SHOW CATALOGS -> [...]` shows `internal`**, but no
  `catalog 'internal' db '<db>' -> N tables` lines → the scope was applied but internal's schemas
  did not deep-introspect: capture the pattern string and whether a manual "all schemas" tick then
  loads them (would implicate scope→introspection wiring, not the default seam).
- **`no 'internal' catalog ...` warning present** → the server genuinely has no `internal`; not a
  plugin bug.

---

## Future feature — "Introspect this?" intention on out-of-scope references

*TODO (froze-over era; the UX half of the lazy-catalog degrade story). Not a merge-gate item, but
closely coupled to it.*

The lazy-external default (enumerate catalogs, introspect only `internal`) is the right cost
default, but it leaves a discoverability hole: a reference into a known-but-not-introspected
catalog (`extcat.somedb.sometable`) just fails at the un-introspected segment, and the user has no
in-editor clue that the fix is one schemas-pane tick away (observed live: catalog and database
segments complete, then the table segment dies — reads like a bug, is actually scope).

Design sketch:
- **Detection.** When resolution fails at a segment whose PARENT path resolves to a model node that
  is enumerated-but-childless (an external catalog, or a database inside one, that introspection
  has never visited), classify the failure as OUT-OF-SCOPE rather than UNRESOLVED.
- **Affordance.** Register an intention/quick-fix on that reference: *"'<db>' is in catalog
  '<extcat>', which is not introspected. Introspect it?"* — accepting opts that catalog (or just
  that database) into the data source's introspection scope and triggers the incremental
  introspection, then re-resolution turns the reference green.
- **Degrade tie-in.** The same OUT-OF-SCOPE classification is where unresolved-error severity gets
  downgraded (weak-warning/no-highlight instead of red) — detection is shared; the intention and
  the severity change are one feature with two outputs.
- **Consistency.** Like TVF "Preview" (RESEARCH-tvf-completion.md §7): the affordance IS the
  permission gate — explicit, user-initiated, per-target; introspection cost is never spent on
  typing or background resolve.
