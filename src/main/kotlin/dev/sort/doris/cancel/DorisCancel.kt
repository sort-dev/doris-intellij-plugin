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

    /**
     * Detach strategy for an uncancellable statement (see [KillOutcome.STILL_RUNNING]). Default is
     * the graceful platform disconnect (a `DataRequest.Disconnect` through the console's own data
     * producer — the same seam `SessionsUtil.disconnect` uses). If the IDE bake shows the graceful
     * disconnect cannot force through the console thread while it is blocked in the master-forward
     * RPC, set `-Ddoris.cancel.detach.physical=true` to instead close the console's `RemoteConnection`
     * directly, which interrupts the blocked socket read. Read per-access, same rationale as [enabled].
     */
    const val DETACH_PHYSICAL_PROPERTY: String = "doris.cancel.detach.physical"

    val detachPhysical: Boolean
        get() = "true".equals(System.getProperty(DETACH_PHYSICAL_PROPERTY), ignoreCase = true)

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

    /**
     * Classify a `SHOW FULL PROCESSLIST` row for the fallback: is it a *running* statement we
     * could legitimately cancel? A `Command` of `Sleep` is an idle pooled connection (not
     * executing), and we must never match the helper connection's own `SHOW FULL PROCESSLIST`
     * or the `KILL QUERY` it is about to issue. Everything else with a `Query` command that
     * carries one of our markers is a candidate. Pure + unit-tested.
     */
    fun isRunningCancelCandidate(command: String?, info: String?): Boolean {
        val cmd = command?.trim().orEmpty()
        if (cmd.equals("Sleep", ignoreCase = true)) return false
        val sql = info.orEmpty()
        if (sql.contains(SQL_SHOW_FULL_PROCESSLIST, ignoreCase = true)) return false
        if (sql.contains("KILL QUERY", ignoreCase = true)) return false
        return sql.contains(CLIENT_INFO_KEY)
    }

    /** Extract which of our minted guids a processlist `Info` value carries, if any. */
    fun matchedGuid(info: String?, candidateGuids: Set<String>): String? {
        val sql = info ?: return null
        return candidateGuids.firstOrNull { sql.contains(processlistMarker(it)) }
    }

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
    // Guid registries
    // ---------------------------------------------------------------------------------------

    /**
     * Weak map keyed by the connection's [RemoteConnection] — a secondary lookup for the
     * in-process path. The **primary** cancel-time lookup reads the guid straight back off the
     * driver (`RemoteConnection.getClientInfo("DorisTraceId")`), which is self-describing and
     * needs no identity assumptions; this map is the belt-and-braces fallback for that step.
     * Entries die with the connection; a reconnect produces a new `RemoteConnection`, re-runs
     * the interceptor, and gets a fresh guid — no explicit lifecycle management needed.
     */
    private val guidsByConnection: MutableMap<RemoteConnection, String> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * Per-data-source set of every guid we have minted (keyed by [LocalDataSource.getUniqueId]).
     *
     * This is the **robust, connection-independent** registry the processlist safety net uses:
     * at cancel time we may hold no live connection object at all (the P0 that motivated it), but
     * we always know the session's data source, and every statement of every tagged connection
     * carries its guid in the processlist `Info` comment. So "kill the running query for this data
     * source whose `Info` marker is one of ours" works with only the data-source id in hand.
     *
     * Guids accumulate for the JVM session (one per connection; a data source has few). Stale
     * guids from closed connections are harmless — their queries are gone, so they never match a
     * *running* processlist row.
     */
    private val guidsByDataSource: MutableMap<String, MutableSet<String>> =
        Collections.synchronizedMap(HashMap())

    /**
     * Called at mint time: record the guid under the connection and its data source.
     *
     * There is deliberately NO per-session registry here: connect time cannot see which console
     * owns the connection (the requestor `JdbcEngine` retains no session field, and the
     * run-config/audit handles are shared or recreated across a data source's consoles — all
     * verified live). Session scoping instead happens at CANCEL time, by matching the live
     * request-owner of each active connection (see `DorisCancelRunningStatementsAction`).
     */
    fun registerGuid(connection: RemoteConnection, guid: String, dataSourceId: String?) {
        guidsByConnection[connection] = guid
        if (dataSourceId != null) {
            val set = synchronized(guidsByDataSource) {
                guidsByDataSource.getOrPut(dataSourceId) {
                    Collections.synchronizedSet(HashSet())
                }
            }
            set.add(guid)
        }
    }

    /** Secondary in-process lookup: the guid keyed by the (busy or idle) connection identity. */
    fun guidForConnection(connection: RemoteConnection?): String? =
        connection?.let { guidsByConnection[it] }

    /** Every guid ever minted for this data source (for the processlist safety net). */
    fun guidsForDataSource(dataSourceId: String?): Set<String> =
        dataSourceId?.let { id ->
            synchronized(guidsByDataSource) { guidsByDataSource[id]?.toSet() }
        } ?: emptySet()

    /** True if we ever tagged any connection of this data source (vs. genuinely pre-plugin). */
    fun hasAnyGuidForDataSource(dataSourceId: String?): Boolean =
        guidsForDataSource(dataSourceId).isNotEmpty()

    // ---------------------------------------------------------------------------------------
    // In-flight cancel dedupe (ours-first ordering; P0b)
    // ---------------------------------------------------------------------------------------

    /**
     * Outcome of our server-side kill attempt.
     *
     * - [KILLED] (or already-finished) — the success path: the row is gone after the kill, so the
     *   console statement unblocks when the server errors it and the stock cancel is **suppressed**.
     * - [NOTHING] — our server path definitively did nothing (no guid, no running tagged row,
     *   ambiguous, or the helper failed). The stock cancel then runs as the best remaining effort,
     *   but only if the bare client-side cancel (fired before the kill; see
     *   `DorisCancelRunningStatementsAction.cancelPendingClientWork`) did not already settle the
     *   session; then its "Deactivate the Data Source?" dialog is legitimate.
     * - [STILL_RUNNING] — the FE *accepted* `KILL QUERY` (returned OK) but a re-scan a moment later
     *   still finds the same `QueryId` running: the upstream Nereids INSERT/CTAS cancel bug (the FE's
     *   `StmtExecutor.cancel` only cancels the query coordinator, never the insert coordinator
     *   registered in `QeProcessorImpl`; verified 4.1.3 + master). Nothing client- or server-side can
     *   stop it, so we neither retry nor fall back to stock — instead we offer the user a detach so
     *   they get their console back while the statement finishes on the server.
     */
    enum class KillOutcome { KILLED, NOTHING, STILL_RUNNING }

    /** Whether a [KillOutcome] means we should fall back to the stock cancel. */
    fun needsStockFallback(outcome: KillOutcome): Boolean = outcome == KillOutcome.NOTHING

    // ---------------------------------------------------------------------------------------
    // Uncancellable-statement classification (message specificity only — never a gate)
    // ---------------------------------------------------------------------------------------

    /**
     * Best-effort human label for the *kind* of an uncancellable statement, read from its
     * processlist `Info` text — used ONLY to make the detach dialog specific ("this INSERT" vs
     * "this statement"). Never used to decide whether a kill will work: that decision is always the
     * behavioural verify-after-kill re-scan, so a classifier miss just yields a slightly-less-specific
     * message, never a wrong action.
     *
     * Strips a leading Connector/J block client-info comment (our trace-id marker rides in there)
     * before matching the first keyword. Pure + unit-tested.
     */
    fun describeStatementKind(info: String?): String? {
        val sql = stripLeadingComment(info).trimStart()
        if (sql.isEmpty()) return null
        val head = sql.uppercase()
        return when {
            head.startsWith("INSERT") -> "INSERT"
            head.startsWith("UPDATE") -> "UPDATE"
            head.startsWith("DELETE") -> "DELETE"
            // CREATE TABLE ... AS SELECT / CREATE TABLE ... LIKE — the write half is the slow, killable-in-name-only part.
            head.startsWith("CREATE") && (head.contains(" AS ") || head.contains(" SELECT")) -> "CREATE TABLE AS SELECT"
            else -> null
        }
    }

    /** Strip a single leading SQL block comment, e.g. Connector/J's client-info comment. */
    fun stripLeadingComment(info: String?): String {
        val s = info?.trimStart() ?: return ""
        if (!s.startsWith("/*")) return s
        val end = s.indexOf("*/")
        return if (end < 0) s else s.substring(end + 2).trimStart()
    }

    /** Session ids with a Doris cancel currently dispatched (its helper kill in flight). */
    private val inFlightCancels: MutableSet<Long> =
        Collections.synchronizedSet(HashSet())

    /**
     * Claim the in-flight slot for a session. Returns `true` if this call started a new cancel,
     * `false` if one is already in flight (a repeat Stop press) — in which case the caller must
     * **not** dispatch again nor escalate to the stock deactivate dialog.
     */
    fun beginCancel(sessionId: Long): Boolean = inFlightCancels.add(sessionId)

    /** Release the in-flight slot once our kill resolves (success or definitive failure). */
    fun endCancel(sessionId: Long) {
        inFlightCancels.remove(sessionId)
    }

    /** Test/diagnostic view. */
    fun isCancelInFlight(sessionId: Long): Boolean = inFlightCancels.contains(sessionId)
}
