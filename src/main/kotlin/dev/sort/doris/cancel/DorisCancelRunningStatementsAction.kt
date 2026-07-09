package dev.sort.doris.cancel

import com.intellij.database.actions.CancelRunningStatementsAction
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
 * ## Resolving *which* query to kill — the P0 fix
 *
 * Cancel fires exactly while a statement is executing, so the session's connection is **busy**.
 * `JdbcEngine.getCurrentConnectionIfReady()` returns `null` for a busy connection by design
 * (`isConnectionReady` gate, DB-261 bytecode) — the first cut resolved the guid through it and so
 * *always* missed mid-query and fell to stock (the observed "no trace id known" -> deactivate
 * dialog). The busy connection is reachable through the private `JdbcEngine.getCurrentConnection()`
 * (returns `myConnection.get()` with no ready gate); we call the public ready accessor first and
 * only reflect the private one when busy. From that connection the guid is read **straight back
 * off the driver** via `RemoteConnection.getClientInfo("DorisTraceId")` (self-describing, no
 * identity assumption), with the connect-time weak map as a secondary.
 *
 * If the in-process connection genuinely can't be resolved, the **data-source processlist safety
 * net** takes over: we know the session's data source and every guid we ever minted for it, and
 * every tagged statement carries its guid in the processlist `Info` comment — so we find the one
 * *running* row whose `Info` marker is one of this data source's guids and kill it by `QueryId`.
 * With more than one running tagged query for the data source we refuse (to avoid the very
 * wrong-kill this feature exists to prevent) and leave it to stock.
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
    // In-process resolution: the guid off the (busy) session connection
    // ---------------------------------------------------------------------------------------

    /**
     * The guid of the query this console is currently running. Layered so reflection is only used
     * when the connection is actually busy, and every step is logged:
     *
     * 1. public `getCurrentConnectionIfReady()` — non-null only when the session is idle;
     * 2. private `getCurrentConnection()` via reflection — the busy connection (the P0 case);
     * 3. from whichever connection: `getClientInfo("DorisTraceId")`, then the connect-time weak map.
     */
    private fun resolveConnectionGuid(session: DatabaseSession): String? {
        val engine = session.messageBus.dataProducer
        if (engine == null) {
            DorisCancel.info("resolve: session has no data producer engine")
            return null
        }

        val ready = invokeConnectionAccessor(engine, "getCurrentConnectionIfReady")
        if (ready != null) {
            DorisCancel.info("resolve: getCurrentConnectionIfReady -> connection (session idle)")
            guidFromConnection(ready)?.let { return it }
        } else {
            DorisCancel.info("resolve: getCurrentConnectionIfReady -> null (session busy, expected mid-query)")
        }

        val busy = invokeConnectionAccessor(engine, "getCurrentConnection")
        if (busy != null) {
            DorisCancel.info("resolve: getCurrentConnection (reflected) -> busy connection")
            guidFromConnection(busy)?.let { return it }
        } else {
            DorisCancel.info("resolve: getCurrentConnection (reflected) -> null/unavailable")
        }

        DorisCancel.info("resolve: no guid from any in-process accessor")
        return null
    }

    /** Read the guid from a resolved connection: driver client-info first, weak map second. */
    private fun guidFromConnection(connection: DatabaseConnection): String? {
        val remote: RemoteConnection = try {
            connection.remoteConnection
        } catch (t: Throwable) {
            DorisCancel.warn("resolve: remoteConnection unavailable: ${t.message}")
            return null
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
        val fromMap = DorisCancel.guidForConnection(remote)
        if (DorisCancel.isValidGuid(fromMap)) {
            DorisCancel.info("resolve: guid from connect-time weak map -> $fromMap")
            return fromMap
        }
        return null
    }

    /**
     * Invoke a no-arg `DatabaseConnection`-returning accessor by name on the engine, searching the
     * runtime class hierarchy (the accessor may be private on `JdbcEngine`). Any reflection
     * failure is logged and returns null — the safety net then handles the cancel.
     */
    private fun invokeConnectionAccessor(engine: Any, methodName: String): DatabaseConnection? {
        return try {
            var cls: Class<*>? = engine.javaClass
            while (cls != null) {
                val method = cls.declaredMethods.firstOrNull {
                    it.name == methodName && it.parameterCount == 0
                }
                if (method != null) {
                    method.isAccessible = true
                    return method.invoke(engine) as? DatabaseConnection
                }
                cls = cls.superclass
            }
            DorisCancel.info("resolve: no accessor '$methodName' on ${engine.javaClass.name}")
            null
        } catch (t: Throwable) {
            DorisCancel.warn("resolve: accessor '$methodName' threw: ${t.message}")
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Server-side kill (pooled thread; one short-lived helper connection)
    // ---------------------------------------------------------------------------------------

    /**
     * Resolve the guid off the busy connection and kill, on a pooled thread through one short-lived
     * helper connection. Returns [DorisCancel.KillOutcome.KILLED] on the success path (a kill the
     * server accepted, or the statement already finished) and [DorisCancel.KillOutcome.NOTHING]
     * when we definitively did nothing — the caller then runs the stock fallback.
     */
    private fun killOnServer(session: DatabaseSession, dataSourceId: String?): DorisCancel.KillOutcome {
        // Resolve here (off the EDT) — `getClientInfo` is a remote call.
        val guid = resolveConnectionGuid(session)
        if (guid != null) {
            DorisCancel.info("session '${session.title}': resolved trace id $guid")
        } else {
            DorisCancel.info(
                "session '${session.title}' (ds=$dataSourceId): guid unresolved in-process; " +
                    "using data-source processlist safety net",
            )
        }
        return try {
            val ref = DatabaseConnectionManager.getInstance()
                .build(session.project, session.connectionPoint)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
                ?: throw IllegalStateException("no helper connection")
            ref.use { r ->
                val helper = r.get()
                if (guid != null) {
                    killByTraceId(helper, session, guid)
                } else {
                    killViaDataSourceProcesslist(helper, session, dataSourceId)
                }
            }
        } catch (t: Throwable) {
            DorisCancel.warn("helper connection for kill (guid=$guid) failed: ${t.message}")
            DorisCancel.KillOutcome.NOTHING
        }
    }

    /** Primary: one-shot kill by our own trace id (Doris >= 4.0, FE-forwarded). */
    private fun killByTraceId(
        helper: DatabaseConnection,
        session: DatabaseSession,
        guid: String,
    ): DorisCancel.KillOutcome {
        DorisCancel.info("kill: issuing ${DorisCancel.sqlKillQueryByTraceId(guid)}")
        try {
            execute(helper, DorisCancel.sqlKillQueryByTraceId(guid))
            DorisCancel.info("kill: killed by trace id $guid")
            return DorisCancel.KillOutcome.KILLED
        } catch (t: Throwable) {
            if (!DorisCancel.isUnknownQueryId(t)) {
                DorisCancel.warn("kill: KILL QUERY by trace id $guid failed: ${t.message}")
                return DorisCancel.KillOutcome.NOTHING
            }
            if (session.isIdle) {
                DorisCancel.info("kill: unknown query id for $guid and session idle -> already finished")
                return DorisCancel.KillOutcome.KILLED
            }
            DorisCancel.info(
                "kill: unknown query id for $guid but session still busy " +
                    "(pre-4.0 server or trace id clobbered) -> per-guid processlist fallback",
            )
        }
        // Fallback for exactly this guid.
        widenProcesslist(helper)
        val candidates = collectRunningCandidates(helper, setOf(guid))
        return killSingleCandidate(helper, candidates, "trace-id fallback for $guid")
    }

    /**
     * Data-source safety net: no in-process connection was resolvable, so find the running query
     * for this data source by matching the `Info` marker against every guid we minted for it.
     */
    private fun killViaDataSourceProcesslist(
        helper: DatabaseConnection,
        session: DatabaseSession,
        dataSourceId: String?,
    ): DorisCancel.KillOutcome {
        val guids = DorisCancel.guidsForDataSource(dataSourceId)
        if (guids.isEmpty()) {
            DorisCancel.info("safety net: no minted guids for ds=$dataSourceId; nothing to do")
            return DorisCancel.KillOutcome.NOTHING
        }
        DorisCancel.info("safety net: scanning processlist for ${guids.size} guid(s) of ds=$dataSourceId")
        widenProcesslist(helper)
        val candidates = collectRunningCandidates(helper, guids)
        return killSingleCandidate(helper, candidates, "safety net for session '${session.title}'")
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
