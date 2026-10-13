package dev.sort.doris.cancel

import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.CooperativeAction
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.doris.testing.RemoteRowsFixture
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class DorisCancelCooperativeTest : BasePlatformTestCase() {
    private var nextSession = 1000000L
    private fun client(recording: RecordingConsole, busy: Boolean = false): SessionClient<*> {
        val base = recording.console.session
        val id = ++nextSession
        val session = Proxy.newProxyInstance(DatabaseSession::class.java.classLoader, arrayOf(DatabaseSession::class.java)) { _, method, args ->
            when (method.name) {
                "getId" -> id
                "isIdle" -> !busy
                else -> try { method.invoke(base, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
            }
        } as DatabaseSession
        return Proxy.newProxyInstance(SessionClient::class.java.classLoader, arrayOf(SessionClient::class.java)) { instance, method, args ->
            when (method.name) {
                "getSession" -> session
                "getProject" -> project
                "equals" -> instance === args?.get(0)
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Doris cancel client $id"
                else -> error("Unexpected client $method")
            }
        } as SessionClient<*>
    }

    private fun event(client: SessionClient<*>) = AnActionEvent.createFromDataContext("Doris cancel", Presentation(),
        SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(SessionClientHolder.CLIENT_KEY, client).build())

    private fun recording(dialect: String = "doris") = RecordingConsole(project,
        myFixture.configureByText("cancel.sql", "SELECT 1").virtualFile, dialect)

    fun testForeignAndUntaggedConsolesDelegateOnce() {
        for (dialect in listOf("mysql", "doris")) recording(dialect).use { console ->
            var delegated = 0
            val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { delegated++ } }
            val wrapper = CooperativeAction(previous, DorisCancelInterceptor(DorisCancelCoordinator()) { previous })
            wrapper.actionPerformed(event(client(console)))
            assertEquals(1, delegated)
            assertEmpty(console.requests)
        }
    }

    fun testClientCancelPrecedesTheRealKillQueryAndNoStockRunsOnSuccess() {
        recording().use { recording ->
            val remote = RemoteRowsFixture()
            val guid = DorisCancel.mintGuid()
            val queryId = "0000000000000001-0000000000000002"
            DorisCancel.registerGuid(remote.remoteConnection, guid, recording.console.dataSource.uniqueId)
            val order = CopyOnWriteArrayList<String>()
            recording.onSubmit = { request -> assertTrue(request is DataRequest.Cancel); order += "client" }
            var scans = 0
            remote.query = { sql ->
                assertEquals(DorisCancel.SQL_SHOW_FULL_PROCESSLIST, sql)
                listOf("QueryId", "Info", "Command") to if (scans++ == 0)
                    listOf(listOf(queryId, "SELECT 1 /* ${DorisCancel.processlistMarker(guid)} */", "Query")) else emptyList()
            }
            remote.command = { sql -> if (sql.startsWith("KILL QUERY")) order += "kill" }
            val coordinator = object : DorisCancelCoordinator() {
                override fun resolveConsole(session: DatabaseSession): ResolvedConsole {
                    order += "resolve"
                    return ResolvedConsole(emptyList(), setOf(guid))
                }
                override fun withHelperConnection(session: DatabaseSession, use: (com.intellij.database.dataSource.DatabaseConnectionCore) -> CancelResult) = use(remote.connection)
            }
            var stock = 0
            val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { stock++ } }
            val client = client(recording)
            CooperativeAction(previous, DorisCancelInterceptor(coordinator) { previous }).actionPerformed(event(client))
            PlatformTestUtil.waitWithEventsDispatching("Doris kill finishes", {
                remote.executed.any { it.startsWith("KILL QUERY") } && !DorisCancel.isCancelInFlight((client.session as DatabaseSession).id)
            }, 10)
            assertEquals(listOf("resolve", "client", "kill"), order)
            assertEquals(1, recording.requests.size)
            assertEquals(0, stock)
            assertContainsElements(remote.executed, DorisCancel.sqlKillQueryByQueryId(queryId))
            assertEquals(4, remote.statementsClosed.get())
            assertEquals(2, remote.resultsClosed.get())
        }
    }

    fun testDeferredFallbackUsesThePredecessorWithAFreshStableContext() {
        recording().use { recording ->
            val remote = RemoteRowsFixture()
            val guid = DorisCancel.mintGuid()
            DorisCancel.registerGuid(remote.remoteConnection, guid, recording.console.dataSource.uniqueId)
            val client = client(recording, busy = true)
            val coordinator = object : DorisCancelCoordinator() {
                override fun resolveConsole(session: DatabaseSession) = ResolvedConsole(emptyList(), setOf(guid))
                override fun withHelperConnection(session: DatabaseSession, use: (com.intellij.database.dataSource.DatabaseConnectionCore) -> CancelResult) = use(remote.connection)
            }
            var fallback: AnActionEvent? = null
            val predecessor = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { fallback = e } }
            val original = event(client)
            CooperativeAction(predecessor, DorisCancelInterceptor(coordinator) { predecessor }).actionPerformed(original)
            PlatformTestUtil.waitWithEventsDispatching("Doris fallback runs", { fallback != null }, 10)
            assertNotSame(original, fallback)
            assertSame(project, fallback!!.project)
            assertSame(client, fallback!!.getData(SessionClientHolder.CLIENT_KEY))
            assertEquals(1, recording.requests.size)
        }
    }

    fun testInflightCancelConsumesRepeatPressWithoutStockEscalation() {
        recording().use { recording ->
            val client = client(recording)
            val id = (client.session as DatabaseSession).id
            assertTrue(DorisCancel.beginCancel(id))
            try {
                var delegated = 0
                val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { delegated++ } }
                val wrapper = CooperativeAction(previous, DorisCancelInterceptor(DorisCancelCoordinator()) { previous })
                wrapper.actionPerformed(event(client))
                wrapper.actionPerformed(event(client))
                assertEquals(0, delegated)
                assertEmpty(recording.requests)
            } finally { DorisCancel.endCancel(id) }
        }
    }

    fun testConfigurationPreservesAPeerAndDetachesWithoutOverwritingIt() {
        val manager = ActionManager.getInstance()
        val original = manager.getAction("Console.Jdbc.Cancel")!!
        var delegated = 0
        val sentinel = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { delegated++ } }
        manager.replaceAction("Console.Jdbc.Cancel", sentinel)
        val configuration = DorisCancelConfiguration()
        try {
            configuration.registerActions(manager)
            val ours = manager.getAction("Console.Jdbc.Cancel") as CooperativeAction
            val peer = CooperativeAction(ours, object : dev.brikk.house.sql.intellij.ActionInterceptor {
                override fun perform(event: AnActionEvent, delegate: () -> Unit) = dev.brikk.house.sql.intellij.ExecutionClaim.DECLINED
            })
            manager.replaceAction("Console.Jdbc.Cancel", peer)
            configuration.unregisterActions(manager)
            assertSame(peer, manager.getAction("Console.Jdbc.Cancel"))
            peer.actionPerformed(AnActionEvent.createFromDataContext("Detached cancel", Presentation(), SimpleDataContext.getProjectContext(project)))
            assertEquals(1, delegated)
        } finally {
            configuration.unregisterActions(manager)
            manager.replaceAction("Console.Jdbc.Cancel", original)
        }
    }
}
