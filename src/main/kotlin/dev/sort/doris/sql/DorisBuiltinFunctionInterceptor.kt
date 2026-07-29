package dev.sort.doris.sql

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms

/**
 * `<database.connectionInterceptor>` — harvests the connected Doris FE's builtin function names via
 * `SHOW BUILTIN FUNCTIONS` into [DorisBuiltinCatalog], so completion can reconcile the brikk-sql
 * baseline to the server's actual version (drop names the server lacks, add names brikk lacks).
 *
 * Gated on the Doris dbms ONLY — independent of the cancel feature's flag (unlike
 * [dev.sort.doris.cancel.DorisTraceIdConnectionInterceptor]). Harvest is:
 *  - **synchronous during connect prep** ([handleConnected] runs before the connection is handed to
 *    a session), so there is no concurrent-statement race on the connection;
 *  - **once per data source per session** — subsequent connects short-circuit on [DorisBuiltinCatalog.hasData],
 *    so the ~700-row `SHOW` runs a single time, not on every console / helper connection;
 *  - **best-effort** — an older server without the command, a restricted session, or any error is
 *    logged and leaves the brikk baseline in force (the user's "on failure, brikk list").
 */
class DorisBuiltinFunctionInterceptor : DatabaseConnectionInterceptor {

    override suspend fun interceptConnection(
        proto: DatabaseConnectionInterceptor.ProtoConnection,
        silent: Boolean,
    ): Boolean = proto.connectionPoint.dbms === DorisDbms.DORIS

    override suspend fun handleConnected(
        connection: DatabaseConnectionCore,
        proto: DatabaseConnectionInterceptor.ProtoConnection,
    ) {
        if (connection.dbms !== DorisDbms.DORIS) return
        val dataSourceId = connection.connectionPoint.dataSource.uniqueId
        if (DorisBuiltinCatalog.hasData(dataSourceId)) return // harvested already this session

        val names = try {
            harvest(connection)
        } catch (t: Throwable) {
            DorisCatalogs.info(
                "SHOW BUILTIN FUNCTIONS harvest failed on " +
                    "'${connection.connectionPoint.dataSource.name}' (brikk baseline stands): ${t.message}",
            )
            return
        }
        if (names.isNotEmpty()) {
            DorisBuiltinCatalog.record(dataSourceId, names)
            // DIAGNOSTIC (wip): does the server's own list contain the probe names? If st_area/
            // mbrcontains ARE served, keeping them in completion is correct, not a filter bug.
            val probes = listOf("REGEXP_LIKE", "ST_AREA", "ST_POINT", "MBRCONTAINS", "MBR_CONTAINS")
                .associateWith { it in names }
            DorisCatalogs.info(
                "harvested ${names.size} builtin function names from " +
                    "'${connection.connectionPoint.dataSource.name}' (ds=$dataSourceId); probes=$probes",
            )
        }
    }

    /** All rows' first column of `SHOW BUILTIN FUNCTIONS`, upper-cased. */
    private fun harvest(connection: DatabaseConnectionCore): Set<String> {
        val statement = JdbcNativeUtil.computeRemote {
            connection.remoteConnection.createStatement()
        } ?: return emptySet()
        try {
            val resultSet = JdbcNativeUtil.computeRemote {
                statement.executeQuery("SHOW BUILTIN FUNCTIONS")
            } ?: return emptySet()
            try {
                return JdbcNativeUtil.computeRemote {
                    buildSet {
                        while (resultSet.next()) {
                            resultSet.getString(1)?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
                        }
                    }
                } ?: emptySet()
            } finally {
                JdbcNativeUtil.performSafe { resultSet.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }
}
