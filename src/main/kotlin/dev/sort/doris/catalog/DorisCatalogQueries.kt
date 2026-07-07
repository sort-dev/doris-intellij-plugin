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
 * ## Row structs and jdba `structOf`
 *
 * `Layouts.structOf(C::class.java)` maps each result column to the public field of `C` with the same
 * name. Doris `SHOW CATALOGS` emits `CatalogId, CatalogName, Type, IsCurrent, ...`; only the two we
 * name below are bound, the rest ignored. Fields are plain public JVM fields (`@JvmField`).
 */
object DorisCatalogQueries {

    /** One row of `SHOW CATALOGS`. Field names match Doris's column labels. */
    class CatalogRow {
        @JvmField var CatalogId: Long = 0
        @JvmField var CatalogName: String? = null
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

    /** `SHOW CATALOGS` -> one [CatalogRow] per Doris catalog (`internal` + externals). */
    val LIST_CATALOGS: SqlQuery<List<CatalogRow>> =
        SqlQuery("SHOW CATALOGS", Layouts.listOf(Layouts.structOf(CatalogRow::class.java)))

    /** `SHOW DATABASES` (current catalog after a SWITCH) -> one database name per row. */
    val LIST_DATABASES: SqlQuery<Array<String>> =
        SqlQuery("SHOW DATABASES", Layouts.columnOf(String::class.java))

    /**
     * Tables + views of one Doris database in the current catalog. `?` is bound to the database
     * (schema) name via `withParams(schemaName)`.
     */
    val LIST_TABLES: SqlQuery<List<TableRow>> = SqlQuery(
        "SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA = ?",
        Layouts.listOf(Layouts.structOf(TableRow::class.java)),
    )

    /** Columns of every table of one Doris database in the current catalog. `?` = schema name. */
    val LIST_COLUMNS: SqlQuery<List<ColumnRow>> = SqlQuery(
        "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, ORDINAL_POSITION " +
            "FROM information_schema.columns WHERE TABLE_SCHEMA = ? " +
            "ORDER BY TABLE_NAME, ORDINAL_POSITION",
        Layouts.listOf(Layouts.structOf(ColumnRow::class.java)),
    )

    /** `SWITCH <catalog>` — makes `catalog`'s databases the target of subsequent `information_schema`. */
    fun switchCatalog(catalogName: String): String =
        "SWITCH " + DorisStringUtils.quoteIdentifier(catalogName)

    /** Doris `information_schema.tables.TABLE_TYPE` value that denotes a view (vs. `BASE TABLE`). */
    fun isViewType(tableType: String?): Boolean =
        tableType != null && tableType.equals("VIEW", ignoreCase = true)
}
