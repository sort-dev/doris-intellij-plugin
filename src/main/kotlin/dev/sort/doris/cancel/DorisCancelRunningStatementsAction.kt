package dev.sort.doris.cancel

import com.intellij.database.actions.CancelRunningStatementsAction
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager

/**
 * Replaces `Console.Jdbc.Cancel` (`overrides="true"` in plugin.xml) — cancel-time half of the
 * clean Doris cancel (RESEARCH-query-cancel.md; see [DorisCancel] for the design and the live
 * 4.1.2 verification table).
 *
 * For non-Doris sessions (or with `-Ddoris.cancel.experimental=false`, or on the second press
 * where the stock action escalates to deactivate-with-confirmation) this is byte-for-byte the
 * stock [CancelRunningStatementsAction] — `performAction` delegates straight to `super`.
 *
 * ## Ordering — ours first, stock only as a genuine fallback (P0b)
 *
 * The first cut ran stock **first**, ours after. Behind a k8s LB stock's `KILL QUERY <conn-id>`
 * hits the wrong FE and *fails*, so the stock "Cancelling of Running Statements Failed. Deactivate
 * the Data Source?" dialog popped even though our kill landed ~0.5s later and killed the query
 * (confirmed live on the user's prod multi-FE cluster: the red *"cancel query by user from
 * 10.244.7.112"* bar is our helper connection's cross-FE kill). The dialog was pure noise in the
 * success case.
 *
 * Now, for a Doris session with any tagged connection:
 * - our kill is dispatched to a pooled thread and the **stock cancel is suppressed** in the
 *   success path. Our server-side `KILL QUERY` makes the console's running statement return
 *   *"cancel query by user"*, which unblocks the client on its own — so stock is unnecessary.
 * - the stock cancel runs **only when our path definitively did nothing** ([DorisCancel.KillOutcome.NOTHING]:
 *   no guid resolvable, no running tagged row, an ambiguous multi-match, or the helper failed);
 *   then its dialog is legitimate. That decision is only known after the async kill, so the stock
 *   fallback is posted back to the EDT.
 * - if we never tagged this data source at all (connected before the plugin/flag), there is no
 *   hope for our path, so we delegate to stock **immediately and synchronously** — no dispatch.
 * - a repeat Stop press while our kill is in flight is de-duped ([DorisCancel.beginCancel]) so it
 *   does not prematurely escalate to stock's deactivate dialog.
 *
 * **Accepted trade-off:** we give up stock's *instant* client-side unblock for a no-spurious-dialog
 * experience; the unblock now happens when our kill lands (~0.5s, the server erroring the
 * statement) instead of instantly. The user explicitly finds the false dialog worse than the wait.
 * Stock's client-side `Statement.cancel()` cannot be fired without also arming its server-failure
 * dialog (the failure dialog is wired into the stock cancel request's `onError`, DB-261 bytecode),
 * so the two cannot be decoupled — hence ours-only in the success path.
 *
 * ## Resolving *which* query to kill
 *
 * Cancel fires while the console's statement is executing. The guid for *this* console lives on
 * its connection, but `session.messageBus.dataProducer` is a one-method `DataProducer` JDK proxy,
 * so the connection (and its guid) cannot be reflected off it — the original cut tried and always
 * failed, falling through to the data-source-wide net below (which then refused whenever a second
 * query, e.g. a background copy in another console, was running on the same data source). Instead
 * we match **live at cancel time**: a console connection's requestor is its `JdbcEngine` (a public
 * class — it *is* the `DataProducer` behind that proxy), and while the console is executing, the
 * engine's `getRequestContextIfAny()` exposes the active request, whose public `owner` is the
 * session client that submitted it. Cancel fires exactly while the target session is mid-query, so
 * enumerating `DatabaseConnectionManager.getActiveConnections()` and keeping the connection(s)
 * whose live request-owner belongs to this session pins down *this console's* connection — and our
 * connect-time weak map turns it into the guid, with no remote call. Nothing is registered
 * per-session ahead of time, so reconnects can never leave the mapping stale. (Session-identity
 * keys that do NOT work, learned the hard way: `ConsoleRunConfiguration` and the audit handles are
 * shared/recreated across consoles of a data source; `JdbcEngine` holds no session field to
 * reflect.)
 *
 * The guid is then used only as the **`Info`-marker to match in `SHOW FULL PROCESSLIST`**, from
 * which we read the running query's real `QueryId` and `KILL QUERY "<QueryId>"`. We do *not* kill
 * by trace id directly: `KILL QUERY "<trace-id>"` returns success but is a silent no-op on Doris
 * 4.1.2 (the query keeps running), whereas the QueryId kill is live-proven.
 *
 * **Data-source safety net (last resort):** if no console guid resolves, we match against every
 * guid minted for the *data source* instead. More than one running match -> refuse (avoid the
 * wrong-kill this feature exists to prevent) and leave it to stock. With per-console resolution
 * above now working, this net is reached only when the config registry and active-connection
 * lookup both come up empty.
 *
 * Every resolution step logs under the `DorisCancel:` prefix so a real-IDE press leaves a
 * definitive trail (the flow is not headless-drivable).
 */
class DorisCancelRunningStatementsAction : CancelRunningStatementsAction() {

    override fun performAction(e: AnActionEvent, session: DatabaseSession) {
        val handleDoris = DorisCancel.shouldHandle(session.connectionPoint.dbms) &&
            !session.isCancelled
        if (!handleDoris) {
            // Non-Doris, flag-off, or the deactivate-escalation second press: pure stock.
            super.performAction(e, session)
            return
        }

        val dataSourceId = dataSourceId(session)

        // No hope for our path (never tagged this data source: connected before the plugin/flag)?
        // Delegate to stock immediately and synchronously — its dialog is legitimate.
        if (!DorisCancel.hasAnyGuidForDataSource(dataSourceId)) {
            DorisCancel.info(
                "session '${session.title}' (ds=$dataSourceId): no trace id ever minted for this " +
                    "data source (connected before the plugin/flag?); delegating to stock",
            )
            super.performAction(e, session)
            return
        }

        // Dedupe: a second Stop press while our kill is in flight must not re-dispatch nor
        // escalate to stock's deactivate dialog.
        if (!DorisCancel.beginCancel(session.id)) {
            DorisCancel.info(
                "session '${session.title}': a Doris cancel is already in flight; ignoring repeat press",
            )
            return
        }

        DorisCancel.info(
            "session '${session.title}' (ds=$dataSourceId): our kill dispatched, stock suppressed " +
                "unless our path finds nothing (success path)",
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = try {
                killOnServer(session, dataSourceId)
            } catch (t: Throwable) {
                DorisCancel.warn("session '${session.title}': kill dispatch threw: ${t.message}")
                DorisCancel.KillOutcome.NOTHING
            } finally {
                DorisCancel.endCancel(session.id)
            }
            if (DorisCancel.needsStockFallback(outcome)) {
                DorisCancel.info(
                    "session '${session.title}': our path found nothing to kill; delegating to stock as fallback",
                )
                ApplicationManager.getApplication().invokeLater {
                    if (!session.isCancelled) runStockCancel(e, session)
                }
            }
        }
    }

    /** Bridge so the stock (super) cancel can be invoked from an async continuation. */
    private fun runStockCancel(e: AnActionEvent, session: DatabaseSession) {
        super.performAction(e, session)
    }

    private fun dataSourceId(session: DatabaseSession): String? =
        try {
            session.connectionPoint.dataSource.uniqueId
        } catch (t: Throwable) {
            DorisCancel.warn("could not read data source id: ${t.message}")
            null
        }

    // ---------------------------------------------------------------------------------------
    // Session resolution: this console's guid(s) via the public active-connection registry
    // ---------------------------------------------------------------------------------------

    /**
     * The guid(s) of the connection(s) backing THIS session.
     *
     * Replaces the original proxy reflection (see the class KDoc): we enumerate the platform's
     * public active-connection list and keep only the connection(s) whose `ConsoleRunConfiguration`
     * is identity-equal to this session's — each console owns a distinct run-config instance,
     * threaded by identity into the connection it opens, so config identity isolates this console
     * from other consoles on the same data source. The guid is then read from our connect-time
     * registry (or, on a miss, straight off the driver via `getClientInfo` — a safe direct call now
     * that we hold the real connection, not the proxy). Usually one guid; a session that reconnected
     * can carry more than one (stale ones simply never match a *running* processlist row).
     */
    private fun sessionGuids(session: DatabaseSession): Set<String> {
        val id = session.id
        val clients = try {
            session.clients
        } catch (t: Throwable) {
            emptyArray()
        }
        val active = try {
            DatabaseConnectionManager.getInstance().activeConnections
        } catch (t: Throwable) {
            DorisCancel.warn("sessionGuids: activeConnections unavailable: ${t.message}")
            return emptySet()
        }
        // Live match: a console connection's requestor is its JdbcEngine, and WHILE the console is
        // executing, the engine exposes the active request's context, whose request carries its
        // OWNER — the session client that submitted it. Cancel fires exactly while the target
        // session is mid-query, so its engine has a context and the owner names the session. All
        // public API, read fresh at every press — nothing registered ahead of time, so reconnects
        // can never leave this stale.
        val mine = active.filter { conn ->
            runCatching {
                val owner = (conn.requestor as? JdbcEngine)?.requestContextIfAny?.request?.owner
                    ?: return@runCatching false
                val ownerSessionId = ((owner as? SessionClient<*>)?.session as? DatabaseSession)?.id
                ownerSessionId == id || clients.any { it === owner }
            }.getOrDefault(false)
        }
        val guids = mine.mapNotNullTo(LinkedHashSet()) { guidFromConnection(it) }
        DorisCancel.info(
            "sessionGuids: '${session.title}' id=$id -> ${guids.size} guid(s) via " +
                "${mine.size}/${active.size} live request-owner match(es)",
        )
        return guids
    }

    /**
     * Read the guid from a resolved connection: connect-time weak map first (instant, in-process),
     * driver client-info second. Order matters: `getClientInfo` is a remote call that can stall for
     * seconds behind the busy connection's running statement (observed 8s live), while the weak map
     * — populated at mint for every tagged connection — answers immediately.
     */
    private fun guidFromConnection(connection: DatabaseConnection): String? {
        val remote: RemoteConnection = try {
            connection.remoteConnection
        } catch (t: Throwable) {
            DorisCancel.warn("resolve: remoteConnection unavailable: ${t.message}")
            return null
        }
        val fromMap = DorisCancel.guidForConnection(remote)
        if (DorisCancel.isValidGuid(fromMap)) {
            DorisCancel.info("resolve: guid from connect-time weak map -> $fromMap")
            return fromMap
        }
        val fromDriver = try {
            JdbcNativeUtil.computeRemote { remote.getClientInfo(DorisCancel.CLIENT_INFO_KEY) }
        } catch (t: Throwable) {
            DorisCancel.info("resolve: getClientInfo(${DorisCancel.CLIENT_INFO_KEY}) failed: ${t.message}")
            null
        }
        if (DorisCancel.isValidGuid(fromDriver)) {
            DorisCancel.info("resolve: guid from driver client-info -> $fromDriver")
            return fromDriver
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Server-side kill (pooled thread; one short-lived helper connection)
    // ---------------------------------------------------------------------------------------

    /**
     * Resolve this session's guid(s) and kill, on a pooled thread through one short-lived helper
     * connection. We always kill via the **processlist -> real `QueryId`** path: it is the only one
     * live-proven on the user's cluster. (`KILL QUERY "<trace-id>"` returns success but is a silent
     * no-op on Doris 4.1.2 — the running query keeps going — so the trace id is used only as the
     * `Info`-marker we match in the processlist, never as a direct kill argument.)
     *
     * Guid scoping, best -> last resort:
     *  - **this console's** guids (`session.configuration` registry) — kills only this console's
     *    running query, so a background copy in another console on the same data source is ignored;
     *  - if none resolve, **the data source's** guids — the old, ambiguity-prone net, which refuses
     *    on more than one running match and leaves it to stock.
     *
     * Returns [DorisCancel.KillOutcome.KILLED] when the server accepted a kill (or the row was
     * already gone) and [DorisCancel.KillOutcome.NOTHING] when we did nothing — the caller then
     * runs the stock fallback.
     */
    private fun killOnServer(session: DatabaseSession, dataSourceId: String?): DorisCancel.KillOutcome {
        val scoped = sessionGuids(session)
        val guids = if (scoped.isNotEmpty()) scoped else DorisCancel.guidsForDataSource(dataSourceId)
        val context =
            if (scoped.isNotEmpty()) "session '${session.title}'"
            else "data-source safety net for '${session.title}' (ds=$dataSourceId)"
        if (guids.isEmpty()) {
            DorisCancel.info("$context: no guids to match; nothing to kill (stock will handle it)")
            return DorisCancel.KillOutcome.NOTHING
        }
        return try {
            val ref = DatabaseConnectionManager.getInstance()
                .build(session.project, session.connectionPoint)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
                ?: throw IllegalStateException("no helper connection")
            ref.use { r ->
                val helper = r.get()
                DorisCancel.info("$context: matching ${guids.size} guid(s) in the processlist")
                widenProcesslist(helper)
                val candidates = collectRunningCandidates(helper, guids)
                killSingleCandidate(helper, candidates, context)
            }
        } catch (t: Throwable) {
            DorisCancel.warn("helper connection for kill failed: ${t.message}")
            DorisCancel.KillOutcome.NOTHING
        }
    }

    /** Kill exactly one candidate; refuse (log) on zero or ambiguous multiple. */
    private fun killSingleCandidate(
        helper: DatabaseConnection,
        candidates: List<Candidate>,
        context: String,
    ): DorisCancel.KillOutcome {
        when {
            candidates.isEmpty() -> {
                DorisCancel.info("$context: no running tagged query found; nothing to kill (stock will handle it)")
                return DorisCancel.KillOutcome.NOTHING
            }
            candidates.size > 1 -> {
                val ids = candidates.joinToString { "${it.queryId}(${it.guid})" }
                DorisCancel.warn(
                    "$context: ${candidates.size} running tagged queries [$ids]; ambiguous, " +
                        "refusing to kill to avoid a wrong-kill; leaving it to stock",
                )
                return DorisCancel.KillOutcome.NOTHING
            }
            else -> {
                val c = candidates.single()
                if (!DorisCancel.isValidQueryId(c.queryId)) {
                    DorisCancel.warn("$context: refusing to kill malformed query id '${c.queryId}'")
                    return DorisCancel.KillOutcome.NOTHING
                }
                DorisCancel.info("$context: issuing ${DorisCancel.sqlKillQueryByQueryId(c.queryId)} (guid=${c.guid})")
                return try {
                    execute(helper, DorisCancel.sqlKillQueryByQueryId(c.queryId))
                    DorisCancel.info("$context: killed query id ${c.queryId}")
                    DorisCancel.KillOutcome.KILLED
                } catch (t: Throwable) {
                    if (DorisCancel.isUnknownQueryId(t)) {
                        DorisCancel.info("$context: query id ${c.queryId} already gone")
                        DorisCancel.KillOutcome.KILLED
                    } else {
                        DorisCancel.warn("$context: KILL QUERY \"${c.queryId}\" failed: ${t.message}")
                        DorisCancel.KillOutcome.NOTHING
                    }
                }
            }
        }
    }

    /** Widen the processlist to all FEs (both variable generations; failures ignored). */
    private fun widenProcesslist(helper: DatabaseConnection) {
        for (sql in listOf(DorisCancel.SQL_ALL_FE_PROCESSLIST_NEW, DorisCancel.SQL_ALL_FE_PROCESSLIST_OLD)) {
            try {
                execute(helper, sql)
                DorisCancel.info("processlist: '$sql' OK")
                return
            } catch (t: Throwable) {
                DorisCancel.info("processlist: '$sql' rejected (${t.message?.take(100)}); trying older name")
            }
        }
    }

    private data class Candidate(val guid: String, val queryId: String)

    /**
     * `SHOW FULL PROCESSLIST` scan: every *running* row whose `Info` marker matches one of
     * [guids]. Idle (`Command=Sleep`) rows and the helper's own SHOW/KILL statements are excluded
     * by [DorisCancel.isRunningCancelCandidate].
     */
    private fun collectRunningCandidates(helper: DatabaseConnection, guids: Set<String>): List<Candidate> {
        val statement = JdbcNativeUtil.computeRemote {
            helper.remoteConnection.createStatement()
        } ?: return emptyList()
        try {
            val resultSet = JdbcNativeUtil.computeRemote {
                statement.executeQuery(DorisCancel.SQL_SHOW_FULL_PROCESSLIST)
            } ?: return emptyList()
            try {
                val metadata = resultSet.metaData
                var queryIdIndex = -1
                var infoIndex = -1
                var commandIndex = -1
                for (i in 1..metadata.columnCount) {
                    when (metadata.getColumnLabel(i).orEmpty().lowercase()) {
                        "queryid" -> queryIdIndex = i
                        "info" -> infoIndex = i
                        "command" -> commandIndex = i
                    }
                }
                if (queryIdIndex < 0 || infoIndex < 0) {
                    DorisCancel.info("processlist: result lacks QueryId/Info columns")
                    return emptyList()
                }
                val out = ArrayList<Candidate>()
                while (resultSet.next()) {
                    val info = resultSet.getString(infoIndex) ?: continue
                    val command = if (commandIndex > 0) resultSet.getString(commandIndex) else null
                    if (!DorisCancel.isRunningCancelCandidate(command, info)) continue
                    val guid = DorisCancel.matchedGuid(info, guids) ?: continue
                    val queryId = resultSet.getString(queryIdIndex) ?: continue
                    out.add(Candidate(guid, queryId))
                }
                DorisCancel.info("processlist: ${out.size} running tagged candidate(s)")
                return out
            } finally {
                JdbcNativeUtil.performSafe { resultSet.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    private fun execute(connection: DatabaseConnection, sql: String) {
        val statement = JdbcNativeUtil.computeRemote {
            connection.remoteConnection.createStatement()
        } ?: throw IllegalStateException("could not create statement")
        try {
            JdbcNativeUtil.computeRemote { statement.execute(sql) }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }
}
