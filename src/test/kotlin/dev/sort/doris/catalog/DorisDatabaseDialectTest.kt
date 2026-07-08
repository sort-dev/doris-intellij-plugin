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

    override fun tearDown() {
        try {
            System.clearProperty(dev.sort.doris.DorisCatalogs.PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    /** JVM-global pin (classloader-proof; the DorisReplayPocTest property pattern). */
    private fun <T> withFlag(value: Boolean, block: () -> T): T {
        System.setProperty(dev.sort.doris.DorisCatalogs.PROPERTY, value.toString())
        try {
            return block()
        } finally {
            System.clearProperty(dev.sort.doris.DorisCatalogs.PROPERTY)
        }
    }

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

    fun testFlagOffDialectMatchesMysql() = withFlag(false) {
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

    fun testReadBackPathMapping() {
        // M6: the console read-back must produce a TWO-LEVEL path that binds to the multi-catalog
        // model (MySQL's single-level `select database()` path is the observed console-reset root).
        val full = DorisCatalogQueries.currentSearchPath("extcat", "somedb").getCurrent()!!
        assertEquals(ObjectKind.SCHEMA, full.kind)
        assertEquals("somedb", full.name)
        assertEquals(ObjectKind.DATABASE, full.parent!!.kind)
        assertEquals("extcat", full.parent!!.name)

        // No current database -> catalog-only DATABASE path.
        val catalogOnly = DorisCatalogQueries.currentSearchPath("extcat", null).getCurrent()!!
        assertEquals(ObjectKind.DATABASE, catalogOnly.kind)
        assertEquals("extcat", catalogOnly.name)
        assertNull(catalogOnly.parent)

        // Catalog unknown -> assume the connect-time default so the path still binds.
        val assumed = DorisCatalogQueries.currentSearchPath(null, "somedb").getCurrent()!!
        assertEquals(ObjectKind.SCHEMA, assumed.kind)
        assertEquals(DorisCatalogScopes.INTERNAL_CATALOG, assumed.parent!!.name)
        assertEquals(
            DorisCatalogScopes.INTERNAL_CATALOG,
            DorisCatalogQueries.currentSearchPath("  ", null).getCurrent()!!.name,
        )
    }

    fun testReadBackProbeSql() {
        assertEquals("select current_catalog()", DorisCatalogQueries.SELECT_CURRENT_CATALOG)
        assertEquals("select database()", DorisCatalogQueries.SELECT_CURRENT_DATABASE)
    }

    fun testSearchPathKindStaysSchemaForSteppedPopup() {
        // M6 correction of M4: ChooseSchemaAction.createInitialStep engages the stepped DbScStep
        // popup only when kind != DATABASE AND the dialect composes a DATABASE-kind switch
        // (bytecode: if_acmpeq DATABASE jumps AWAY from DbScStep). Both inputs must hold here:
        // kind is SCHEMA in both modes; the composer's DATABASE-kind answer is non-null.
        val doris = DorisDatabaseDialect(DorisDbms.DORIS)
        // Kind must be SCHEMA in BOTH modes (M6 correction; M10 pins each mode explicitly).
        withFlag(true) { assertEquals(ObjectKind.SCHEMA, doris.searchPathObjectKind) }
        withFlag(false) { assertEquals(ObjectKind.SCHEMA, doris.searchPathObjectKind) }
        assertEquals(
            "SWITCH `test`",
            DorisCatalogQueries.sqlSwitchSearchPath(ObjectPath.create("test", ObjectKind.DATABASE)),
        )
    }
}
