package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.base.introspector.BaseIntrospector
import com.intellij.database.dialects.base.introspector.BaseMultiDatabaseIntrospector
import com.intellij.database.dialects.base.introspector.SchemaPortion
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mssql.model.MsSchema
import com.intellij.database.dialects.mysqlbase.introspector.MysqlBaseIntrospector
import com.intellij.database.dialects.mysqlbase.model.MysqlBaseRoot
import com.intellij.database.dialects.mysqlbase.model.MysqlBaseSchema
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.layoutedQueries.DBTransaction
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.families.ModNamingFamily
import com.intellij.database.util.TreePattern
import dev.sort.doris.DorisCatalogs

/**
 * Experimental multi-catalog introspector for Apache Doris (Gate 1 / Milestone 1, path (a) of
 * RESEARCH-catalog-introspection.md). **Only ever instantiated when
 * [DorisCatalogs.enabled] is true** — see [DorisIntrospectorFactory], which routes to the stock
 * `MysqlBaseIntrospector` when the flag is off.
 *
 * ## Shape
 *
 * Reuses SQL Server's public multi-database model (`Ms*`), so this is a
 * [BaseMultiDatabaseIntrospector] over `MsRoot / MsDatabase / MsSchema`. The mapping is:
 *
 * | DataGrip level | Doris concept   | populated by |
 * |----------------|-----------------|--------------|
 * | DATABASE       | catalog         | [createDatabaseLister] via `SHOW CATALOGS` |
 * | SCHEMA         | database        | [createDatabaseRetriever] via `SHOW DATABASES FROM <catalog>` |
 * | TABLE / VIEW   | table / view    | [createSchemaRetriever] via `<catalog>.information_schema.tables` |
 * | COLUMN         | column          | [createSchemaRetriever] via `<catalog>.information_schema.columns` |
 *
 * ## Stateless-first (Gate 1 design revision, review feedback)
 *
 * The primary per-catalog path is **stateless**: `SHOW DATABASES FROM <catalog>` and
 * catalog-qualified `information_schema` reads, which never mutate connection session state — safe
 * on pooled/shared/keep-alive connections, no per-catalog phase ordering, half the round trips.
 * `SWITCH <catalog>` + unqualified queries survive only as a **per-catalog fallback** for older
 * Doris versions where the qualified forms fail (see [runCatalogScopedOrFallback]).
 *
 * Note on the Ms-family rationale in the Gate 1 log: SQL Server's `MsIntrospector` literally
 * switches into each database (`USE`) before reading it. We still reuse the Ms family's *seam
 * structure* (database lister / database retriever / schema retriever) and its model, but in our
 * primary path the "switch into the database" step is a no-op — the queries carry the catalog
 * qualification themselves. Only the fallback reproduces the switching rhythm literally.
 *
 * All queries go through the platform's layouted-query facade ([DBTransaction.query] /
 * [DBTransaction.command]); node creation uses the model's `createOrGet` / `renew` mutators.
 *
 * ## Resilience
 *
 * External catalogs (hive/iceberg/jdbc) can be broken, slow, or permission-gated. Every per-catalog
 * and per-schema step is wrapped so one failure is logged (with the `DorisCatalogs:` prefix) and
 * skipped rather than aborting the whole introspection.
 *
 * ## Verification honesty
 *
 * The *mechanism* is proven offline (query text + layouts are unit-tested; the model/EP wiring
 * compiles and registers; flag-off is byte-identical). The *runtime correctness* against a live
 * Doris — that `SHOW CATALOGS` column labels bind, that the catalog-qualified `information_schema`
 * forms are supported by the target Doris version (probe-able up front, see the Gate 1 log §5
 * pre-flight), that the framework's level bookkeeping accepts eager `createOrGet` population —
 * cannot be asserted from this environment and is the subject of the runtime test script.
 */
class DorisIntrospector(
    context: DBIntrospectionContext,
    dbms: Dbms,
    modelFactory: ModelFactory,
) : BaseMultiDatabaseIntrospector<MsRoot, MsDatabase, MsSchema>(context, DorisNature, dbms, modelFactory) {

    /**
     * The default introspection scope a **fresh** data source receives (M2; M7 confirmed this is
     * the canonical seam and hardened its diagnostics).
     *
     * ## Why this is *the* seam that stops the empty tree (M7 bytecode findings)
     *
     * `DBIntrospector.getDefaultScope()` is consulted in **two** places, both proven in DB-261
     * bytecode, so it takes effect on the very first connect and persists:
     *  1. `BaseIntrospector.init(model, config, scope)` sets `introspectionScope =
     *     selectIntrospectionScope(scope, ...)`, and `selectIntrospectionScope` returns
     *     **`getDefaultScope()` whenever the passed data-source scope `isEmpty()`** — i.e. the
     *     effective scope of a fresh data source's first introspection is this pattern, before any
     *     task is built.
     *  2. `DatabaseIntrospectionSession.updateDataSourceScope()` copies `getDefaultScope()` into the
     *     `LocalDataSource` when its scope is empty, persisting the default (and never overriding a
     *     user's later explicit selection).
     *
     * There is no separate dbms-keyed "default schema"/"startup schema" hook on `DatabaseDialect`
     * and no driver-level scope attribute (M7 audit), so this introspector method — which we own
     * flag-ON — is the correct and only clean lever.
     *
     * The pattern: `internal` deep-introspected (its databases/tables load on first connect),
     * external catalogs enumerated but not deep-introspected (shown, opt-in per catalog). It names
     * `internal` explicitly (not the `@` current-namespace pattern the inherited `MULTI_DB_SCOPE`
     * uses, which can resolve to nothing on a fresh Doris connection). Degrades safely if `internal`
     * is somehow absent: its node simply matches no catalog and the externals still enumerate — no
     * crash (the [createDatabaseLister] warns in that case so the log explains an empty tree).
     */
    override fun getDefaultScope(): TreePattern {
        val scope = DorisCatalogScopes.multiCatalogDefaultScope()
        DorisCatalogs.info(
            "default introspection scope (fresh data source): '${DorisCatalogScopes.INTERNAL_CATALOG}' " +
                "deep-introspected, external catalogs enumerated -> " +
                com.intellij.database.util.TreePatternUtils.serialize(scope),
        )
        return scope
    }

    /**
     * M8: scope-interpretation fix. Called by `BaseIntrospector.init` **after** the effective scope
     * is assigned (bytecode: `putfield introspectionScope` at offset 405, `initSpecificThings` at
     * 442), so this is the one seam where the scope every downstream consumer reads — the schema
     * filter of `introspectAutomaticallyLevelByLevel`, the portion machinery, the tree's
     * `listNamespacesToShow` — can be normalized in one place.
     *
     * An **explicitly selected** catalog with no schema children (the exact shape the schemas pane
     * writes when a catalog is ticked whose databases were never loaded — `DbNamespacesTree.build`
     * serializes checked nodes positively, children only if checked) is expanded to
     * catalog -> all-schemas, because platform `matches()` semantics never imply children from a
     * naked parent node (a schema matches only through a SCHEMA group). The M2 enumerate-only
     * default (negative-with-exceptions node) is a fixed point of the expansion and stays shallow.
     */
    override fun initSpecificThings(
        model: com.intellij.database.model.basic.BasicModModel,
        config: com.intellij.database.dataSource.DataSourceBriefConfig,
    ) {
        super.initSpecificThings(model, config)
        val scope = introspectionScope ?: return
        val expanded = DorisCatalogScopes.expandExplicitCatalogSelections(scope)
        if (expanded !== scope) {
            DorisCatalogs.info(
                "scope expansion (explicitly selected catalogs imply all their databases): " +
                    com.intellij.database.util.TreePatternUtils.serialize(scope) + " -> " +
                    com.intellij.database.util.TreePatternUtils.serialize(expanded),
            )
            introspectionScope = expanded
        }
    }

    /** Level 1: enumerate Doris catalogs as DATABASE nodes via `SHOW CATALOGS`. */
    override fun createDatabaseLister(): DatabaseLister<*, *> {
        return object : DatabaseLister<DorisCatalogQueries.CatalogRow, MsDatabase>() {
            override fun listDatabases(tran: DBTransaction): List<DorisCatalogQueries.CatalogRow> {
                val rows = try {
                    tran.query(DorisCatalogQueries.LIST_CATALOGS).run().orEmpty()
                } catch (t: Throwable) {
                    DorisCatalogs.warn("SHOW CATALOGS failed; no catalogs will be listed", t)
                    emptyList()
                }
                val names = rows.mapNotNull { it.CatalogName }
                DorisCatalogs.info("SHOW CATALOGS -> $names")
                // M8: definitive per-catalog trail — how the (already expanded) scope classifies
                // each catalog. EXPLICIT_* deep-introspects; ENUMERATED_DEFAULT stays shallow.
                introspectionScope?.let { scope ->
                    val classes = names.associateWith { DorisCatalogScopes.classifyCatalog(scope, it) }
                    DorisCatalogs.info("catalog scope classification: $classes")
                }
                // M7: the default scope deep-introspects `internal`; if the server reports no such
                // catalog, the fresh-connection tree will look empty until the user opts into a
                // catalog — surface that as a warning so an empty tree is self-explaining in the log.
                if (names.none { it.equals(DorisCatalogScopes.INTERNAL_CATALOG, ignoreCase = true) }) {
                    DorisCatalogs.warn(
                        "no '${DorisCatalogScopes.INTERNAL_CATALOG}' catalog in SHOW CATALOGS ($names); " +
                            "the default deep-introspection target is absent — tree may appear empty " +
                            "until a catalog is selected in the schemas pane",
                    )
                }
                return rows.filter { it.CatalogName != null }
            }

            override fun applyDatabase(
                family: ModNamingFamily<*>,
                row: DorisCatalogQueries.CatalogRow,
            ): MsDatabase {
                // renew(family, id, name): create-or-refresh the DATABASE node for this catalog.
                return renew(family, row.CatalogId, row.CatalogName!!)
            }

            /**
             * M2: mark the session's actual current catalog. The base class marks whichever row
             * comes **first**, which is arbitrary for `SHOW CATALOGS`. Prefer Doris's own
             * `IsCurrent` column; fall back to `internal` (the connect-time default catalog) when
             * the column is absent. The current flag drives the tree's "current" highlight and any
             * `@`-based scope pattern a user configures.
             */
            override fun isCurrent(index: Int, row: DorisCatalogQueries.CatalogRow): Boolean {
                val flag = row.IsCurrent
                if (flag != null) {
                    return flag.equals("yes", ignoreCase = true) ||
                        flag.equals("true", ignoreCase = true) ||
                        flag == "1"
                }
                return row.CatalogName == DorisCatalogScopes.INTERNAL_CATALOG
            }
        }
    }

    /** Level 2: for a catalog, list its Doris databases as SCHEMA nodes via `SHOW DATABASES FROM <c>`. */
    override fun createDatabaseRetriever(
        transaction: DBTransaction,
        database: MsDatabase,
    ): BaseDatabaseRetriever<MsDatabase> {
        return object : BaseDatabaseRetriever<MsDatabase>(transaction, database) {
            override fun retrieveSchemas() {
                val catalog = database.name
                try {
                    val names = runCatalogScopedOrFallback(
                        transaction, catalog, "SHOW DATABASES",
                        primary = { it.query(DorisCatalogQueries.listDatabasesIn(catalog)).run() },
                        fallback = { it.query(DorisCatalogQueries.LIST_DATABASES_CURRENT).run() },
                    ).orEmpty()
                    DorisCatalogs.info("catalog '$catalog' databases -> ${names.toList()}")
                    for (name in names) {
                        if (name.isNullOrBlank()) continue
                        database.schemas.createOrGet(name)
                    }
                } catch (t: Throwable) {
                    DorisCatalogs.warn("catalog '$catalog' schema listing failed; skipping catalog", t)
                }
            }
        }
    }

    /** Level 3: for a Doris database, populate tables/views + columns from `<catalog>.information_schema`. */
    override fun createSchemaRetriever(
        transaction: DBTransaction,
        schema: MsSchema,
    ): AbstractSchemaRetriever<MsSchema> {
        return object : AbstractSchemaRetriever<MsSchema>(transaction, schema) {
            override fun isPossibleToIntrospectSchemaIncrementally(
                tran: DBTransaction,
                s: MsSchema,
                level: com.intellij.database.model.properties.Level,
            ): Boolean = false

            override fun process() {
                retrieveSchemaObjects(transaction, schema)
            }
        }
    }

    /**
     * M5 item 1: the **portion** (level-one) retriever the platform requests when only a *subset*
     * of schemas needs introspection — e.g. the schemas pane / console switcher triggering
     * "Introspect the Portion of N schemas (full) on level 1". The [BaseNativeIntrospector] default
     * throws "The introspector ... doesn't support the requested retriever"
     * (`thisRetrieverIsNotSupported`, the SEVERE from the user's runtime pass); this is the **only**
     * retriever-factory seam with a not-supported default — `MsIntrospector` fills the same seam
     * with `MsLevelOneRetriever : BaseDatabaseSchemasRetriever`.
     *
     * Subset semantics: introspects **exactly** the portion's schemas, via the same stateless
     * per-catalog queries as the deep path. Schemas outside the portion are not touched; the
     * databases (catalog) level is never reset (no family-wide `markChildrenAsSyncPending`/`clear`).
     * Per-schema failures log + skip that schema only. Our "level one" is the full
     * table/view/column retrieve — the only surface defined so far; if the platform later asks for
     * a deeper level on the same schema, the retrieval is idempotent (`createOrGet`).
     */
    override fun createLevelOneRetrieverForPortion(
        transaction: DBTransaction,
        portion: SchemaPortion<out MsDatabase, out MsSchema>,
    ): AbstractDatabaseSchemasRetriever<out MsDatabase, out MsSchema> {
        return object : BaseDatabaseSchemasRetriever<MsDatabase, MsSchema>(
            transaction,
            portion.database,
            portion.schemas,
        ) {
            override fun process() {
                DorisCatalogs.info(
                    "portion introspection: ${portion.schemas.size} schema(s) of catalog " +
                        "'${portion.database.name}' (mode ${portion.mode})",
                )
                for (schema in schemas) {
                    retrieveSchemaObjects(transaction, schema)
                }
            }
        }
    }

    /**
     * Retrieves one Doris database's tables/views/columns — including column stored types and
     * positions (M5 item 2) — via the stateless catalog-qualified queries. Shared by the deep
     * schema retriever and the portion retriever. A failure is logged with the `DorisCatalogs:`
     * prefix and skips only this schema.
     */
    private fun retrieveSchemaObjects(transaction: DBTransaction, schema: MsSchema) {
        val catalog = schema.database?.name ?: return
        val schemaName = schema.name
        try {
            val tables = runCatalogScopedOrFallback(
                transaction, catalog, "information_schema.tables",
                primary = {
                    it.query(DorisCatalogQueries.listTablesIn(catalog)).withParams(schemaName).run()
                },
                fallback = {
                    it.query(DorisCatalogQueries.LIST_TABLES_CURRENT).withParams(schemaName).run()
                },
            ).orEmpty()
            val columnsByTable = runCatalogScopedOrFallback(
                transaction, catalog, "information_schema.columns",
                primary = {
                    it.query(DorisCatalogQueries.listColumnsIn(catalog)).withParams(schemaName).run()
                },
                fallback = {
                    it.query(DorisCatalogQueries.LIST_COLUMNS_CURRENT).withParams(schemaName).run()
                },
            ).orEmpty().groupBy { it.TABLE_NAME }

            var tableCount = 0
            var viewCount = 0
            for (t in tables) {
                val name = t.TABLE_NAME ?: continue
                if (DorisCatalogQueries.isViewType(t.TABLE_TYPE)) {
                    schema.views.createOrGet(name)
                    viewCount++
                } else {
                    val table = schema.tables.createOrGet(name)
                    for (col in columnsByTable[name].orEmpty()) {
                        val colName = col.COLUMN_NAME ?: continue
                        val column = table.columns.createOrGet(colName)
                        // M5 item 2: stored type from COLUMN_TYPE (full spec) / DATA_TYPE via the
                        // platform's lenient factory; Doris exotics and hive-side strings stay
                        // unresolved-but-named (UI shows 'variant' etc.), never throw.
                        DorisCatalogQueries.columnDasType(col.DATA_TYPE, col.COLUMN_TYPE)
                            ?.let { dasType -> column.setStoredType(dasType) }
                        val pos = col.ORDINAL_POSITION
                        if (pos in 1..Short.MAX_VALUE.toLong()) {
                            column.setPosition(pos.toShort())
                        }
                    }
                    tableCount++
                }
            }
            DorisCatalogs.info(
                "catalog '$catalog' db '$schemaName' -> $tableCount tables, $viewCount views",
            )
        } catch (t: Throwable) {
            DorisCatalogs.warn("catalog '$catalog' db '$schemaName' object listing failed; skipping", t)
        }
    }

    /**
     * Stateless-first execution: run the catalog-qualified [primary] query; if it throws (older
     * Doris without `SHOW DATABASES FROM` / `<catalog>.information_schema` support), log a
     * `DorisCatalogs:` warning and retry with `SWITCH <catalog>` + the unqualified [fallback].
     * Only this fallback path mutates connection session state (the current catalog); the primary
     * path never does. A failure of the fallback itself propagates to the per-catalog/per-schema
     * catch, which logs and skips just that catalog/schema.
     */
    private fun <T> runCatalogScopedOrFallback(
        transaction: DBTransaction,
        catalog: String,
        what: String,
        primary: (DBTransaction) -> T,
        fallback: (DBTransaction) -> T,
    ): T {
        return try {
            primary(transaction)
        } catch (t: Throwable) {
            DorisCatalogs.warn(
                "catalog '$catalog': qualified $what query failed; falling back to SWITCH + unqualified " +
                    "(older Doris?)",
                t,
            )
            transaction.command(DorisCatalogQueries.switchCatalog(catalog)).run()
            fallback(transaction)
        }
    }

    /**
     * The introspector's declared capabilities. Server-wide objects (users/roles) and fragment
     * (incremental) introspection are out of M1 scope; level-by-level (catalog -> database ->
     * objects) is exactly the flow above.
     */
    private object DorisNature : BaseIntrospector.Nature {
        override val supportServerObjects: Boolean = false
        override val supportLevelByLevelIntrospection: Boolean = true
        override val supportFragmentIntrospection: Boolean = false
        override val supportFragmentKinds: Set<ObjectKind> = emptySet()
    }

    /**
     * `<introspector dbms="DORIS">` factory. **Dual-mode**: flag-off it produces
     * [DorisSingleDatabaseIntrospector] — the stock `MysqlBaseIntrospector` behaviour with exactly
     * one deliberate change, the M2 default-scope fix for GitHub issue #5 — and delegates all
     * capability answers to the stock `MysqlBaseIntrospector.Factory`; flag-on it produces
     * [DorisIntrospector] and advertises multilevel introspection.
     */
    class DorisIntrospectorFactory private constructor(
        private val mysql: MsqlFactory,
        private val dbms: Dbms,
    ) : DBIntrospector.Factory by mysql {

        constructor(dbms: Dbms) : this(MsqlFactory(dbms), dbms)

        override fun createIntrospector(
            context: DBIntrospectionContext,
            dbms: Dbms,
            modelFactory: ModelFactory,
        ): DBIntrospector {
            return if (DorisCatalogs.enabled) {
                DorisIntrospector(context, dbms, modelFactory)
            } else {
                DorisSingleDatabaseIntrospector(context, dbms, modelFactory)
            }
        }

        override val supportsMultilevelIntrospection: Boolean
            get() = DorisCatalogs.enabled || mysql.supportsMultilevelIntrospection

        override fun isIncremental(): Boolean =
            if (DorisCatalogs.enabled) false else mysql.isIncremental()
    }
}

/**
 * Flag-OFF introspector (Gate 1 / M2, GitHub issue #5): the stock single-database
 * [MysqlBaseIntrospector] with **only** [getDefaultScope] overridden.
 *
 * A fresh Doris data source used to default to *no* introspection scope selection: the inherited
 * default is the `@` (current-namespace) pattern, which matches only a schema flagged
 * `isCurrent()` — and Doris connections frequently end up with none (no current database reported
 * over the MySQL protocol). Genuine MySQL data sources resolve `@` fine, hence the parity gap. The
 * override keeps `@` (exact MySQL parity when it works) and **adds the connection's reported
 * current database by name** ([getCurrentDatabase], jdba `ConnectionInfo.databaseName`) when there
 * is one, so the default resolves even when the `isCurrent` flag never lands.
 *
 * Deliberately a **subclass** (not a delegating wrapper): platform code such as
 * `MysqlIntrospectorStatsProvider` does `instanceof MysqlBaseIntrospector` checks that must keep
 * matching flag-off; a wrapper would silently disable them. Everything except the default scope is
 * inherited unchanged.
 */
class DorisSingleDatabaseIntrospector(
    context: DBIntrospectionContext,
    dbms: Dbms,
    modelFactory: ModelFactory,
) : MysqlBaseIntrospector<MysqlBaseRoot, MysqlBaseSchema>(context, dbms, modelFactory) {

    override fun getDefaultScope(): TreePattern {
        val current = try {
            getCurrentDatabase()
        } catch (t: Throwable) {
            DorisCatalogs.warn("could not determine current database for default scope", t)
            null
        }
        DorisCatalogs.info("supplying flag-off default introspection scope (current database: $current)")
        return DorisCatalogScopes.singleDatabaseDefaultScope(super.getDefaultScope(), current)
    }
}

/**
 * Alias for the stock MySQL introspector factory we delegate to flag-off. Kept as a named type so
 * the delegation `by mysql` in [DorisIntrospector.DorisIntrospectorFactory] reads clearly and so the
 * MySQL dependency is localized to one import.
 */
private typealias MsqlFactory =
    com.intellij.database.dialects.mysqlbase.introspector.MysqlBaseIntrospector.Factory
