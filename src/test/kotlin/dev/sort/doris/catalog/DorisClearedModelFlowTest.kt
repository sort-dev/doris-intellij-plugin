package dev.sort.doris.catalog

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectName
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.TreePattern
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectEngine
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sqlResolveInspection
import dev.sort.doris.testing.productionReplay
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.CopyOnWriteArrayList

/**
 * From a cleared model (the data source root and nothing under it), a path the model does not know is
 * listed first on a helper connection (played here by [Listing]), then the targeted level loads through
 * the platform sync ([HeldIntrospections]).
 */
class DorisClearedModelFlowTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var introspections: HeldIntrospections
    private lateinit var source: LocalDataSource
    private lateinit var requests: DorisAutoIntrospectService
    private lateinit var listing: Listing
    private var previousFlag: String? = null

    /** Answers the listing queries from a fixed server inventory and records them. */
    private class Listing(project: Project, scope: CoroutineScope) : DorisNamespaceListing(project, scope) {
        val queries = CopyOnWriteArrayList<String>()
        val databases = mapOf("internal" to listOf("sales"), "ext_catalog" to listOf("archive_db", "other_db"))

        override suspend fun <T> withQueries(local: LocalDataSource, use: (query: (String) -> List<Map<String, String?>>) -> T): T = use { sql ->
            queries += sql
            when {
                sql == "SHOW CATALOGS" -> listOf(mapOf("CatalogId" to "0", "CatalogName" to "internal"), mapOf("CatalogId" to "7", "CatalogName" to "ext_catalog"))
                sql.startsWith("SHOW DATABASES FROM ") -> databases[sql.removePrefix("SHOW DATABASES FROM ").trim('`')].orEmpty().map { mapOf("Database" to it) }
                else -> error("unexpected listing query $sql")
            }
        }
    }

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        introspections = HeldIntrospections(testRootDisposable)
        productionReplay(testRootDisposable)
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        source = LocalDataSource().apply {
            name = "Cleared Doris model"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:doris-cleared"; isAutoSynchronize = false; isKeepAlive = false
            introspectionScope = DorisCatalogScopes.multiCatalogDefaultScope()
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        DbPsiFacade.getInstance(project).clearCaches()
        requests = DorisAutoIntrospectService(project, errors.newScope(testRootDisposable))
        project.registerOrReplaceServiceInstance(DorisAutoIntrospectService::class.java, requests, testRootDisposable)
        listing = Listing(project, errors.newScope(testRootDisposable))
        project.registerOrReplaceServiceInstance(DorisNamespaceListing::class.java, listing, testRootDisposable)
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        settle()
    }

    override fun tearDown() = RunAll(
        { LookupManager.getInstance(project).hideActiveLookup() },
        { introspections.finishAll() },
        { errors.awaitScopes() },
        { errors.assertNone() },
        { LocalDataSourceManager.getInstance(project).removeDataSource(source) },
        { DbPsiFacade.getInstance(project).clearCaches() },
        { previousFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { super.tearDown() },
    ).run()

    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private val root get() = (source.model as BasicModModel).root as MsRoot

    private fun catalog(name: String): MsDatabase? = ((source.model as? BasicModModel)?.root as? MsRoot)?.databases?.firstOrNull { it.name == name }

    private fun configure(text: String) {
        myFixture.configureByText("cleared.sql", text)
        SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
        settle()
    }

    private fun completions(text: String): List<String> {
        LookupManager.getInstance(project).hideActiveLookup()
        configure(text)
        return myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
    }

    private fun errors(): List<String> {
        settle()
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description.orEmpty() }
    }

    private fun awaitListing(condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("the listing lands in the model", condition, 10)
        settle()
    }

    private fun awaitDone(path: List<String>) {
        PlatformTestUtil.waitWithEventsDispatching("$path introspected", {
            requests.engine.state(AutoIntrospectEngine.Key(source.uniqueId, path)) == AutoIntrospectEngine.State.Done
        }, 10)
        PlatformTestUtil.waitWithEventsDispatching("the refresh of $path ends", {
            !com.intellij.database.dataSource.DataSourceSyncManager.getInstance().isActive(source)
        }, 10)
        errors.awaitScopes()
        DbPsiFacade.getInstance(project).clearCaches()
        settle()
    }

    fun testCompletingAnUnknownCatalogListsItThenLoadsTheDatabaseOnDemand() {
        assertFalse("a cleared data source has no model", source.model is BasicModModel)
        completions("SELECT * FROM ext_catalog.<caret>")
        awaitListing { catalog("ext_catalog")?.schemas?.isEmpty == false }
        assertEquals(listOf("SHOW CATALOGS", "SHOW DATABASES FROM `ext_catalog`"), listing.queries.toList())
        introspections.assertNone()
        assertContainsElements(completions("SELECT * FROM ext_catalog.<caret>"), "archive_db", "other_db")

        completions("SELECT * FROM ext_catalog.archive_db.<caret>")
        val refresh = introspections.next()
        assertEquals("ext_catalog", refresh.context.tasks.single().databaseName)
        refresh.finish { catalog("ext_catalog")!!.schemas.first { it.name == "archive_db" }.tables.createOrGet("facts").columns.createOrGet("uid") }
        awaitDone(listOf("ext_catalog", "archive_db"))
        assertContainsElements(completions("SELECT * FROM ext_catalog.archive_db.<caret>"), "facts")
        assertEquals("nothing is listed twice", 2, listing.queries.size)
    }

    fun testAQueryOverAnUnknownCatalogListsAndLoadsOnlyTheTargetedDatabase() {
        configure("SELECT uid FROM ext_catalog.archive_db.facts")
        errors()
        awaitListing { catalog("ext_catalog")?.schemas?.isEmpty == false }
        val refresh = introspections.next()
        assertEquals("ext_catalog", refresh.context.tasks.single().databaseName)
        refresh.finish { catalog("ext_catalog")!!.schemas.first { it.name == "archive_db" }.tables.createOrGet("facts").columns.createOrGet("uid") }
        awaitDone(listOf("ext_catalog", "archive_db"))
        assertEmpty(errors())
        assertTrue("the other database stays unloaded", catalog("ext_catalog")!!.schemas.first { it.name == "other_db" }.tables.isEmpty)
        introspections.assertNone()
    }

    fun testANameStillUnknownAfterItsListingIsNotListedAgain() {
        configure("SELECT * FROM nosuch_catalog.x.t")
        errors()
        awaitListing { catalog("internal") != null }
        val asked = listing.queries.toList()
        assertEquals(listOf("SHOW CATALOGS", "SHOW DATABASES FROM `internal`"), asked)
        repeat(2) { configure("SELECT * FROM nosuch_catalog.x.t"); errors() }
        errors.awaitScopes()
        assertEquals(asked, listing.queries.toList())
        introspections.assertNone(500)
    }

    fun testAPartlyLoadedDatabaseCompletesItsWholeTableList() {
        source.model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        val sales = root.databases.createOrGet(0L).apply { name = "internal" }.schemas.createOrGet("sales")
        sales.tables.createOrGet("loaded_on_demand")
        DbPsiFacade.getInstance(project).clearCaches()
        completions("SELECT * FROM internal.sales.<caret>")
        val refresh = introspections.next()
        assertEquals("internal", refresh.context.tasks.single().databaseName)
        refresh.finish { sales.tables.createOrGet("orders"); sales.tables.createOrGet("customers") }
        awaitDone(listOf("internal", "sales"))
        assertContainsElements(completions("SELECT * FROM internal.sales.<caret>"), "loaded_on_demand", "orders", "customers")
        introspections.assertNone(500)
        assertEmpty("a known catalog needs no listing", listing.queries)
    }

    fun testConsoleSwitchToAnUnknownCatalogListsIt() {
        listing.ensureNamespace(source, "ext_catalog", "archive_db")
        awaitListing { catalog("ext_catalog")?.schemas?.isEmpty == false }
        assertEquals(listOf("SHOW CATALOGS", "SHOW DATABASES FROM `ext_catalog`"), listing.queries.toList())
        assertEquals(listOf("archive_db", "other_db"), catalog("ext_catalog")!!.schemas.map { it.name }.sorted())
        listing.ensureNamespace(source, "ext_catalog", "archive_db")
        errors.awaitScopes()
        assertEquals(2, listing.queries.size)
    }
}
