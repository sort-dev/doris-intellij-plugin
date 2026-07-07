package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.base.introspector.BaseIntrospector
import com.intellij.database.dialects.base.introspector.BaseMultiDatabaseIntrospector
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mssql.model.MsSchema
import com.intellij.database.introspection.DBIntrospectionContext
import com.intellij.database.introspection.DBIntrospector
import com.intellij.database.layoutedQueries.DBTransaction
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.families.ModNamingFamily
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
 * | SCHEMA         | database        | [createDatabaseRetriever] via `SWITCH` + `SHOW DATABASES` |
 * | TABLE / VIEW   | table / view    | [createSchemaRetriever] via `information_schema.tables` |
 * | COLUMN         | column          | [createSchemaRetriever] via `information_schema.columns` |
 *
 * This mirrors SQL Server's own rhythm — **one JDBC connection, switch into each database to
 * introspect it** — which is mechanically what Doris `SWITCH <catalog>` does over the MySQL
 * protocol. All queries go through the platform's layouted-query facade ([DBTransaction.query] /
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
 * Doris — that `SHOW CATALOGS` column labels bind, that `information_schema` reflects the switched
 * catalog, that the framework's level bookkeeping accepts eager `createOrGet` population — cannot be
 * asserted from this environment and is the subject of the runtime test script in the Gate 1 log.
 */
class DorisIntrospector(
    context: DBIntrospectionContext,
    dbms: Dbms,
    modelFactory: ModelFactory,
) : BaseMultiDatabaseIntrospector<MsRoot, MsDatabase, MsSchema>(context, DorisNature, dbms, modelFactory) {

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
                DorisCatalogs.info("SHOW CATALOGS -> ${rows.mapNotNull { it.CatalogName }}")
                return rows.filter { it.CatalogName != null }
            }

            override fun applyDatabase(
                family: ModNamingFamily<*>,
                row: DorisCatalogQueries.CatalogRow,
            ): MsDatabase {
                // renew(family, id, name): create-or-refresh the DATABASE node for this catalog.
                return renew(family, row.CatalogId, row.CatalogName!!)
            }
        }
    }

    /** Level 2: for a catalog, list its Doris databases as SCHEMA nodes via `SWITCH` + `SHOW DATABASES`. */
    override fun createDatabaseRetriever(
        transaction: DBTransaction,
        database: MsDatabase,
    ): BaseDatabaseRetriever<MsDatabase> {
        return object : BaseDatabaseRetriever<MsDatabase>(transaction, database) {
            override fun retrieveSchemas() {
                val catalog = database.name
                try {
                    transaction.command(DorisCatalogQueries.switchCatalog(catalog)).run()
                    val names = transaction.query(DorisCatalogQueries.LIST_DATABASES).run().orEmpty()
                    DorisCatalogs.info("catalog '$catalog' SHOW DATABASES -> ${names.toList()}")
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

    /** Level 3: for a Doris database, populate its tables/views + columns from `information_schema`. */
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
                val catalog = schema.database?.name ?: return
                val schemaName = schema.name
                try {
                    transaction.command(DorisCatalogQueries.switchCatalog(catalog)).run()

                    val tables = transaction.query(DorisCatalogQueries.LIST_TABLES)
                        .withParams(schemaName).run().orEmpty()
                    val columnsByTable = transaction.query(DorisCatalogQueries.LIST_COLUMNS)
                        .withParams(schemaName).run().orEmpty()
                        .groupBy { it.TABLE_NAME }

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
                                table.columns.createOrGet(colName)
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
     * `<introspector dbms="DORIS">` factory. **Dual-mode**: flag-off it is a transparent pass-through
     * to the stock `MysqlBaseIntrospector.Factory` (byte-identical to today's `extensionFallback`
     * behaviour — same single-database introspector, same capabilities); flag-on it produces
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
                mysql.createIntrospector(context, dbms, modelFactory)
            }
        }

        override val supportsMultilevelIntrospection: Boolean
            get() = DorisCatalogs.enabled || mysql.supportsMultilevelIntrospection

        override fun isIncremental(): Boolean =
            if (DorisCatalogs.enabled) false else mysql.isIncremental()
    }
}

/**
 * Alias for the stock MySQL introspector factory we delegate to flag-off. Kept as a named type so
 * the delegation `by mysql` in [DorisIntrospector.DorisIntrospectorFactory] reads clearly and so the
 * MySQL dependency is localized to one import.
 */
private typealias MsqlFactory =
    com.intellij.database.dialects.mysqlbase.introspector.MysqlBaseIntrospector.Factory
