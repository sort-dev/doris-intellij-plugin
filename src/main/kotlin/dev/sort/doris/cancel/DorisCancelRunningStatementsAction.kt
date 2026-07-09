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
 * For a Doris session the stock path still runs, **first**, and our server-side kill follows on
 * a pooled thread (ordering rationale unchanged: `performAction` is on the EDT, the plugin kill
 * needs a helper connection, and the stock path is what unblocks the client-side statement).
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

        // Stock behavior always runs first (posts the async client-side cancel; harmless server
        // miss). It must not be gated on any of our resolution succeeding.
        super.performAction(e, session)
        if (!handleDoris) return

        val dataSourceId = dataSourceId(session)
        // Resolve the guid off the BUSY connection (the P0 fix); logs each accessor it tries.
        val guid = resolveConnectionGuid(session)

        if (guid == null && !DorisCancel.hasAnyGuidForDataSource(dataSourceId)) {
            // Truly nothing tagged for this data source: connected before the plugin/flag.
            DorisCancel.info(
                "session '${session.title}' (ds=$dataSourceId): no trace id ever minted for this " +
                    "data source (connected before the plugin/flag?); stock cancel only",
            )
            return
        }
        if (guid == null) {
            DorisCancel.info(
                "session '${session.title}' (ds=$dataSourceId): guid minted for this data source " +
                    "but its connection was unreachable at cancel; using processlist safety net",
            )
        } else {
            DorisCancel.info("cancel requested for session '${session.title}', trace id $guid")
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            killOnServer(session, guid, dataSourceId)
        }
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

    private fun killOnServer(session: DatabaseSession, guid: String?, dataSourceId: String?) {
        try {
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
        }
    }

    /** Primary: one-shot kill by our own trace id (Doris >= 4.0, FE-forwarded). */
    private fun killByTraceId(helper: DatabaseConnection, session: DatabaseSession, guid: String) {
        DorisCancel.info("kill: issuing ${DorisCancel.sqlKillQueryByTraceId(guid)}")
        try {
            execute(helper, DorisCancel.sqlKillQueryByTraceId(guid))
            DorisCancel.info("kill: killed by trace id $guid")
            return
        } catch (t: Throwable) {
            if (!DorisCancel.isUnknownQueryId(t)) {
                DorisCancel.warn("kill: KILL QUERY by trace id $guid failed: ${t.message}")
                return
            }
            if (session.isIdle) {
                DorisCancel.info("kill: unknown query id for $guid and session idle -> already finished")
                return
            }
            DorisCancel.info(
                "kill: unknown query id for $guid but session still busy " +
                    "(pre-4.0 server or trace id clobbered) -> per-guid processlist fallback",
            )
        }
        // Fallback for exactly this guid.
        widenProcesslist(helper)
        val candidates = collectRunningCandidates(helper, setOf(guid))
        killSingleCandidate(helper, candidates, "trace-id fallback for $guid")
    }

    /**
     * Data-source safety net: no in-process connection was resolvable, so find the running query
     * for this data source by matching the `Info` marker against every guid we minted for it.
     */
    private fun killViaDataSourceProcesslist(
        helper: DatabaseConnection,
        session: DatabaseSession,
        dataSourceId: String?,
    ) {
        val guids = DorisCancel.guidsForDataSource(dataSourceId)
        if (guids.isEmpty()) {
            DorisCancel.info("safety net: no minted guids for ds=$dataSourceId; nothing to do")
            return
        }
        DorisCancel.info("safety net: scanning processlist for ${guids.size} guid(s) of ds=$dataSourceId")
        widenProcesslist(helper)
        val candidates = collectRunningCandidates(helper, guids)
        killSingleCandidate(helper, candidates, "safety net for session '${session.title}'")
    }

    /** Kill exactly one candidate; refuse (log) on zero or ambiguous multiple. */
    private fun killSingleCandidate(
        helper: DatabaseConnection,
        candidates: List<Candidate>,
        context: String,
    ) {
        when {
            candidates.isEmpty() ->
                DorisCancel.info("$context: no running tagged query found; nothing to kill (stock handled it)")
            candidates.size > 1 -> {
                val ids = candidates.joinToString { "${it.queryId}(${it.guid})" }
                DorisCancel.warn(
                    "$context: ${candidates.size} running tagged queries [$ids]; ambiguous, " +
                        "refusing to kill to avoid a wrong-kill; leaving it to stock",
                )
            }
            else -> {
                val c = candidates.single()
                if (!DorisCancel.isValidQueryId(c.queryId)) {
                    DorisCancel.warn("$context: refusing to kill malformed query id '${c.queryId}'")
                    return
                }
                DorisCancel.info("$context: issuing ${DorisCancel.sqlKillQueryByQueryId(c.queryId)} (guid=${c.guid})")
                try {
                    execute(helper, DorisCancel.sqlKillQueryByQueryId(c.queryId))
                    DorisCancel.info("$context: killed query id ${c.queryId}")
                } catch (t: Throwable) {
                    if (DorisCancel.isUnknownQueryId(t)) {
                        DorisCancel.info("$context: query id ${c.queryId} already gone")
                    } else {
                        DorisCancel.warn("$context: KILL QUERY \"${c.queryId}\" failed: ${t.message}")
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
