package dev.sort.doris.cancel

import com.intellij.database.Dbms
import dev.sort.doris.DorisDbms
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the clean Doris cancel (RESEARCH-query-cancel.md): guid format, the
 * exact SQL text of every building block (each string was verified live against Doris 4.1.2 —
 * changing one here means re-verifying against a cluster), the unknown-query-id error
 * classification, the interceptor/action dbms guard, and the flag parsing rule.
 */
class DorisCancelTest {

    // --- flag -----------------------------------------------------------------------------

    @Test
    fun `flag defaults on and only explicit false disables`() {
        assertTrue(DorisCancel.isEnabledValue(null))
        assertTrue(DorisCancel.isEnabledValue(""))
        assertTrue(DorisCancel.isEnabledValue("true"))
        assertTrue(DorisCancel.isEnabledValue("banana"))
        assertFalse(DorisCancel.isEnabledValue("false"))
        assertFalse(DorisCancel.isEnabledValue("FALSE"))
        assertFalse(DorisCancel.isEnabledValue("False"))
    }

    @Test
    fun `enabled reads the system property per access`() {
        val old = System.getProperty(DorisCancel.PROPERTY)
        try {
            System.clearProperty(DorisCancel.PROPERTY)
            assertTrue(DorisCancel.enabled)
            System.setProperty(DorisCancel.PROPERTY, "false")
            assertFalse(DorisCancel.enabled)
            System.setProperty(DorisCancel.PROPERTY, "true")
            assertTrue(DorisCancel.enabled)
        } finally {
            if (old == null) System.clearProperty(DorisCancel.PROPERTY)
            else System.setProperty(DorisCancel.PROPERTY, old)
        }
    }

    // --- guid -----------------------------------------------------------------------------

    @Test
    fun `minted guids match the dg-16hex format and vary`() {
        repeat(50) {
            val guid = DorisCancel.mintGuid()
            assertTrue("bad guid: $guid", Regex("dg-[0-9a-f]{16}").matches(guid))
            assertTrue(DorisCancel.isValidGuid(guid))
        }
        assertNotEquals(DorisCancel.mintGuid(), DorisCancel.mintGuid())
    }

    @Test
    fun `guid validation rejects injection-shaped values`() {
        assertFalse(DorisCancel.isValidGuid(null))
        assertFalse(DorisCancel.isValidGuid(""))
        assertFalse(DorisCancel.isValidGuid("dg-123"))
        assertFalse(DorisCancel.isValidGuid("dg-9f2c41d87ab34e0b\"; KILL 1; --"))
        assertFalse(DorisCancel.isValidGuid("DG-9F2C41D87AB34E0B"))
        assertTrue(DorisCancel.isValidGuid("dg-9f2c41d87ab34e0b"))
    }

    @Test
    fun `query id validation accepts processlist QueryId shape only`() {
        // Live 4.1.2 shapes — note the first half may be 15 chars (no leading zero).
        assertTrue(DorisCancel.isValidQueryId("65b9749905fd4677-b6dca64ee8ec2cb0"))
        assertTrue(DorisCancel.isValidQueryId("b4ea704e9f74c0d-aa47a8e92f512d1e"))
        assertFalse(DorisCancel.isValidQueryId(null))
        assertFalse(DorisCancel.isValidQueryId(""))
        assertFalse(DorisCancel.isValidQueryId("dg-9f2c41d87ab34e0b"))
        assertFalse(DorisCancel.isValidQueryId("65b9749905fd4677-b6dca64ee8ec2cb0\" OR 1"))
    }

    // --- SQL building blocks (live-verified text; do not change without a cluster pass) ----

    @Test
    fun `sql building blocks match the live-verified statements`() {
        assertEquals(
            "SET session_context = 'trace_id:dg-9f2c41d87ab34e0b'",
            DorisCancel.sqlSetSessionContext("dg-9f2c41d87ab34e0b"),
        )
        assertEquals(
            "KILL QUERY \"dg-9f2c41d87ab34e0b\"",
            DorisCancel.sqlKillQueryByTraceId("dg-9f2c41d87ab34e0b"),
        )
        assertEquals(
            "KILL QUERY \"65b9749905fd4677-b6dca64ee8ec2cb0\"",
            DorisCancel.sqlKillQueryByQueryId("65b9749905fd4677-b6dca64ee8ec2cb0"),
        )
        assertEquals("SET fetch_all_fe_for_system_table = true", DorisCancel.SQL_ALL_FE_PROCESSLIST_NEW)
        assertEquals("SET show_all_fe_connection = true", DorisCancel.SQL_ALL_FE_PROCESSLIST_OLD)
        assertEquals("SHOW FULL PROCESSLIST", DorisCancel.SQL_SHOW_FULL_PROCESSLIST)
        assertEquals("DorisTraceId=dg-9f2c41d87ab34e0b", DorisCancel.processlistMarker("dg-9f2c41d87ab34e0b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `kill sql refuses a non-guid`() {
        DorisCancel.sqlKillQueryByTraceId("dg-oops\"; KILL 1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `kill sql refuses a non-query-id`() {
        DorisCancel.sqlKillQueryByQueryId("not-a-query-id!")
    }

    // --- unknown-query-id classification ----------------------------------------------------

    @Test
    fun `unknown query id classified from the live 412 wire message`() {
        // Exact detailMessage observed live on 4.1.2 (vendor code 1105, not 1097 — which is
        // why classification is by message, not by code).
        val live = SQLException("errCode = 2, detailMessage = Unknown query id: dg-neverexists", "HY000", 1105)
        assertTrue(DorisCancel.isUnknownQueryId(live))
    }

    @Test
    fun `unknown query id found through a cause chain`() {
        val root = SQLException("errCode = 2, detailMessage = Unknown query id: dg-1234567890abcdef")
        val wrapped = RuntimeException("remote failure", RuntimeException("relay", root))
        assertTrue(DorisCancel.isUnknownQueryId(wrapped))
    }

    @Test
    fun `other errors are not classified as unknown query id`() {
        assertFalse(DorisCancel.isUnknownQueryId(null))
        assertFalse(DorisCancel.isUnknownQueryId(SQLException("Unknown thread id: 99999")))
        assertFalse(DorisCancel.isUnknownQueryId(SQLException("errCode = 2, detailMessage = Unknown system variable 'show_all_fe_connection'")))
        assertFalse(DorisCancel.isUnknownQueryId(RuntimeException("connection refused")))
    }

    @Test
    fun `unknown query id survives self-referential cause cycles`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b) // cycle
        assertFalse(DorisCancel.isUnknownQueryId(b))
    }

    // --- guid registry -------------------------------------------------------------------------

    @Test
    fun `guid registry is keyed by connection identity`() {
        val a = fakeRemoteConnection()
        val b = fakeRemoteConnection()
        val guidA = DorisCancel.mintGuid()
        val guidB = DorisCancel.mintGuid()
        DorisCancel.registerGuid(a, guidA, "ds-A")
        DorisCancel.registerGuid(b, guidB, "ds-B")
        assertEquals(guidA, DorisCancel.guidForConnection(a))
        assertEquals(guidB, DorisCancel.guidForConnection(b))
        assertEquals(null, DorisCancel.guidForConnection(fakeRemoteConnection()))
        assertEquals(null, DorisCancel.guidForConnection(null))
        // Reconnect semantics: re-registering the same connection replaces the guid.
        DorisCancel.registerGuid(a, guidB, "ds-A")
        assertEquals(guidB, DorisCancel.guidForConnection(a))
    }

    @Test
    fun `per data source registry accumulates guids and is retrievable without a connection`() {
        val ds = "ds-${DorisCancel.mintGuid()}" // unique per run so the shared object stays clean
        assertFalse(DorisCancel.hasAnyGuidForDataSource(ds))
        assertTrue(DorisCancel.guidsForDataSource(ds).isEmpty())

        val g1 = DorisCancel.mintGuid()
        val g2 = DorisCancel.mintGuid()
        // Two connections of the same data source (the two-mint-lines case from the bake log).
        DorisCancel.registerGuid(fakeRemoteConnection(), g1, ds)
        DorisCancel.registerGuid(fakeRemoteConnection(), g2, ds)

        assertTrue(DorisCancel.hasAnyGuidForDataSource(ds))
        assertEquals(setOf(g1, g2), DorisCancel.guidsForDataSource(ds))
        // Null / unknown data source ids are empty, not crashy.
        assertTrue(DorisCancel.guidsForDataSource(null).isEmpty())
        assertFalse(DorisCancel.hasAnyGuidForDataSource(null))
        assertTrue(DorisCancel.guidsForDataSource("never-registered").isEmpty())
    }

    @Test
    fun `registering with a null data source id still records the connection guid`() {
        val c = fakeRemoteConnection()
        val g = DorisCancel.mintGuid()
        DorisCancel.registerGuid(c, g, null)
        assertEquals(g, DorisCancel.guidForConnection(c))
    }

    // --- processlist candidate classification (fallback disambiguation) --------------------------

    @Test
    fun `running-candidate classification excludes idle and self statements`() {
        val marker = "/* ApplicationName=DataGrip, DorisTraceId=dg-9f2c41d87ab34e0b */ INSERT INTO t SELECT 1"
        // A running tagged INSERT: yes.
        assertTrue(DorisCancel.isRunningCancelCandidate("Query", marker))
        // Idle pooled connection (Command=Sleep): no, even if it carries a stale marker.
        assertFalse(DorisCancel.isRunningCancelCandidate("Sleep", marker))
        // The helper's own SHOW / KILL must never match.
        assertFalse(DorisCancel.isRunningCancelCandidate("Query", "SHOW FULL PROCESSLIST"))
        assertFalse(DorisCancel.isRunningCancelCandidate("Query", "KILL QUERY \"dg-9f2c41d87ab34e0b\""))
        // An untagged running query is not ours.
        assertFalse(DorisCancel.isRunningCancelCandidate("Query", "SELECT * FROM t"))
    }

    @Test
    fun `matched guid picks the one marker present`() {
        val g1 = "dg-1111111111111111"
        val g2 = "dg-2222222222222222"
        val info = "/* DorisTraceId=$g2 */ INSERT ..."
        assertEquals(g2, DorisCancel.matchedGuid(info, setOf(g1, g2)))
        assertEquals(null, DorisCancel.matchedGuid(info, setOf(g1)))
        assertEquals(null, DorisCancel.matchedGuid(null, setOf(g1, g2)))
        assertEquals(null, DorisCancel.matchedGuid("no marker here", setOf(g1, g2)))
    }

    private fun fakeRemoteConnection(): com.intellij.database.remote.jdbc.RemoteConnection {
        return java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(com.intellij.database.remote.jdbc.RemoteConnection::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "FakeRemoteConnection"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as com.intellij.database.remote.jdbc.RemoteConnection
    }

    // --- in-flight dedupe state machine (ours-first ordering, P0b) ------------------------------

    @Test
    fun `beginCancel claims the slot once and dedupes repeat presses until endCancel`() {
        val id = System.nanoTime() // unique per run so the shared set stays clean across tests
        assertFalse(DorisCancel.isCancelInFlight(id))

        // First press claims the slot.
        assertTrue(DorisCancel.beginCancel(id))
        assertTrue(DorisCancel.isCancelInFlight(id))

        // Repeat presses while in flight are refused (no re-dispatch, no stock escalation).
        assertFalse(DorisCancel.beginCancel(id))
        assertFalse(DorisCancel.beginCancel(id))
        assertTrue(DorisCancel.isCancelInFlight(id))

        // After our kill resolves, the slot is released and a fresh cancel may start.
        DorisCancel.endCancel(id)
        assertFalse(DorisCancel.isCancelInFlight(id))
        assertTrue(DorisCancel.beginCancel(id))
        DorisCancel.endCancel(id)
    }

    @Test
    fun `in-flight slots are independent per session`() {
        val a = System.nanoTime()
        val b = a + 1
        assertTrue(DorisCancel.beginCancel(a))
        assertTrue(DorisCancel.beginCancel(b)) // different session, own slot
        assertFalse(DorisCancel.beginCancel(a))
        DorisCancel.endCancel(a)
        assertFalse(DorisCancel.isCancelInFlight(a))
        assertTrue(DorisCancel.isCancelInFlight(b)) // ending a must not touch b
        DorisCancel.endCancel(b)
    }

    @Test
    fun `endCancel is idempotent`() {
        val id = System.nanoTime()
        DorisCancel.beginCancel(id)
        DorisCancel.endCancel(id)
        DorisCancel.endCancel(id) // no throw, still not in flight
        assertFalse(DorisCancel.isCancelInFlight(id))
    }

    @Test
    fun `stock fallback is needed only when our path did nothing`() {
        assertTrue(DorisCancel.needsStockFallback(DorisCancel.KillOutcome.NOTHING))
        assertFalse(DorisCancel.needsStockFallback(DorisCancel.KillOutcome.KILLED))
        // STILL_RUNNING is handled by the detach dialog, never by stock (stock can't stop it either).
        assertFalse(DorisCancel.needsStockFallback(DorisCancel.KillOutcome.STILL_RUNNING))
    }

    // --- detach strategy flag ----------------------------------------------------------------

    @Test
    fun `detach is graceful by default and physical only on explicit true`() {
        val old = System.getProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY)
        try {
            System.clearProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY)
            assertFalse(DorisCancel.detachPhysical)
            System.setProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY, "true")
            assertTrue(DorisCancel.detachPhysical)
            System.setProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY, "TRUE")
            assertTrue(DorisCancel.detachPhysical)
            System.setProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY, "false")
            assertFalse(DorisCancel.detachPhysical)
            System.setProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY, "banana")
            assertFalse(DorisCancel.detachPhysical)
        } finally {
            if (old == null) System.clearProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY)
            else System.setProperty(DorisCancel.DETACH_PHYSICAL_PROPERTY, old)
        }
    }

    // --- uncancellable statement classification (message specificity only) -------------------

    @Test
    fun `leading client-info comment is stripped before classifying`() {
        assertEquals("", DorisCancel.stripLeadingComment(null))
        assertEquals("", DorisCancel.stripLeadingComment("   "))
        assertEquals(
            "INSERT INTO t SELECT 1",
            DorisCancel.stripLeadingComment("/* ApplicationName=DataGrip, DorisTraceId=dg-9f2c41d87ab34e0b */ INSERT INTO t SELECT 1"),
        )
        // No comment: text passes through untouched.
        assertEquals("SELECT 1", DorisCancel.stripLeadingComment("SELECT 1"))
        // Unterminated comment: don't chop the string to nothing.
        assertEquals("/* oops INSERT", DorisCancel.stripLeadingComment("/* oops INSERT"))
    }

    @Test
    fun `statement kind is recognised past the trace-id comment`() {
        val comment = "/* ApplicationName=DataGrip, DorisTraceId=dg-9f2c41d87ab34e0b */ "
        assertEquals("INSERT", DorisCancel.describeStatementKind(comment + "INSERT INTO acme_dw.t SELECT * FROM s"))
        assertEquals("INSERT", DorisCancel.describeStatementKind("insert into t values (1)"))
        assertEquals("UPDATE", DorisCancel.describeStatementKind(comment + "UPDATE t SET a = 1"))
        assertEquals("DELETE", DorisCancel.describeStatementKind("DELETE FROM t WHERE a = 1"))
        assertEquals("CREATE TABLE AS SELECT", DorisCancel.describeStatementKind("CREATE TABLE t2 AS SELECT * FROM t1"))
    }

    @Test
    fun `statement kind is null for cancellable or unknown statements`() {
        // SELECTs cancel fine on Doris — no special message.
        assertNull(DorisCancel.describeStatementKind("SELECT * FROM t"))
        assertNull(DorisCancel.describeStatementKind("/* DorisTraceId=dg-9f2c41d87ab34e0b */ SELECT count(*) FROM big"))
        assertNull(DorisCancel.describeStatementKind(null))
        assertNull(DorisCancel.describeStatementKind(""))
        // Plain CREATE without a query body is fast/DDL — not the slow uncancellable case.
        assertNull(DorisCancel.describeStatementKind("CREATE TABLE t (a INT)"))
    }

    // --- dbms guard --------------------------------------------------------------------------

    @Test
    fun `guard requires both the doris dbms and the flag`() {
        assertTrue(DorisCancel.shouldHandle(DorisDbms.DORIS, flagEnabled = true))
        assertFalse(DorisCancel.shouldHandle(DorisDbms.DORIS, flagEnabled = false))
        assertFalse(DorisCancel.shouldHandle(Dbms.MYSQL, flagEnabled = true))
        assertFalse(DorisCancel.shouldHandle(Dbms.POSTGRES, flagEnabled = true))
        assertFalse(DorisCancel.shouldHandle(null, flagEnabled = true))
        assertFalse(DorisCancel.shouldHandle(null, flagEnabled = false))
    }
}
