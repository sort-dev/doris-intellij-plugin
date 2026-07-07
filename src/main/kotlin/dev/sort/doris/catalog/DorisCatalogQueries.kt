package dev.sort.doris.catalog

import com.intellij.database.remote.jdba.core.Layouts
import com.intellij.database.remote.jdba.core.ResultLayout
import com.intellij.database.remote.jdba.sql.SqlQuery
import dev.sort.doris.DorisStringUtils

/**
 * All SQL the experimental multi-catalog introspector runs over the MySQL protocol, plus the jdba
 * [ResultLayout]s that decode each result. Kept as a pure object with **no platform state** so the
 * query text and row shapes are unit-testable offline ([dev.sort.doris.catalog] tests) — the parts
 * that need a live Doris (does the server actually answer `SHOW CATALOGS`?) are exactly the parts we
 * cannot assert here.
 *
 * ## Stateless-first design (Gate 1 design revision, review feedback)
 *
 * The **primary** per-catalog queries are *catalog-qualified and stateless*:
 * `SHOW DATABASES FROM <catalog>` and `SELECT ... FROM <catalog>.information_schema.*`. They never
 * mutate connection session state, so they are safe on pooled/shared/keep-alive connections, impose
 * no per-catalog phase ordering, halve round trips (no `SWITCH` before each read), and isolate
 * per-catalog failures naturally.
 *
 * `SWITCH <catalog>` + the unqualified `*_CURRENT` forms are retained **only as a documented
 * fallback** for older Doris versions where the qualified forms fail; see [DorisIntrospector] for
 * the fail-then-fall-back flow. Catalog names are backtick-quoted identifiers (they can need
 * quoting), and identifiers cannot be JDBC `?` parameters, so the qualified query text is built per
 * catalog.
 *
 * ## Row structs and jdba `structOf`
 *
 * `Layouts.structOf(C::class.java)` maps each result column to the public field of `C` with the same
 * name. Doris `SHOW CATALOGS` emits `CatalogId, CatalogName, Type, IsCurrent, ...`; only the fields
 * we name below are bound, the rest ignored. Fields are plain public JVM fields (`@JvmField`).
 */
object DorisCatalogQueries {

    /** One row of `SHOW CATALOGS`. Field names match Doris's column labels. */
    class CatalogRow {
        @JvmField var CatalogId: Long = 0
        @JvmField var CatalogName: String? = null

        /**
         * Doris reports the session's current catalog in an `IsCurrent` column (`Yes`/`No`). Older
         * builds may lack the column; jdba then leaves this null and the introspector falls back to
         * treating `internal` as the current catalog.
         */
        @JvmField var IsCurrent: String? = null
    }

    /** One row of the per-schema `information_schema.tables` scan. */
    class TableRow {
        @JvmField var TABLE_NAME: String? = null
        @JvmField var TABLE_TYPE: String? = null
    }

    /** One row of the per-schema `information_schema.columns` scan. */
    class ColumnRow {
        @JvmField var TABLE_NAME: String? = null
        @JvmField var COLUMN_NAME: String? = null
        @JvmField var DATA_TYPE: String? = null
        @JvmField var ORDINAL_POSITION: Long = 0
    }

    private val DATABASES_LAYOUT: ResultLayout<Array<String>> = Layouts.columnOf(String::class.java)
    private val TABLES_LAYOUT: ResultLayout<List<TableRow>> =
        Layouts.listOf(Layouts.structOf(TableRow::class.java))
    private val COLUMNS_LAYOUT: ResultLayout<List<ColumnRow>> =
        Layouts.listOf(Layouts.structOf(ColumnRow::class.java))

    private const val TABLES_SELECT = "SELECT TABLE_NAME, TABLE_TYPE FROM "
    private const val TABLES_WHERE = ".tables WHERE TABLE_SCHEMA = ?"
    private const val COLUMNS_SELECT =
        "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, ORDINAL_POSITION FROM "
    private const val COLUMNS_WHERE =
        ".columns WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME, ORDINAL_POSITION"

    /** `SHOW CATALOGS` -> one [CatalogRow] per Doris catalog (`internal` + externals). Stateless. */
    val LIST_CATALOGS: SqlQuery<List<CatalogRow>> =
        SqlQuery("SHOW CATALOGS", Layouts.listOf(Layouts.structOf(CatalogRow::class.java)))

    // ---- Primary path: stateless, catalog-qualified -----------------------------------------------

    /** `SHOW DATABASES FROM <catalog>` -> one database name per row. Stateless (no SWITCH). */
    fun listDatabasesIn(catalogName: String): SqlQuery<Array<String>> =
        SqlQuery("SHOW DATABASES FROM " + quote(catalogName), DATABASES_LAYOUT)

    /**
     * Tables + views of one Doris database, read from the **catalog-qualified**
     * `<catalog>.information_schema.tables`. `?` is bound to the database (schema) name via
     * `withParams(schemaName)`. Stateless.
     */
    fun listTablesIn(catalogName: String): SqlQuery<List<TableRow>> =
        SqlQuery(TABLES_SELECT + qualifiedInformationSchema(catalogName) + TABLES_WHERE, TABLES_LAYOUT)

    /** Columns of every table of one Doris database, catalog-qualified. `?` = schema name. Stateless. */
    fun listColumnsIn(catalogName: String): SqlQuery<List<ColumnRow>> =
        SqlQuery(COLUMNS_SELECT + qualifiedInformationSchema(catalogName) + COLUMNS_WHERE, COLUMNS_LAYOUT)

    // ---- Fallback path: SWITCH + unqualified (older Doris without qualified-form support) ----------

    /**
     * `SWITCH <catalog>` — **fallback only**. Mutates connection session state (the current
     * catalog), which is exactly why the primary path avoids it: on pooled/shared/keep-alive
     * connections the mutated state leaks across the connection's users and forces per-catalog
     * phase ordering. Used only when the qualified forms fail for a catalog.
     */
    fun switchCatalog(catalogName: String): String = "SWITCH " + quote(catalogName)

    /** Fallback for [listDatabasesIn]: `SHOW DATABASES` against the SWITCHed current catalog. */
    val LIST_DATABASES_CURRENT: SqlQuery<Array<String>> =
        SqlQuery("SHOW DATABASES", DATABASES_LAYOUT)

    /** Fallback for [listTablesIn]: unqualified `information_schema` after `SWITCH`. `?` = schema. */
    val LIST_TABLES_CURRENT: SqlQuery<List<TableRow>> =
        SqlQuery(TABLES_SELECT + "information_schema" + TABLES_WHERE, TABLES_LAYOUT)

    /** Fallback for [listColumnsIn]: unqualified `information_schema` after `SWITCH`. `?` = schema. */
    val LIST_COLUMNS_CURRENT: SqlQuery<List<ColumnRow>> =
        SqlQuery(COLUMNS_SELECT + "information_schema" + COLUMNS_WHERE, COLUMNS_LAYOUT)

    // ------------------------------------------------------------------------------------------------

    /** Doris `information_schema.tables.TABLE_TYPE` value that denotes a view (vs. `BASE TABLE`). */
    fun isViewType(tableType: String?): Boolean =
        tableType != null && tableType.equals("VIEW", ignoreCase = true)

    /** `<catalog>.information_schema`, catalog backtick-quoted. */
    private fun qualifiedInformationSchema(catalogName: String): String =
        quote(catalogName) + ".information_schema"

    private fun quote(identifier: String): String = DorisStringUtils.quoteIdentifier(identifier)
}
