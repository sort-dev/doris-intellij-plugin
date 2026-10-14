package dev.sort.doris.catalog

import com.intellij.database.dataSource.DataSourceSyncManager
import com.intellij.database.dataSource.DatabaseDriver
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.database.util.LoaderContext
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.classpath.SimpleClasspathElement
import com.intellij.util.ui.classpath.SimpleClasspathElementFactory
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sqlResolveInspection
import java.io.File

/**
 * The real platform introspection against a live Doris (scripts/doris-live.sh starts one with an
 * internal catalog holding `sales` and `ops`, both with an `orders` table, and a JDBC external catalog
 * `ext_catalog`). Skipped unless `-Pdoris.live.url` is set; JUnit 3 has no assumptions, so a skip is a return.
 */
class DorisIntrospectionLiveTest : BasePlatformTestCase() {
    private val url: String? = System.getProperty("doris.live.url")
    private val driverJar: String? = System.getProperty("doris.live.driverJar")
    private var dataSource: LocalDataSource? = null
    private var driver: DatabaseDriver? = null
    private var savedClasspath: List<SimpleClasspathElement>? = null
    private var previousFlag: String? = null

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            dataSource?.let { ds ->
                runCatching { DataSourceSyncManager.getInstance().stopSynchronization(ds) }
                runCatching { LocalDataSourceManager.getInstance(project).removeDataSource(ds) }
            }
            driver?.let { d -> savedClasspath?.let { runCatching { d.additionalClasspathElements = it } } }
            previousFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    private fun register(): LocalDataSource {
        val d = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
        driver = d
        savedClasspath = d.additionalClasspathElements
        d.additionalClasspathElements = SimpleClasspathElementFactory.createElements("jar://${File(driverJar!!).absolutePath}!/")
        val ds = LocalDataSource.create("Doris live introspection", "com.mysql.cj.jdbc.Driver", url!!, "root")
        ds.databaseDriver = d
        ds.isAutoSynchronize = false
        LocalDataSourceManager.getInstance(project).addDataSource(ds)
        dataSource = ds
        return ds
    }

    private fun sync(context: LoaderContext) {
        val future = checkNotNull(DataSourceSyncManager.getInstance().tryPerform(context, true, false)) { "no sync task" }.toFuture()
        PlatformTestUtil.waitForFuture(future, 120_000)
        settle()
    }

    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    private fun roots(ds: LocalDataSource): List<DasObject> = ReadAction.compute<List<DasObject>, RuntimeException> {
        DbPsiFacade.getInstance(project).findDataSource(ds.uniqueId)?.model?.modelRoots?.toList().orEmpty()
    }

    private fun children(node: DasObject, kind: ObjectKind): List<String> = ReadAction.compute<List<String>, RuntimeException> {
        node.getDasChildren(kind).map { it.name }.toList()
    }

    private fun node(ds: LocalDataSource, vararg path: String): DasObject? = ReadAction.compute<DasObject?, RuntimeException> {
        var current: DasObject? = roots(ds).firstOrNull { it.name == path[0] }
        for ((index, name) in path.drop(1).withIndex()) {
            val kind = if (index == 0) ObjectKind.SCHEMA else ObjectKind.TABLE
            current = current?.getDasChildren(kind)?.firstOrNull { it.name == name }
        }
        current
    }

    /** Waits until no introspection runs and none has finished for two seconds. */
    private fun awaitIdle(ds: LocalDataSource) {
        var last = sessions()
        var since = System.currentTimeMillis()
        PlatformTestUtil.waitWithEventsDispatching("introspection settles", {
            val now = sessions()
            if (now != last || DataSourceSyncManager.getInstance().isActive(ds)) { last = now; since = System.currentTimeMillis() }
            System.currentTimeMillis() - since > 2_000
        }, 60)
    }

    /** Finished introspection sessions so far, as the introspector logs them. */
    private fun sessions(): Int {
        val log = java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getLogPath(), "idea.log")
        return if (java.nio.file.Files.exists(log)) java.nio.file.Files.readAllLines(log).count { "post-introspection refresh" in it } else 0
    }

    /**
     * Introspections started while [action] runs and for [quietMillis] after: the larger of the sync
     * manager's observed activity and the sessions the introspector finished.
     */
    private fun countSyncs(ds: LocalDataSource, quietMillis: Long, action: () -> Unit = {}): Int {
        val before = sessions()
        var count = 0
        var active = false
        fun sample() {
            val now = DataSourceSyncManager.getInstance().isActive(ds)
            if (now && !active) count++
            active = now
        }
        action()
        val end = System.currentTimeMillis() + quietMillis
        while (System.currentTimeMillis() < end) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            sample()
            Thread.sleep(50)
        }
        return maxOf(count, sessions() - before)
    }

    private fun errorsIn(ds: LocalDataSource, sql: String): List<String> {
        val file = myFixture.configureByText("live.sql", sql)
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(ds.uniqueId)))))
        settle()
        return myFixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }.map { it.description.orEmpty() }
    }

    fun testCatalogsEnumerateAndExternalNamespacesLoadOnDemandWithoutRetrigger() {
        if (url.isNullOrBlank() || driverJar.isNullOrBlank()) { println("SKIP: doris.live.url not set"); return }
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        val ds = register()

        sync(LoaderContext.selectGeneralTask(project, ds))
        awaitIdle(ds)
        val catalogs = roots(ds).map { it.name }
        println("LIVE catalogs=$catalogs scope=${ds.introspectionScope?.let(TreePatternUtils::serialize)}")
        assertTrue("SHOW CATALOGS lists internal and the external catalog: $catalogs", catalogs.containsAll(listOf("internal", "ext_catalog")))
        val internal = checkNotNull(node(ds, "internal"))
        assertTrue(children(internal, ObjectKind.SCHEMA).toString(), children(internal, ObjectKind.SCHEMA).containsAll(listOf("sales", "ops")))
        assertEquals(listOf("orders"), children(checkNotNull(node(ds, "internal", "ops")), ObjectKind.TABLE))
        assertTrue("an external catalog lists its databases", "archive_src" in children(checkNotNull(node(ds, "ext_catalog")), ObjectKind.SCHEMA))
        assertEquals("but loads no tables until asked", emptyList<String>(), children(checkNotNull(node(ds, "ext_catalog", "archive_src")), ObjectKind.TABLE))

        val quiet = countSyncs(ds, 8_000)
        assertEquals("no introspection may follow a finished one by itself", 0, quiet)

        // Columns a star brings from the not yet introspected external table are not errors.
        val star = "SELECT uid, * FROM (SELECT FROM_SECOND(ts) AS ts1, *, 0 AS k FROM ext_catalog.archive_src.facts) AS u"
        assertEmpty(errorsIn(ds, star))

        // Completing `ext_catalog.archive_src.` loads that database's tables.
        val service = project.service<DorisAutoIntrospectService>()
        for (path in listOf(listOf("ext_catalog", "archive_src"))) {
            myFixture.configureByText("complete.sql", "SELECT * FROM ${path.joinToString(".")}.<caret>")
            SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
                TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(ds.uniqueId)))))
            val started = countSyncs(ds, 0) { myFixture.completeBasic() }
            PlatformTestUtil.waitWithEventsDispatching("$path loads", {
                val target = node(ds, *path.toTypedArray())
                target != null && children(target, if (path.size == 1) ObjectKind.SCHEMA else ObjectKind.TABLE).isNotEmpty()
            }, 60)
            println("LIVE after $path: started=$started pending=${service.pending(myFixture.file.virtualFile)}")
        }
        val facts = checkNotNull(node(ds, "ext_catalog", "archive_src", "facts"))
        assertEquals(listOf("ts", "uid", "kind"), ReadAction.compute<List<String>, RuntimeException> { DasUtil.getColumns(facts).map { it.name }.toList() })
        awaitIdle(ds)
        assertTrue("the default scope keeps enumerating every catalog: ${TreePatternUtils.serialize(ds.introspectionScope)}",
            DorisCatalogScopes.classifyCatalog(ds.introspectionScope!!, "internal") != null)
        assertEquals("no introspection follows the on-demand ones by itself", 0, countSyncs(ds, 8_000))

        // A full refresh keeps the external databases it loaded, and lists every catalog again.
        sync(LoaderContext.selectGeneralTask(project, ds))
        awaitIdle(ds)
        assertTrue(roots(ds).map { it.name }.containsAll(listOf("internal", "ext_catalog")))
        assertEquals(listOf("facts"), children(checkNotNull(node(ds, "ext_catalog", "archive_src")), ObjectKind.TABLE))
        assertEquals(0, countSyncs(ds, 8_000))

        // With the external table loaded, the star shapes and a same-named table in two databases stay quiet.
        assertEmpty(errorsIn(ds, star))
        assertEmpty(errorsIn(ds, "SELECT o.amount, p.status FROM internal.sales.orders o JOIN internal.ops.orders p ON p.id = o.id"))
        assertTrue(errorsIn(ds, "SELECT nope FROM internal.ops.orders").any { "nope" in it })
    }

    fun testClearedModelListsOnDemandAndAllDatabasesLoadsInternal() {
        if (url.isNullOrBlank() || driverJar.isNullOrBlank()) { println("SKIP: doris.live.url not set"); return }
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        val ds = register()
        // What the schemas pane holds after a clear: "All databases" at the root, no catalog level.
        ds.introspectionScope = TreePattern(com.intellij.database.util.TreePatternNode(
            com.intellij.database.util.TreePatternNode.NegativeNaming.WILDCARD, com.intellij.database.util.TreePatternNode.NO_GROUPS))
        assertEmpty(roots(ds))
        println("LIVE model=${ds.model?.javaClass?.name} root=${(ds.model as? com.intellij.database.model.basic.BasicModModel)?.root?.javaClass?.name}")

        fun complete(text: String) {
            myFixture.configureByText("cleared.sql", text)
            SqlResolveMappings.getInstance(project).setMapping(myFixture.file.virtualFile,
                TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(ds.uniqueId)))))
            settle()
            myFixture.completeBasic()
        }
        complete("SELECT * FROM ext_catalog.<caret>")
        PlatformTestUtil.waitWithEventsDispatching("the catalogs and ext_catalog's databases are listed", {
            node(ds, "ext_catalog")?.let { "archive_src" in children(it, ObjectKind.SCHEMA) } == true
        }, 60)
        println("LIVE cleared listing: catalogs=${roots(ds).map { it.name }}")
        complete("SELECT * FROM ext_catalog.archive_src.<caret>")
        PlatformTestUtil.waitWithEventsDispatching("archive_src's tables load", {
            node(ds, "ext_catalog", "archive_src")?.let { children(it, ObjectKind.TABLE).isNotEmpty() } == true
        }, 60)
        assertEquals(listOf("facts"), children(checkNotNull(node(ds, "ext_catalog", "archive_src")), ObjectKind.TABLE))

        // A sync under "All databases" lists every catalog and loads internal, as the default does.
        sync(LoaderContext.selectGeneralTask(project, ds))
        val internal = checkNotNull(node(ds, "internal"))
        assertTrue(children(internal, ObjectKind.SCHEMA).containsAll(listOf("sales", "ops")))
        assertEquals(listOf("orders"), children(checkNotNull(node(ds, "internal", "ops")), ObjectKind.TABLE))
        assertTrue("the external catalog is listed", "archive_src" in children(checkNotNull(node(ds, "ext_catalog")), ObjectKind.SCHEMA))
    }
}
