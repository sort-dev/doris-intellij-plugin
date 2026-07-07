package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.mysqlbase.MysqlBaseDialect
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.SearchPath
import dev.sort.doris.DorisCatalogs

/**
 * `<dialect dbms="DORIS">` — the dbms-keyed **database dialect** (Gate 1 / M4).
 *
 * ## Why this exists (the console-switcher machinery, bytecode findings)
 *
 * The console's namespace switcher (`ChooseSchemaAction`) obtains the database dialect via
 * `DbImplUtil.getDatabaseDialect(console)` — the dbms-keyed `<dialect>` EP (DORIS resolved to
 * `com.intellij.database.dialects.mysql.MysqlDialect` by `extensionFallback`) — and uses exactly
 * two seams of it:
 *
 * 1. **`sqlSetSearchPath(SearchPath)`** to compose the switch statement.
 *    `MysqlBaseDialect`'s implementation renders `ObjectPath.getDisplayName()` (the full dotted
 *    path — `catalog.schema` under the flag-ON multi-catalog model) and quotes it as **one**
 *    identifier via `NamingService.catToScript`, producing ``use `catalog.schema` `` — which Doris
 *    rejects with "Unknown database" (M4 BUG A).
 * 2. **`getSearchPathObjectKind()`** to pick the popup UI: kind `DATABASE` selects the two-level
 *    stepped popup (`ChooseSchemaAction.DbScStep`, the database→schema chooser SQL Server users
 *    see); kind `SCHEMA` (MySQL's default) selects the flat one-level list — which under the
 *    multi-catalog model showed every catalog's schemas mixed together with no catalog qualifier
 *    (M4 BUG B).
 *
 * Overriding both, flag-ON only, fixes A and B together: the popup groups by catalog, and the
 * composed SQL quotes each path part separately ([DorisCatalogQueries.sqlSwitchSearchPath]).
 *
 * ## Dual-mode safety
 *
 * This registration is static XML, so it is live flag-OFF too. `MysqlDialect` (the class the
 * fallback would have produced) is `final`, but its only additions over the public
 * [MysqlBaseDialect] base are `getDbms()`/`getDisplayName()` — replicated here — so the flag-OFF
 * behaviour of every inherited method is byte-identical to today's. No platform code
 * `instanceof`s/casts to the final `MysqlDialect` class (bytecode-audited), so the class-identity
 * change is safe. The SQL-editor side (`DorisSqlDialect.getDatabaseDialect()`) deliberately keeps
 * returning the MYSQL-keyed dialect instance and is untouched by this DORIS-keyed registration.
 */
class DorisDatabaseDialect(private val dbms: Dbms) : MysqlBaseDialect() {

    override fun getDbms(): Dbms = dbms

    // Flag-off fidelity: the fallback MysqlDialect displays "MySQL"; keep that unless the
    // experimental model is active (where "Doris" is more honest in dialect-name surfaces).
    override fun getDisplayName(): String = if (DorisCatalogs.enabled) "Doris" else "MySQL"

    /**
     * Flag-ON: `DATABASE` — the console switcher shows the two-level catalog→database stepped
     * popup (BUG B). Flag-OFF: MySQL's `SCHEMA` (flat list), unchanged.
     */
    override fun getSearchPathObjectKind(): ObjectKind =
        if (DorisCatalogs.enabled) ObjectKind.DATABASE else super.getSearchPathObjectKind()!!

    /**
     * Flag-ON: per-part-quoted Doris switch SQL (BUG A) — see
     * [DorisCatalogQueries.sqlSwitchSearchPath]. Flag-OFF: MySQL's composer, unchanged.
     */
    override fun sqlSetSearchPath(path: SearchPath): String? {
        if (!DorisCatalogs.enabled) return super.sqlSetSearchPath(path)
        val current = path.getCurrent() ?: return null
        val sql = DorisCatalogQueries.sqlSwitchSearchPath(current)
        DorisCatalogs.info("console switch to '${current.getDisplayName()}' -> $sql")
        return sql
    }
}
