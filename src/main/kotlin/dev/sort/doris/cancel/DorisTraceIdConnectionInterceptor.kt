package dev.sort.doris.cancel

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil

/**
 * `<database.connectionInterceptor>` — connect-time half of the clean Doris cancel
 * (RESEARCH-query-cancel.md; see [DorisCancel] for the full design).
 *
 * The platform seam (DataGrip DB-261 bytecode, `DatabaseConnectionEstablisher`): every EP
 * interceptor's [interceptConnection] runs while the proto-connection is being prepared; only
 * interceptors that return `true` are added to the proto's *active* list, and after the JDBC
 * connection is established `DatabaseConnectionEstablisher.processConnected` calls
 * [handleConnected] on each active interceptor. The default `interceptConnection` returns
 * `false` (via a null `intercept` stage), so we override it — that override doubles as the
 * dbms + flag guard: non-Doris data sources never see [handleConnected] at all.
 *
 * On each new Doris connection (consoles, introspection, and our own cancel-time helper alike):
 *
 * 1. mint guid `dg-<16 hex>` ([DorisCancel.mintGuid]);
 * 2. `SET session_context = 'trace_id:<guid>'` — the Doris >= 4.0 kill handle (the FE maps the
 *    trace id onto every subsequent query of this session);
 * 3. `RemoteConnection.setClientInfo("DorisTraceId", <guid>)` — Connector/J's
 *    `CommentClientInfoProvider` merges all client-info entries into a `/* ... */` comment
 *    prepended to every outgoing statement, so the guid is greppable in any FE's
 *    `SHOW PROCESSLIST` `Info` column (the pre-4.0 fallback handle);
 * 4. remember the guid keyed by the connection ([DorisCancel.registerGuid]).
 *
 * Both server calls are best-effort: a failure is logged with the `DorisCancel:` prefix and the
 * connection proceeds untouched (worst case the plugin cancel degrades to the stock behavior).
 */
class DorisTraceIdConnectionInterceptor : DatabaseConnectionInterceptor {

    override suspend fun interceptConnection(
        proto: DatabaseConnectionInterceptor.ProtoConnection,
        silent: Boolean,
    ): Boolean = DorisCancel.shouldHandle(proto.connectionPoint.dbms)

    override suspend fun handleConnected(
        connection: DatabaseConnectionCore,
        proto: DatabaseConnectionInterceptor.ProtoConnection,
    ) {
        // interceptConnection already gated on dbms+flag; re-check defensively (both are cheap).
        if (!DorisCancel.shouldHandle(connection.dbms)) return

        val guid = DorisCancel.mintGuid()
        val dataSourceName = connection.connectionPoint.dataSource.name

        val traceIdBound = try {
            execute(connection, DorisCancel.sqlSetSessionContext(guid))
            true
        } catch (t: Throwable) {
            DorisCancel.warn(
                "failed to bind trace id $guid on '$dataSourceName' " +
                    "(pre-2.x server or restricted session?): ${t.message}",
            )
            false
        }

        val clientInfoSet = try {
            JdbcNativeUtil.performRemote {
                connection.remoteConnection.setClientInfo(DorisCancel.CLIENT_INFO_KEY, guid)
            }
            true
        } catch (t: Throwable) {
            DorisCancel.warn(
                "failed to set ${DorisCancel.CLIENT_INFO_KEY} client info on '$dataSourceName': ${t.message}",
            )
            false
        }

        if (traceIdBound || clientInfoSet) {
            DorisCancel.registerGuid(connection.remoteConnection, guid)
            DorisCancel.info(
                "minted trace id $guid for '$dataSourceName' " +
                    "(session_context=$traceIdBound, clientInfo=$clientInfoSet)",
            )
        }
    }

    private fun execute(connection: DatabaseConnectionCore, sql: String) {
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
