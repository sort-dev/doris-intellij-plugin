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
