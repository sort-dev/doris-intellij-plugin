package dev.sort.doris.cancel

import com.intellij.database.actions.CancelRunningStatementsAction
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
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
 * a pooled thread. Ordering rationale:
 *
 * - `performAction` runs on the EDT; the plugin kill needs a helper connection (network I/O),
 *   so it must go to a background thread regardless — "ours strictly before stock" would delay
 *   the client-side unblock behind a helper connect and hang the UI feel on a slow LB.
 * - The stock path is itself asynchronous (it just posts a `DataRequest.Cancel` to the session's
 *   data producer) and is what unblocks the *client-side* statement promptly; its server-side
 *   `KILL QUERY <conn-id>` on a random FE stays exactly as (in)effective as today.
 * - Our kill needs only the guid, captured *before* delegating, so nothing the stock path does
 *   (statement teardown, connection release) can race the lookup.
 *
 * The kill itself, from a short-lived helper connection to the same data source (any FE):
 * `KILL QUERY "<guid>"` — FE-forwarded on Doris >= 4.0. "Unknown query id" with the session
 * already idle means the statement finished on its own: success, silent. "Unknown query id"
 * with the session still busy (pre-4.0 server, or the trace id was clobbered by a user
 * `SET session_context`) triggers the fallback: all-FE `SHOW FULL PROCESSLIST`, match the
 * `Info` column's `DorisTraceId=<guid>` driver-comment marker, `KILL QUERY "<QueryId>"`.
 */
class DorisCancelRunningStatementsAction : CancelRunningStatementsAction() {

    override fun performAction(e: AnActionEvent, session: DatabaseSession) {
        val handleDoris = DorisCancel.shouldHandle(session.connectionPoint.dbms) &&
            !session.isCancelled
        // Capture the guid before the stock path can touch the session's connection.
        val guid = if (handleDoris) guidOf(session) else null

        // Stock behavior always runs (posts the async client-side cancel; harmless server miss).
        super.performAction(e, session)

        if (!handleDoris) return
        if (guid == null) {
            DorisCancel.info(
                "no trace id known for session '${session.title}' " +
                    "(connected before the plugin/flag?); stock cancel only",
            )
            return
        }
        DorisCancel.info("cancel requested for session '${session.title}', trace id $guid")
        ApplicationManager.getApplication().executeOnPooledThread {
            killOnServer(session, guid)
        }
    }

    /** The guid minted at connect time, looked up by the session's live remote connection. */
    private fun guidOf(session: DatabaseSession): String? {
        val engine = session.messageBus.dataProducer as? JdbcEngine ?: return null
        val connection = engine.getCurrentConnectionIfReady() ?: return null
        return try {
            DorisCancel.guidFor(connection.remoteConnection)
        } catch (t: Throwable) {
            DorisCancel.warn("could not read session connection identity: ${t.message}")
            null
        }
    }

    /** Runs on a pooled thread. Opens the helper connection, kills, closes. */
    private fun killOnServer(session: DatabaseSession, guid: String) {
        try {
            val ref = DatabaseConnectionManager.getInstance()
                .build(session.project, session.connectionPoint)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
                ?: throw IllegalStateException("no helper connection")
            ref.use { killWith(it.get(), session, guid) }
        } catch (t: Throwable) {
            DorisCancel.warn("helper connection for kill of $guid failed: ${t.message}")
        }
    }

    private fun killWith(helper: DatabaseConnection, session: DatabaseSession, guid: String) {
        // Primary: one-shot kill by our own trace id (Doris >= 4.0, FE-forwarded).
        try {
            execute(helper, DorisCancel.sqlKillQueryByTraceId(guid))
            DorisCancel.info("killed by trace id: $guid")
            return
        } catch (t: Throwable) {
            if (!DorisCancel.isUnknownQueryId(t)) {
                DorisCancel.warn("KILL QUERY by trace id $guid failed: ${t.message}")
                return
            }
            // "Unknown query id": nothing owns the trace id anywhere.
            if (session.isIdle) {
                DorisCancel.info("kill $guid: unknown query id and session idle -> already finished")
                return
            }
            DorisCancel.info(
                "kill $guid: unknown query id but session still busy " +
                    "(pre-4.0 server or trace id lost) -> processlist fallback",
            )
        }
        killViaProcesslist(helper, guid)
    }

    /**
     * Fallback (pre-4.0 servers / trace id lost): widen the processlist to all FEs (both
     * variable generations, failures ignored), find the row whose `Info` carries this
     * connection's `DorisTraceId=<guid>` driver-comment marker, kill by its `QueryId` (reaches
     * other FEs via FE-forwarding on 4.0+ or BE broadcast on 2.1/3.x).
     */
    private fun killViaProcesslist(helper: DatabaseConnection, guid: String) {
        for (sql in listOf(DorisCancel.SQL_ALL_FE_PROCESSLIST_NEW, DorisCancel.SQL_ALL_FE_PROCESSLIST_OLD)) {
            try {
                execute(helper, sql)
                DorisCancel.info("fallback: '$sql' OK")
                break
            } catch (t: Throwable) {
                DorisCancel.info("fallback: '$sql' rejected (${t.message?.take(120)}); trying older name")
            }
        }
        val queryId = try {
            findQueryIdByMarker(helper, DorisCancel.processlistMarker(guid))
        } catch (t: Throwable) {
            DorisCancel.warn("fallback: SHOW FULL PROCESSLIST failed: ${t.message}")
            return
        }
        if (queryId == null) {
            DorisCancel.info("fallback: no processlist row carries marker for $guid; nothing to kill")
            return
        }
        if (!DorisCancel.isValidQueryId(queryId)) {
            DorisCancel.warn("fallback: refusing to kill malformed query id '$queryId'")
            return
        }
        try {
            execute(helper, DorisCancel.sqlKillQueryByQueryId(queryId))
            DorisCancel.info("fallback: killed query id $queryId (marker match for $guid)")
        } catch (t: Throwable) {
            if (DorisCancel.isUnknownQueryId(t)) {
                DorisCancel.info("fallback: query id $queryId already gone")
            } else {
                DorisCancel.warn("fallback: KILL QUERY \"$queryId\" failed: ${t.message}")
            }
        }
    }

    /** `SHOW FULL PROCESSLIST` scan: the `QueryId` of the row whose `Info` contains the marker. */
    private fun findQueryIdByMarker(helper: DatabaseConnection, marker: String): String? {
        val statement = JdbcNativeUtil.computeRemote {
            helper.remoteConnection.createStatement()
        } ?: return null
        try {
            val resultSet = JdbcNativeUtil.computeRemote {
                statement.executeQuery(DorisCancel.SQL_SHOW_FULL_PROCESSLIST)
            } ?: return null
            try {
                val metadata = resultSet.metaData
                var queryIdIndex = -1
                var infoIndex = -1
                for (i in 1..metadata.columnCount) {
                    val label = metadata.getColumnLabel(i).orEmpty()
                    when {
                        label.equals("QueryId", ignoreCase = true) -> queryIdIndex = i
                        label.equals("Info", ignoreCase = true) -> infoIndex = i
                    }
                }
                if (queryIdIndex < 0 || infoIndex < 0) {
                    DorisCancel.info("fallback: processlist lacks QueryId/Info columns")
                    return null
                }
                while (resultSet.next()) {
                    val info = resultSet.getString(infoIndex) ?: continue
                    if (info.contains(marker)) return resultSet.getString(queryIdIndex)
                }
                return null
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
