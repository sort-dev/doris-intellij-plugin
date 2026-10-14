package dev.sort.doris.catalog

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mssql.model.MsSchema
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sqlResolveInspection

/**
 * End-to-end multi-catalog flows over an offline model: `internal` with `sales` and `ops` (both with an
 * `orders` table), and an external catalog listed but not introspected. The platform's sync runs; only
 * its database round trip is played by the test ([HeldIntrospections]).
 */
class DorisMultiCatalogFlowTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var introspections: HeldIntrospections
    private lateinit var source: LocalDataSource
    private lateinit var requests: DorisAutoIntrospectService
    private lateinit var external: MsDatabase
    private var previousFlag: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        introspections = HeldIntrospections(testRootDisposable)
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        source = LocalDataSource().apply {
            name = "Offline Doris multi-catalog"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-multi-catalog"; isAutoSynchronize = false; isKeepAlive = false
            model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
            introspectionScope = DorisCatalogScopes.multiCatalogDefaultScope()
        }
        val root = (source.model as BasicModModel).root as MsRoot
        val internal = root.databases.createOrGet(1L).apply { name = "internal" }
        internal.schemas.createOrGet("sales").tables.apply {
            createOrGet("orders").columns.apply { createOrGet("id"); createOrGet("amount") }
            createOrGet("customers").columns.apply { createOrGet("id"); createOrGet("name") }
        }
        internal.schemas.createOrGet("ops").tables.createOrGet("orders").columns.apply { createOrGet("id"); createOrGet("status") }
        external = root.databases.createOrGet(2L).apply { name = "ext_catalog" }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        requests = DorisAutoIntrospectService(project, errors.newScope(testRootDisposable))
        project.registerOrReplaceServiceInstance(DorisAutoIntrospectService::class.java, requests, testRootDisposable)
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        settle()
    }

    override fun tearDown() = RunAll(
        { LookupManager.getInstance(project).hideActiveLookup() },
        { introspections.finishAll() },
        { errors.awaitScopes() },
        { errors.assertNone() },
        { SqlResolveMappings.getInstance(project).setMapping(myFixture.file?.virtualFile ?: return@RunAll, null) },
        { LocalDataSourceManager.getInstance(project).removeDataSource(source) },
        { DbPsiFacade.getInstance(project).clearCaches() },
        { previousFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { super.tearDown() },
    ).run()

    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun configure(text: String) {
        myFixture.configureByText("flow.sql", text)
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
        settle()
    }

    private fun errors(): List<String> {
        settle()
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description.orEmpty() }
    }

    private fun awaitDone(path: List<String>) {
        PlatformTestUtil.waitWithEventsDispatching("$path introspected", {
            requests.engine.state(AutoIntrospectEngine.Key(source.uniqueId, path)) == AutoIntrospectEngine.State.Done
        }, 10)
        DbPsiFacade.getInstance(project).clearCaches()
        settle()
    }

    private fun archive(): MsSchema = external.schemas.createOrGet("archive_db")

    private fun assertScopeStillEnumeratesEveryCatalog() {
        assertEquals(TreePatternUtils.serialize(source.introspectionScope),
            DorisCatalogScopes.CatalogScopeClass.ENUMERATED_DEFAULT, DorisCatalogScopes.classifyCatalog(source.introspectionScope, "other_catalog"))
    }

    fun testCompletingAPathLoadsOneLevelAtATime() {
        configure("SELECT * FROM ext_catalog.<caret>")
        myFixture.completeBasic()
        val catalogRefresh = introspections.next()
        assertEquals("ext_catalog", catalogRefresh.context.tasks.single().databaseName)
        catalogRefresh.finish { archive() }
        awaitDone(listOf("ext_catalog"))
        assertTrue("one level: the database stays unloaded", archive().tables.isEmpty())

        LookupManager.getInstance(project).hideActiveLookup()
        configure("SELECT * FROM ext_catalog.archive_db.<caret>")
        myFixture.completeBasic()
        introspections.next().finish { archive().tables.createOrGet("facts").columns.apply { createOrGet("ts"); createOrGet("uid") } }
        awaitDone(listOf("ext_catalog", "archive_db"))
        LookupManager.getInstance(project).hideActiveLookup()
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "facts")
        assertScopeStillEnumeratesEveryCatalog()
    }

    fun testAQueryOverAnUnloadedTableLoadsItsNamespacesWithoutFalseErrors() {
        configure("SELECT uid, * FROM (SELECT FROM_SECOND(ts) AS ts1, *, 0 AS k FROM ext_catalog.archive_db.facts) AS u")
        assertEmpty(errors())
        introspections.next().finish { archive() }
        awaitDone(listOf("ext_catalog"))
        assertEmpty(errors())
        introspections.next().finish { archive().tables.createOrGet("facts") }
        awaitDone(listOf("ext_catalog", "archive_db"))
        assertEmpty("a table listed without columns is not a source of errors", errors())
        introspections.next().finish { archive().tables.createOrGet("facts").columns.apply { createOrGet("ts"); createOrGet("uid"); createOrGet("kind") } }
        awaitDone(listOf("ext_catalog", "archive_db", "facts"))
        assertEmpty(errors())
        introspections.assertNone()
        configure("SELECT nope FROM ext_catalog.archive_db.facts")
        assertTrue("once loaded, a missing column is an error", errors().any { "nope" in it })
        assertScopeStillEnumeratesEveryCatalog()
    }

    fun testIntrospectedNamespacesDoNotIntrospectAgain() {
        configure("SELECT o.amount, p.status FROM internal.sales.orders o JOIN internal.ops.orders p ON p.id = o.id")
        assertEmpty(errors())
        configure("SELECT * FROM internal.sales.nope")
        assertTrue(errors().any { "nope" in it })
        introspections.assertNone(500)
    }

    fun testConsoleNamespaceDecidesSameNamedTablesAndCompletion() {
        configure("SELECT status FROM orders")
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            for ((database, column, other) in listOf(Triple("ops", "status", "amount"), Triple("sales", "amount", "status"))) {
                recording.console.setSearchPath(SearchPath.of(ObjectPath.create("internal", ObjectKind.DATABASE).append(database, ObjectKind.SCHEMA)))
                DbPsiFacade.getInstance(project).clearCaches()
                configure("SELECT id, $column FROM orders")
                assertEmpty("$database: $column", errors())
                configure("SELECT $other FROM orders")
                assertTrue("$database: $other", errors().any { other in it })
                configure("SELECT * FROM <caret>")
                myFixture.completeBasic()
                assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "orders")
                LookupManager.getInstance(project).hideActiveLookup()
            }
            introspections.assertNone(500)
        }
    }

    fun testConsoleOnAnUnloadedExternalDatabaseLoadsItForBareTables() {
        archive()
        source.introspectionScope = CatalogScopes.widen(source.introspectionScope, CatalogScopes.select(DorisCatalogProfile.levels, listOf("ext_catalog")))
        DbPsiFacade.getInstance(project).clearCaches()
        configure("SELECT 1")
        RecordingConsole(project, myFixture.file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("ext_catalog", ObjectKind.DATABASE).append("archive_db", ObjectKind.SCHEMA)))
            DbPsiFacade.getInstance(project).clearCaches()
            configure("SELECT uid FROM facts")
            assertEmpty("an unloaded current database is not a source of errors", errors())
            configure("SELECT * FROM archive_db.<caret>")
            myFixture.completeBasic()
            introspections.next().finish { archive().tables.createOrGet("facts").columns.apply { createOrGet("ts"); createOrGet("uid") } }
            awaitDone(listOf("ext_catalog", "archive_db"))
            configure("SELECT uid FROM facts")
            assertEmpty(errors())
        }
    }
}
