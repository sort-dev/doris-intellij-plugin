package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dialects.mysqlbase.MysqlBaseDialect
import com.intellij.database.model.ObjectKind
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.DbImplUtilCore
import com.intellij.database.util.SearchPath
import dev.sort.doris.DorisCatalogs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `<dialect dbms="DORIS">` — the dbms-keyed **database dialect** (Gate 1 / M4, revised in M6).
 *
 * ## The console machinery this class serves (bytecode findings)
 *
 * The console obtains this dialect via `DbImplUtil.getDatabaseDialect(console)` (dbms-keyed
 * `<dialect>` EP; DORIS previously fell back to the final `mysql.MysqlDialect`) and drives its
 * session context through three seams:
 *
 * 1. **`sqlSetSearchPath(SearchPath)`** — composes the switch statement when the user picks a
 *    namespace (`ChooseSchemaAction.switchSearchPath`). MySQL's implementation quotes the whole
 *    dotted path as ONE identifier (``use `catalog.schema` `` — Doris rejects it; M4 BUG A).
 * 2. **`tryToLoadSearchPath(connection)`** — the **read-back**: `JdbcEngine` re-reads the current
 *    namespace after execution. MySQL's implementation runs `select database()` and returns a
 *    **single-level** SCHEMA path, which cannot bind to the two-level multi-catalog model — the
 *    console's context unbinds after every statement (dropdown resets to `<database>`, completion
 *    loses its scope; the M6 regression).
 * 3. **`getSearchPathObjectKind()` + `sqlSetSearchPath(DATABASE-kind probe)`** — pick the switcher
 *    popup in `ChooseSchemaAction.createInitialStep`. Exact branch (M6-corrected bytecode reading;
 *    M4 had it inverted): the stepped catalog→database popup (`DbScStep`) engages only when
 *    `getSearchPathObjectKind() != DATABASE` **and** (`JdbcUrlParserUtil.isDatabaseBounded(target)`
 *    **or** `sqlSetSearchPath(SearchPath.of(ObjectPath("test", DATABASE))) != null`). Kind
 *    `DATABASE` jumps straight to `supportsSearchPath()` (false for MySQL-family) and lands on the
 *    flat `SingleScOrDbStep` — which is why M4's kind override produced the flat mixed list the
 *    user saw. Therefore the kind stays **SCHEMA** (MySQL's default) in both modes, and the
 *    stepped popup engages because our composer answers the DATABASE-kind probe with
 *    ``SWITCH `test` ``.
 *
 * ## Dual-mode safety
 *
 * Static XML registration, live flag-OFF too. The fallback `MysqlDialect` is `final`, but its only
 * additions over the public [MysqlBaseDialect] base are `getDbms()`/`getDisplayName()` —
 * replicated — so flag-OFF behaviour of every inherited method is byte-identical (pinned by
 * `DorisDatabaseDialectTest`). No platform code casts to the final `MysqlDialect` class
 * (bytecode-audited). The SQL-editor side (`DorisSqlDialect.getDatabaseDialect()`) keeps returning
 * the MYSQL-keyed instance and is untouched.
 */
class DorisDatabaseDialect(private val dbms: Dbms) : MysqlBaseDialect() {

    private val popupBranchLogged = AtomicBoolean()

    override fun getDbms(): Dbms = dbms

    // Flag-off fidelity: the fallback MysqlDialect displays "MySQL"; keep that unless the
    // experimental model is active (where "Doris" is more honest in dialect-name surfaces).
    override fun getDisplayName(): String = if (DorisCatalogs.enabled) "Doris" else "MySQL"

    /**
     * **SCHEMA in both modes** (MySQL's default). M6 correction: returning DATABASE (as M4 did)
     * routes `ChooseSchemaAction.createInitialStep` AWAY from the stepped `DbScStep` popup onto the
     * flat `SingleScOrDbStep` — the observed flat mixed list. With SCHEMA, the stepped popup
     * engages because [sqlSetSearchPath] answers the DATABASE-kind probe. Logged once per session
     * so the runtime trail shows which branch inputs the platform saw.
     */
    override fun getSearchPathObjectKind(): ObjectKind {
        val kind = super.getSearchPathObjectKind()!!
        if (DorisCatalogs.enabled && popupBranchLogged.compareAndSet(false, true)) {
            DorisCatalogs.info(
                "switcher popup inputs: searchPathObjectKind=$kind, " +
                    "DATABASE-probe sql=${DorisCatalogQueries.sqlSwitchSearchPath(
                        com.intellij.database.util.ObjectPath.create("test", ObjectKind.DATABASE),
                    )} (kind!=DATABASE + non-null probe => stepped DbScStep popup)",
            )
        }
        return kind
    }

    /**
     * Flag-ON: per-part-quoted Doris switch SQL (M4 BUG A) — see
     * [DorisCatalogQueries.sqlSwitchSearchPath]. Also answers the DATABASE-kind popup probe with
     * ``SWITCH `...` ``, which is what engages the stepped popup (see class KDoc §3).
     * Flag-OFF: MySQL's composer, unchanged.
     */
    override fun sqlSetSearchPath(path: SearchPath): String? {
        if (!DorisCatalogs.enabled) return super.sqlSetSearchPath(path)
        val current = path.getCurrent() ?: return null
        val sql = DorisCatalogQueries.sqlSwitchSearchPath(current)
        DorisCatalogs.info("console switch to '${current.getDisplayName()}' -> $sql")
        return sql
    }

    /**
     * Flag-ON: the search-path **read-back** (M6). `JdbcEngine` calls this after execution to
     * re-sync the console's current namespace; it must return a **two-level** path
     * (`DATABASE(catalog) -> SCHEMA(db)`) or the console context unbinds against the multi-catalog
     * model. Sources, defensively chained:
     *
     * 1. `select current_catalog()` (Doris built-in) + `select database()`;
     * 2. if the catalog probe fails (older Doris without the function): `SHOW CATALOGS` `IsCurrent`;
     * 3. if that fails too: assume `internal` (connect-time default) so the path still binds.
     *
     * Results and the produced path are logged with the `DorisCatalogs:` prefix.
     * Flag-OFF: MySQL's `select database()` single-level read-back, unchanged.
     */
    @Throws(java.sql.SQLException::class)
    override fun tryToLoadSearchPath(connection: DatabaseConnectionCore): SearchPath? {
        if (!DorisCatalogs.enabled) return super.tryToLoadSearchPath(connection)

        val database = queryStringOrNull(connection, DorisCatalogQueries.SELECT_CURRENT_DATABASE)
        var catalogSource = "current_catalog()"
        var catalog = queryStringOrNull(connection, DorisCatalogQueries.SELECT_CURRENT_CATALOG)
        if (catalog.isNullOrBlank()) {
            catalogSource = "SHOW CATALOGS IsCurrent"
            catalog = currentCatalogFromShowCatalogs(connection)
        }
        if (catalog.isNullOrBlank()) {
            catalogSource = "assumed default"
        }
        val path = DorisCatalogQueries.currentSearchPath(catalog, database)
        DorisCatalogs.info(
            "search-path read-back: catalog=$catalog (via $catalogSource), database=$database " +
                "-> ${path.getCurrent()?.getDisplayName()}",
        )
        return path
    }

    /**
     * Runs a single-value query via the platform's own helper (the exact shape MySQL's read-back
     * uses: `concatStringResults(connection, connection.dbms, sql, 1, NO_CONCAT)`); null (logged)
     * on any failure.
     */
    private fun queryStringOrNull(connection: DatabaseConnectionCore, sql: String): String? {
        return try {
            DbImplUtilCore.concatStringResults(
                connection, connection.dbms, sql, 1, DbImplUtilCore.ConcatenationProps.NO_CONCAT,
            )?.takeUnless { it.isBlank() }
        } catch (t: Throwable) {
            DorisCatalogs.info("read-back probe '$sql' failed (${t.message}); falling back")
            null
        }
    }

    /** Fallback catalog probe: the `IsCurrent` column of `SHOW CATALOGS` (works on older Doris). */
    private fun currentCatalogFromShowCatalogs(connection: DatabaseConnectionCore): String? {
        return try {
            val statement = JdbcNativeUtil.computeRemote {
                connection.remoteConnection.createStatement()
            } ?: return null
            try {
                val resultSet = JdbcNativeUtil.computeRemote {
                    statement.executeQuery("SHOW CATALOGS")
                } ?: return null
                try {
                    val metadata = resultSet.metaData
                    var nameIndex = -1
                    var currentIndex = -1
                    for (i in 1..metadata.columnCount) {
                        val label = metadata.getColumnLabel(i).orEmpty()
                        when {
                            label.equals("CatalogName", ignoreCase = true) -> nameIndex = i
                            label.equals("IsCurrent", ignoreCase = true) -> currentIndex = i
                        }
                    }
                    if (nameIndex < 0 || currentIndex < 0) return null
                    while (resultSet.next()) {
                        val flag = resultSet.getString(currentIndex) ?: continue
                        if (flag.equals("yes", true) || flag.equals("true", true) || flag == "1") {
                            return resultSet.getString(nameIndex)
                        }
                    }
                    null
                } finally {
                    JdbcNativeUtil.performSafe { resultSet.close() }
                }
            } finally {
                JdbcNativeUtil.closeRemoteStatementSafe(statement)
            }
        } catch (t: Throwable) {
            DorisCatalogs.warn("SHOW CATALOGS read-back fallback failed", t)
            null
        }
    }
}
