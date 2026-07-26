package dev.sort.doris.plan

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.plan.PlanModel
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.util.Version
import com.intellij.util.Consumer
import dev.sort.doris.DorisDbms
import java.util.EnumSet

/**
 * ROUGH DRAFT — DO NOT SHIP AS-IS. Doris Explain Plan for the new-UI graphical view.
 *
 * The new-UI "Explain Plan" is a node-diagram built from a [PlanModel] (the legacy raw-text path is
 * disabled by the `database.explain.plan.new.ui` registry flag, default on). So we must supply a
 * PlanModel. Rather than the platform's stack-based `AbstractPlanModelBuilder`/`RawPlanData`
 * machinery (fiddly, JSON-oriented), this takes the **direct** route: extend [ExplainPlanProvider],
 * override [createExplainRequest] with a [DataRequest.RawRequest] whose `processRaw` runs
 * `EXPLAIN <query>` on the connection (the same `JdbcNativeUtil` pattern the cancel code uses),
 * then builds the [PlanModel] ourselves and hands it to the consumer — mirroring what
 * `AbstractExplainPlanProvider$1.processRaw` does internally.
 *
 * ## Model shape (v1: flat, per the hunch that Doris/MySQL just wants one node per line)
 *
 * Doris `EXPLAIN` returns a single text column, one plan line per row (`PLAN FRAGMENT 0`,
 * `1:VHASH JOIN`, `|----2:VEXCHANGE`, `0:VOlapScanNode`, ...). [buildPlanModel] currently makes a
 * flat tree: a ROOT node with one OPERATION child per non-blank line (line text = node title, full
 * line = tooltip via rawDescription). Cardinality/cost columns are marked unsupported (plain EXPLAIN
 * has none). Nesting by the `|----`/indentation structure is a small follow-up if the flat list
 * reads poorly.
 *
 * ## Verification status
 *
 * [buildPlanModel] is pure and unit-tested. The query-run + new-UI rendering can only be verified on
 * a live Doris connection in `runIde` — that is the bake. `isSupported` = plain EXPLAIN only (no
 * "Explain Analyse": Doris EXPLAIN does not run the query).
 */
class DorisExplainPlanProvider : ExplainPlanProvider(DorisDbms.DORIS) {

    override fun isSupported(version: Version, analyze: Boolean): Boolean = !analyze

    override fun isRawSupported(version: Version, analyze: Boolean): Boolean = false

    override fun createExplainRequest(
        owner: DataRequest.OwnerEx,
        consumer: Consumer<in PlanModel>,
        dataSource: LocalDataSource,
        sql: String,
        analyze: Boolean,
    ): DataRequest.RawRequest =
        object : DataRequest.RawRequest(owner) {
            override fun processRaw(context: DataRequest.Context, connection: DatabaseConnectionCore) {
                consumer.consume(buildPlanModel(runExplain(connection, sql)))
            }
        }

    /** Raw text path is disabled by the platform's new-UI flag; never invoked. */
    override fun createRawExplainTask(dataSource: LocalDataSource, analyze: Boolean): RawExplainTask =
        throw UnsupportedOperationException("Doris uses the structured explain path only")

    /** Run `EXPLAIN <sql>` and join the single text column into one plan string. */
    private fun runExplain(connection: DatabaseConnectionCore, sql: String): String {
        val statement = JdbcNativeUtil.computeRemote { connection.remoteConnection.createStatement() }
            ?: return ""
        return try {
            val rs = JdbcNativeUtil.computeRemote { statement.executeQuery("EXPLAIN $sql") } ?: return ""
            try {
                val sb = StringBuilder()
                while (JdbcNativeUtil.computeRemote { rs.next() } == true) {
                    val line = JdbcNativeUtil.computeRemote { rs.getString(1) } ?: ""
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(line)
                }
                sb.toString()
            } finally {
                JdbcNativeUtil.performSafe { rs.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    companion object {
        /** Pure + testable: Doris EXPLAIN text -> a flat PlanModel (ROOT with one node per plan line). */
        fun buildPlanModel(text: String): PlanModel {
            val root = PlanModel.GenericNode(PlanModel.NodeType.ROOT, "Explain Plan")
            val children = text.split("\n")
                .filter { it.isNotBlank() }
                .map { line ->
                    PlanModel.GenericNode(PlanModel.NodeType.OPERATION, line.trim())
                        .also { it.rawDescription = line }
                }
                .toTypedArray()
            root.children = children
            // Plain EXPLAIN carries no rows/cost/startup numbers — mark those columns unsupported.
            return PlanModel(root, false, EnumSet.allOf(PlanModel.Feature::class.java))
        }
    }
}
