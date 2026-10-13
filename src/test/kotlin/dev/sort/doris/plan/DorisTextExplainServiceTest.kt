package dev.sort.doris.plan

import com.intellij.database.console.JdbcConsole
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.model.ObjectKind
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.Project
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.CooperativeAction
import dev.brikk.house.sql.intellij.catalog.ExplainOutcome
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.doris.pipes.DorisPipesSettings
import dev.sort.doris.testing.RemoteRowsFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

class DorisTextExplainServiceTest : BasePlatformTestCase() {
    private lateinit var errors: BackgroundErrors
    private lateinit var service: Service
    private val remote = RemoteRowsFixture()

    private class Service(project: Project, scope: CoroutineScope, val remote: RemoteRowsFixture) : DorisTextExplainService(project, scope) {
        var opened = 0
        val shown = ArrayList<ExplainOutcome>()
        override suspend fun <T> withHelperConnection(console: JdbcConsole, use: suspend (DatabaseConnectionCore) -> T): T {
            opened++
            return use(remote.connection)
        }
        override fun present(outcome: ExplainOutcome) { shown += outcome }
    }

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        remote.query = { listOf("Explain String") to listOf(listOf("PLAN ROOT"), listOf("  SCAN rows")) }
        service = Service(project, errors.newScope(testRootDisposable), remote)
        project.registerOrReplaceServiceInstance(DorisTextExplainService::class.java, service, testRootDisposable)
    }

    override fun tearDown() = RunAll({ errors.awaitScopes() }, { errors.assertNone() }, { super.tearDown() }).run()

    private fun console(sql: String, dialect: String = "doris") = RecordingConsole(project, myFixture.configureByText("explain.sql", sql).virtualFile, dialect)

    fun testPlanUsesOnlyAHelperInTheExactConsoleNamespace() {
        console("SELECT COUNT(*) FROM events").use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("ware`house", ObjectKind.DATABASE).append("ana`lytics", ObjectKind.SCHEMA)))
            val result = runBlocking { service.explain(recording.console, "SELECT COUNT(*) FROM events") }
            assertEquals(ExplainOutcome.Plan("PLAN ROOT\n  SCAN rows"), result)
            assertEquals(listOf("use `ware``house`.`ana``lytics`", "SET prefer_udf_over_builtin = false", "EXPLAIN SELECT COUNT(*) FROM events"), remote.executed)
            assertEmpty(recording.requests)
            assertEquals(3, remote.statementsClosed.get())
            assertEquals(1, remote.resultsClosed.get())
        }
    }

    fun testPipeQueryIsLoweredBeforeExplain() {
        project.getService(DorisPipesSettings::class.java).enabled = true
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        console("FROM events |> WHERE id > 0 |> SELECT id").use { recording ->
            val result = runBlocking { service.explain(recording.console, "FROM events |> WHERE id > 0 |> SELECT id") }
            assertTrue(result.toString(), result is ExplainOutcome.Plan)
            assertFalse(remote.executed.last().contains("|>"))
            assertTrue(remote.executed.last().contains("WHERE"))
            assertEmpty(recording.requests)
        }
    }

    fun testNonQueriesParametersAndUnprovedCallsOpenNoHelper() {
        console("SELECT 1").use { recording ->
            for (sql in listOf("CREATE TABLE t(id INT)", "INSERT INTO t VALUES (1)", "DROP TABLE t", "SELECT :id",
                "SELECT unknown_udf(id) FROM t", "SELECT * FROM s3()", "SELECT /*+ SET_VAR(query_timeout=5) */ 1")) {
                val result = runBlocking { service.explain(recording.console, sql) }
                assertTrue("$sql: $result", result is ExplainOutcome.Refused)
            }
            assertEquals(0, service.opened)
            assertEmpty(remote.executed)
        }
    }

    fun testCatalogOnlySwitchUsesDorisSwitch() {
        console("SELECT 1").use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("warehouse", ObjectKind.DATABASE)))
            assertTrue(runBlocking { service.explain(recording.console, "SELECT 1") } is ExplainOutcome.Plan)
            assertEquals("SWITCH `warehouse`", remote.executed.first())
        }
    }

    fun testCooperativeWrapperDeclinesForeignConsolesAndDetaches() {
        val manager = ActionManager.getInstance()
        val original = manager.getAction("Console.Jdbc.ExplainPlan")!!
        var delegated = 0
        val sentinel = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { delegated++ } }
        manager.replaceAction("Console.Jdbc.ExplainPlan", sentinel)
        val configuration = DorisTextExplainConfiguration()
        try {
            configuration.registerActions(manager)
            val wrapper = manager.getAction("Console.Jdbc.ExplainPlan") as CooperativeAction
            assertSame(sentinel, wrapper.delegate)
            console("SELECT 1", "mysql").use { recording ->
                val event = AnActionEvent.createFromDataContext("Explain chain", Presentation(), SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, myFixture.editor)
                    .add(SessionClientHolder.CLIENT_KEY, recording.console).build())
                wrapper.actionPerformed(event)
                assertEquals(1, delegated)
                configuration.unregisterActions(manager)
                assertSame(sentinel, manager.getAction("Console.Jdbc.ExplainPlan"))
                wrapper.actionPerformed(event)
                assertEquals(2, delegated)
            }
        } finally {
            configuration.unregisterActions(manager)
            manager.replaceAction("Console.Jdbc.ExplainPlan", original)
        }
    }
}
