package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dialects.mysql.MysqlDialect
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

/**
 * M4 offline assertions: the console namespace-switcher SQL composition and the dual-mode
 * database-dialect wiring. The flag is OFF in the test JVM, so the flag-off equivalence assertions
 * run against the live default; the flag-ON composer is a pure function and is asserted directly.
 */
class DorisDatabaseDialectTest : BasePlatformTestCase() {

    fun testSwitchSqlForCatalogQualifiedSchema() {
        // BUG A regression: the whole point — each path part quoted SEPARATELY.
        val path = ObjectPath.create("extcat", ObjectKind.DATABASE).append("somedb", ObjectKind.SCHEMA)
        assertEquals("use `extcat`.`somedb`", DorisCatalogQueries.sqlSwitchSearchPath(path))

        val internal = ObjectPath.create("internal", ObjectKind.DATABASE).append("acme_dwh", ObjectKind.SCHEMA)
        assertEquals("use `internal`.`acme_dwh`", DorisCatalogQueries.sqlSwitchSearchPath(internal))

        // Embedded backticks must be doubled per part, never fused across the dot.
        val weird = ObjectPath.create("we`ird", ObjectKind.DATABASE).append("db`x", ObjectKind.SCHEMA)
        assertEquals("use `we``ird`.`db``x`", DorisCatalogQueries.sqlSwitchSearchPath(weird))
    }

    fun testSwitchSqlForBareSchemaAndCatalog() {
        // Single-component schema: MySQL-identical shape.
        val schemaOnly = ObjectPath.create("somedb", ObjectKind.SCHEMA)
        assertEquals("use `somedb`", DorisCatalogQueries.sqlSwitchSearchPath(schemaOnly))

        // Catalog-only selection: Doris cannot USE a bare catalog — SWITCH it.
        val catalogOnly = ObjectPath.create("extcat", ObjectKind.DATABASE)
        assertEquals("SWITCH `extcat`", DorisCatalogQueries.sqlSwitchSearchPath(catalogOnly))

        // Non-namespace kinds are not composable.
        assertNull(DorisCatalogQueries.sqlSwitchSearchPath(ObjectPath.create("t", ObjectKind.TABLE)))
    }

    fun testFlagOffDialectMatchesMysql() {
        val doris = DorisDatabaseDialect(DorisDbms.DORIS)
        val mysql = MysqlDialect(Dbms.MYSQL)

        // Flag-off (default in tests): same popup mode and same composed switch SQL as MySQL.
        assertEquals(mysql.searchPathObjectKind, doris.searchPathObjectKind)
        assertEquals("MySQL", doris.displayName)

        val path = SearchPath.of(ObjectPath.create("somedb", ObjectKind.SCHEMA))
        assertEquals(mysql.sqlSetSearchPath(path), doris.sqlSetSearchPath(path))

        // And the injected dbms is preserved (the EP instantiates with DORIS).
        assertEquals(DorisDbms.DORIS, doris.dbms)
    }
}
