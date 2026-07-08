package dev.sort.doris.cancel

import com.intellij.database.Dbms
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.openapi.diagnostic.Logger
import dev.sort.doris.DorisDbms
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Clean Doris query cancel (RESEARCH-query-cancel.md, Leg 3 Option 1).
 *
 * ## The problem this fixes
 *
 * DataGrip's Stop button ends in MySQL Connector/J `Statement.cancel()`, which opens a **second
 * connection to the same JDBC URL** and issues `KILL QUERY <connection-id>`. Behind a k8s LB that
 * second connection can land on a *different* FE where the per-FE connection id either doesn't
 * exist (error swallowed by the platform's `runWithoutReporting` — the observed silent no-op) or
 * belongs to **someone else's session** (per-FE `AtomicInteger(0)` id counters collide), silently
 * killing the wrong query. Verified against Connector/J 9.7.0 sources and Doris FE master.
 *
 * ## The recipe (live-verified on Doris 4.1.2, 2026-07-08)
 *
 * - **Connect time** ([DorisTraceIdConnectionInterceptor]): mint a per-connection guid
 *   `dg-<16 hex>`, bind it as the Doris **trace id** (`SET session_context = 'trace_id:<guid>'`),
 *   and mirror it into the driver's per-connection statement comment via
 *   `RemoteConnection.setClientInfo("DorisTraceId", <guid>)` so every statement carries a
 *   server-visible `/* ..., DorisTraceId=dg-... */` fingerprint in `SHOW PROCESSLIST` `Info`.
 * - **Cancel time** ([DorisCancelRunningStatementsAction]): from a short-lived helper connection
 *   to the same data source (any FE), `KILL QUERY "<guid>"` — on Doris >= 4.0 the FE forwards the
 *   kill to every alive FE until one owns the trace id. "Unknown query id" back means nothing was
 *   running: success, silent. Pre-4.0 / trace-id-lost fallback: all-FE `SHOW FULL PROCESSLIST`,
 *   match the `Info` marker, `KILL QUERY "<QueryId>"`.
 *
 * ## Flag
 *
 * `-Ddoris.cancel.experimental=false` disables everything (interceptor becomes inactive, the
 * cancel action delegates straight to stock). Default **ON** — the plugin path is strictly better
 * than the broken stock path. Same per-access read rationale as [dev.sort.doris.DorisCatalogs]:
 * production reads a session-constant VM option; tests pin both modes through the JVM-global
 * system property because the platform fixture loads plugin classes in a separate classloader.
 */
object DorisCancel {

    /** System property switch. Default: **enabled**; only an explicit `"false"` disables. */
    const val PROPERTY: String = "doris.cancel.experimental"

    /** Same parsing rule as `DorisCatalogs.isEnabledValue` (unit-tested pure function). */
    fun isEnabledValue(raw: String?): Boolean = !"false".equals(raw, ignoreCase = true)

    /** Read from the system property on each access (testability; see class KDoc). */
    val enabled: Boolean
        get() = isEnabledValue(System.getProperty(PROPERTY))

    /** All cancel-path log lines carry this prefix so a runtime tester can `grep idea.log`. */
    const val LOG_PREFIX: String = "DorisCancel:"

    private val log = Logger.getInstance(DorisCancel::class.java)

    fun info(message: String) = log.info("$LOG_PREFIX $message")

    fun warn(message: String, t: Throwable? = null) =
        if (t != null) log.warn("$LOG_PREFIX $message", t) else log.warn("$LOG_PREFIX $message")

    // ---------------------------------------------------------------------------------------
    // Guid: the per-connection cancel handle
    // ---------------------------------------------------------------------------------------

    /** Client-info key merged into the driver's per-connection statement comment. */
    const val CLIENT_INFO_KEY: String = "DorisTraceId"

    private val GUID_REGEX = Regex("dg-[0-9a-f]{16}")

    /** Mint a fresh per-connection guid: `dg-` + 16 lowercase hex chars. */
    fun mintGuid(): String {
        val bits = ThreadLocalRandom.current().nextLong()
        return "dg-" + java.lang.Long.toHexString(bits).padStart(16, '0')
    }

    /** Format check for anything we are about to embed in kill SQL. */
    fun isValidGuid(value: String?): Boolean = value != null && GUID_REGEX.matches(value)

    /**
     * `QueryId` values from `SHOW PROCESSLIST` (e.g. `65b9749905fd4677-b6dca64ee8ec2cb0`).
     * Validated before embedding in SQL — the value comes from a server result set.
     */
    fun isValidQueryId(value: String?): Boolean =
        !value.isNullOrBlank() && value.matches(Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}"))

    // ---------------------------------------------------------------------------------------
    // The SQL building blocks (each verified live against Doris 4.1.2 — see the research doc)
    // ---------------------------------------------------------------------------------------

    /** Connect-time trace-id bind; the FE maps it onto every subsequent query of the session. */
    fun sqlSetSessionContext(guid: String): String {
        require(isValidGuid(guid)) { "not a Doris cancel guid: $guid" }
        return "SET session_context = 'trace_id:$guid'"
    }

    /** The one-shot kill: on Doris >= 4.0 forwarded FE-to-FE until an owner is found. */
    fun sqlKillQueryByTraceId(guid: String): String {
        require(isValidGuid(guid)) { "not a Doris cancel guid: $guid" }
        return "KILL QUERY \"$guid\""
    }

    /** Fallback kill by the `QueryId` string discovered in the processlist. */
    fun sqlKillQueryByQueryId(queryId: String): String {
        require(isValidQueryId(queryId)) { "not a Doris query id: $queryId" }
        return "KILL QUERY \"$queryId\""
    }

    /** Current variable name (renamed + default-true since Sep 2025 / 4.x). */
    const val SQL_ALL_FE_PROCESSLIST_NEW: String = "SET fetch_all_fe_for_system_table = true"

    /** Pre-rename variable name (2.1/3.x); on 4.1.2 this errors and is safely ignored. */
    const val SQL_ALL_FE_PROCESSLIST_OLD: String = "SET show_all_fe_connection = true"

    const val SQL_SHOW_FULL_PROCESSLIST: String = "SHOW FULL PROCESSLIST"

    /** The `Info`-column marker the fallback greps for (driver comment fingerprint). */
    fun processlistMarker(guid: String): String = "$CLIENT_INFO_KEY=$guid"

    // ---------------------------------------------------------------------------------------
    // Error classification
    // ---------------------------------------------------------------------------------------

    /**
     * `KILL QUERY "<id>"` with no owner anywhere answers ERR_NO_SUCH_QUERY. Live 4.1.2 wire text:
     * `ERROR 1105 (HY000): errCode = 2, detailMessage = Unknown query id: dg-neverexists` —
     * the stable part is the message, not the vendor code, so classify by substring across the
     * cause chain. For a cancel this means "nothing is running": treat as success, stay silent.
     */
    fun isUnknownQueryId(t: Throwable?): Boolean {
        var current: Throwable? = t
        var hops = 0
        while (current != null && hops < 10) {
            if (current.message?.contains("Unknown query id", ignoreCase = true) == true) return true
            current = current.cause?.takeIf { it !== current }
            hops++
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Routing guards (pure, unit-tested)
    // ---------------------------------------------------------------------------------------

    /** The dbms guard both the interceptor and the action branch on. */
    fun shouldHandle(dbms: Dbms?, flagEnabled: Boolean = enabled): Boolean =
        flagEnabled && dbms === DorisDbms.DORIS

    // ---------------------------------------------------------------------------------------
    // Guid registry: connection identity -> guid
    // ---------------------------------------------------------------------------------------

    /**
     * Weak map keyed by the connection's [RemoteConnection] — the one object identity that is
     * stable across the platform's `DatabaseConnection` wrappers (`ConnectionWithClient` etc.
     * all delegate `getRemoteConnection()` to the same instance). Entries die with the
     * connection; a reconnect produces a new `RemoteConnection`, passes through the interceptor
     * again, and gets a fresh guid — no explicit lifecycle management needed.
     */
    private val guidsByConnection: MutableMap<RemoteConnection, String> =
        Collections.synchronizedMap(WeakHashMap())

    fun registerGuid(connection: RemoteConnection, guid: String) {
        guidsByConnection[connection] = guid
    }

    fun guidFor(connection: RemoteConnection?): String? =
        connection?.let { guidsByConnection[it] }
}
