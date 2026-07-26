package dev.sort.doris.plan

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.plan.PlanModel
import com.intellij.database.util.Version
import com.intellij.util.Consumer
import dev.sort.doris.DorisDbms

/**
 * Marks Explain Plan **supported** for Doris so the console's "Explain Plan" action is visible — but
 * the actual behaviour is provided by [DorisExplainPlanAction], which overrides the action to run
 * `EXPLAIN <query>` as a normal query and show the plan rows in the result grid.
 *
 * ## Why not the graphical diagram
 *
 * DataGrip's new-UI Explain Plan is a node-diagram whose every box renders as
 * `capitalizeWords(nodeType.display) + "(" + title + ")"` — it is built for *typed* plan nodes.
 * Doris `EXPLAIN` returns a rich hierarchical **text** plan (one line per row: `PLAN FRAGMENT 0`,
 * `0:VOlapScanNode`, detail lines, MATERIALIZATIONS / STATISTICS sections). Forcing that into the
 * diagram produces "Operation(<line>)" noise with the indentation lost. The native Doris plan
 * experience is the text, which is exactly what running `EXPLAIN` as a query gives — so we do that
 * (option C) instead of building a Doris-specific typed-node parser.
 *
 * This provider therefore only gates visibility ([isSupported] = plain EXPLAIN, no "Explain Analyse"
 * since Doris EXPLAIN never runs the query). Its request builders are never reached: the action
 * override handles the DORIS case and delegates every other dbms to the stock graphical path.
 */
class DorisExplainPlanProvider : ExplainPlanProvider(DorisDbms.DORIS) {

    override fun isSupported(version: Version, analyze: Boolean): Boolean = !analyze

    override fun isRawSupported(version: Version, analyze: Boolean): Boolean = false

    /** Unreachable — [DorisExplainPlanAction] handles DORIS before the graphical path is entered. */
    override fun createExplainRequest(
        owner: DataRequest.OwnerEx,
        consumer: Consumer<in PlanModel>,
        dataSource: LocalDataSource,
        sql: String,
        analyze: Boolean,
    ): DataRequest.RawRequest =
        throw UnsupportedOperationException("Doris runs EXPLAIN as a query; see DorisExplainPlanAction")

    /** Unreachable — see [createExplainRequest]. */
    override fun createRawExplainTask(dataSource: LocalDataSource, analyze: Boolean): RawExplainTask =
        throw UnsupportedOperationException("Doris runs EXPLAIN as a query; see DorisExplainPlanAction")
}
