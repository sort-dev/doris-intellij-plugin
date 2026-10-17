package dev.sort.doris.catalog

import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.database.remote.jdbc.RemoteResultSetMetaData
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.database.dialects.mysql.MysqlDialect
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms
import com.intellij.openapi.progress.ProcessCanceledException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.SQLException

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

    fun testNativeSwitchAndUseCommandsMatchTheDorisGrammar() = withFlag(true) {
        val dialect = DorisDatabaseDialect(DorisDbms.DORIS)
        val parser = org.apache.doris.sqlparser.DorisSqlParser()
        for (path in listOf(ObjectPath.create("warehouse", ObjectKind.DATABASE),
            ObjectPath.create("analytics", ObjectKind.SCHEMA),
            ObjectPath.create("ware`house", ObjectKind.DATABASE).append("ana`lytics", ObjectKind.SCHEMA))) {
            parser.parseStatement(checkNotNull(dialect.sqlSetSearchPath(SearchPath.of(path))))
        }
    }

    fun testReadBackUsesOnlyDocumentedDorisQueriesAndClosesThem() = withFlag(true) {
        val queries = ReadBack { sql ->
            when (sql) {
                "select database()" -> listOf(listOf("analytics"))
                "select current_catalog()" -> listOf(listOf("warehouse"))
                else -> error("Unsupported Doris probe: $sql")
            }
        }
        val path = DorisDatabaseDialect(DorisDbms.DORIS).tryToLoadSearchPath(queries.connection)!!.current!!
        assertEquals("warehouse", path.parent!!.name)
        assertEquals("analytics", path.name)
        assertEquals(listOf("select database()", "select current_catalog()"), queries.sql)
        assertEquals(2, queries.closedStatements)
        assertEquals(2, queries.closedResults)
    }

    fun testReadBackFallsBackToShowCatalogsWhenTheFunctionIsUnavailable() = withFlag(true) {
        val queries = ReadBack { sql ->
            when (sql) {
                "select database()" -> listOf(listOf("analytics"))
                "select current_catalog()" -> throw SQLException("Unknown function current_catalog")
                "SHOW CATALOGS" -> listOf(listOf("internal", ""), listOf("warehouse", "yes"))
                else -> error("Unsupported Doris probe: $sql")
            }
        }
        val path = DorisDatabaseDialect(DorisDbms.DORIS).tryToLoadSearchPath(queries.connection)!!.current!!
        assertEquals("warehouse", path.parent!!.name)
        assertEquals("analytics", path.name)
        assertEquals(listOf("select database()", "select current_catalog()", "SHOW CATALOGS"), queries.sql)
        assertEquals(3, queries.closedStatements)
        assertEquals(2, queries.closedResults)
    }

    fun testReadBackPreservesCancellationWithoutFurtherProbes() = withFlag(true) {
        val cancellation = ProcessCanceledException()
        val queries = ReadBack { throw cancellation }
        assertSame(cancellation, runCatching {
            DorisDatabaseDialect(DorisDbms.DORIS).tryToLoadSearchPath(queries.connection)
        }.exceptionOrNull())
        assertEquals(listOf("select database()"), queries.sql)
        assertEquals(1, queries.closedStatements)
    }

    private class ReadBack(val query: (String) -> List<List<String>>) {
        val sql = ArrayList<String>()
        var closedStatements = 0
        var closedResults = 0
        private val metadata = proxy<RemoteResultSetMetaData> { method, args ->
            when (method.name) {
                "getColumnCount" -> 2
                "getColumnLabel" -> listOf("CatalogName", "IsCurrent")[(args[0] as Int) - 1]
                else -> error("Unexpected metadata call: $method")
            }
        }
        private val remote = proxy<RemoteConnection> { method, _ ->
            check(method.name == "createStatement") { "Unexpected connection call: $method" }
            proxy<RemoteStatement> { statementMethod, args ->
                when (statementMethod.name) {
                    "executeQuery" -> {
                        val text = args[0] as String
                        sql += text
                        val data = query(text)
                        var index = -1
                        proxy<RemoteResultSet> { rowMethod, rowArgs ->
                            when (rowMethod.name) {
                                "next" -> ++index < data.size
                                "getString" -> data[index][(rowArgs[0] as Int) - 1]
                                "getMetaData" -> metadata
                                "close" -> { closedResults++; null }
                                else -> error("Unexpected result call: $rowMethod")
                            }
                        }
                    }
                    "close" -> { closedStatements++; null }
                    else -> error("Unexpected statement call: $statementMethod")
                }
            }
        }
        val connection = proxy<DatabaseConnectionCore> { method, _ ->
            when (method.name) {
                "getRemoteConnection" -> remote
                "getDbms" -> DorisDbms.DORIS
                else -> error("Unexpected core call: $method")
            }
        }
    }

    companion object {
        private inline fun <reified T> proxy(crossinline call: (Method, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.get(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Doris read-back ${T::class.java.simpleName}"
                    else -> call(method, args.orEmpty())
                }
            } as T
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
