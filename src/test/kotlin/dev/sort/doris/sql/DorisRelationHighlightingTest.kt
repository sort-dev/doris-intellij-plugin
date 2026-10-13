package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import com.intellij.sql.database.SqlDataSourceManager
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.sql.psi.impl.DataSourceProvider
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.DorisDbms
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.pipes.DorisPipesSettings
import dev.sort.doris.sqlResolveInspection

/** Actual daemon/extension wiring over an offline model; never calls the annotator directly. */
class DorisRelationHighlightingTest : BasePlatformTestCase() {
    private lateinit var local: LocalDataSource
    private lateinit var removeDdl: () -> Unit
    private lateinit var errors: BackgroundErrors
    private var dataSources = emptyList<DbDataSource>()
    private var previousPipes = false
    private var previousCatalogs: String? = null

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        previousPipes = project.service<DorisPipesSettings>().enabled
        previousCatalogs = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "false")
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        DataSourceProvider.EP_NAME.point.registerExtension(object : DataSourceProvider {
            override fun getDataSources(element: PsiFile) = dataSources
        }, testRootDisposable)
        val schema = myFixture.addFileToProject("relations.sql", """
            CREATE TABLE a_db.orders (id BIGINT NOT NULL, a_only INT);
            CREATE TABLE b_db.orders (id BIGINT NOT NULL, b_only INT);
        """.trimIndent())
        setSqlDialectMapping(project, schema.virtualFile, DorisSqlDialect.INSTANCE, testRootDisposable)
        val manager = SqlDataSourceManager.getInstance(project)
        val ddl = manager.createEmpty().apply {
            name = "Offline Doris relations"; definedDbms = DorisDbms.DORIS
            isAutoSync = false; setFiles(arrayOf(schema.virtualFile))
        }
        manager.addDataSource(ddl)
        removeDdl = { manager.removeDataSource(ddl) }
        PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread { ddl.sync() }, 30_000)
        DbPsiFacade.getInstance(project).clearCaches()
        dataSources = listOf(requireNotNull(DbPsiFacade.getInstance(project).findDataSource(ddl.uniqueId)))
        local = object : LocalDataSource() { init { setUniqueId(ddl.uniqueId) } }.apply {
            name = "Offline Doris relations"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:relations"; isAutoSynchronize = false; isKeepAlive = false; model = ddl.model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(local)
        DbPsiFacade.getInstance(project).clearCaches()
        settle()
    }

    /** Dumb mode entered by queued indexing during highlighting would skip the annotators and change PSI. */
    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() = RunAll(
        { project.service<DorisPipesSettings>().enabled = previousPipes },
        { previousCatalogs?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { errors.awaitScopes() }, { errors.assertNone() },
        { dataSources = emptyList() },
        { LocalDataSourceManager.getInstance(project).removeDataSource(local) },
        { removeDdl() }, { DbPsiFacade.getInstance(project).clearCaches() },
        { super.tearDown() },
    ).run()

    /** The toggle reparses files in a later EDT event; let it run before highlighting. */
    private fun setPipes(enabled: Boolean) {
        project.service<DorisPipesSettings>().enabled = enabled
        settle()
    }

    private fun configure(sql: String): PsiFile = myFixture.configureByText("Query.sql", sql).also { file ->
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(local.uniqueId)))))
        settle()
    }

    private fun highlight(sql: String, database: String): List<HighlightInfo> {
        val file = configure(sql)
        return RecordingConsole(project, file.virtualFile, local).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create(database, ObjectKind.SCHEMA)))
            myFixture.doHighlighting()
        }
    }

    private fun List<HighlightInfo>.errors() = filter { it.severity == HighlightSeverity.ERROR }.map { it.description.orEmpty() }

    fun testOrdinarySqlSearchPathSelectsEitherDatabaseWithoutFalseErrors() {
        for (pipes in listOf(false, true)) {
            setPipes(pipes)
            for ((database, column, other) in listOf(Triple("a_db", "a_only", "b_only"), Triple("b_db", "b_only", "a_only"))) {
                val queries = listOfNotNull("SELECT id, $column FROM orders", "FROM orders |> SELECT id, $column".takeIf { pipes })
                for (sql in queries) {
                    assertEmpty("$sql on $database, PIPE $pipes", highlight(sql, database).errors())
                    val wrong = sql.replace(column, other)
                    assertEquals("$wrong on $database, PIPE $pipes", listOf("Unknown column '$other'"),
                        highlight(wrong, database).errors().filter { it.startsWith("Unknown column") })
                }
            }
        }
    }

    fun testSharedDiagnosticsRemainEnabledForOrdinarySql() {
        val file = configure("SELECT missing FROM orders")
        RecordingConsole(project, file.virtualFile, local).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("a_db", ObjectKind.SCHEMA)))
            assertTrue(myFixture.doHighlighting().any { it.description == "Unknown column 'missing'" })
        }
    }

    fun testSharedDiagnosticsReachPipeEditor() {
        setPipes(true)
        val file = configure("FROM orders |> SELECT missing")
        RecordingConsole(project, file.virtualFile, local).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("a_db", ObjectKind.SCHEMA)))
            val infos = myFixture.doHighlighting()
            assertTrue(infos.joinToString { "${it.severity}:${it.description}" }, infos.any { it.description == "Unknown column 'missing'" })
        }
    }
}
