package dev.sort.doris.cancel

import com.intellij.database.Dbms
import dev.sort.doris.DorisDbms
import java.sql.SQLException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        DorisCancel.registerGuid(a, guidA)
        DorisCancel.registerGuid(b, guidB)
        assertEquals(guidA, DorisCancel.guidFor(a))
        assertEquals(guidB, DorisCancel.guidFor(b))
        assertEquals(null, DorisCancel.guidFor(fakeRemoteConnection()))
        assertEquals(null, DorisCancel.guidFor(null))
        // Reconnect semantics: re-registering the same connection replaces the guid.
        DorisCancel.registerGuid(a, guidB)
        assertEquals(guidB, DorisCancel.guidFor(a))
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
