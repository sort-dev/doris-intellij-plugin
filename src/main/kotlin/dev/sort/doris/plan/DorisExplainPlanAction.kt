package dev.sort.doris.plan

import com.intellij.database.actions.ExplainActionBase
import com.intellij.database.console.JdbcConsole
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.openapi.diagnostic.Logger
import dev.sort.doris.DorisDbms

/**
 * Overrides `Console.Jdbc.ExplainPlan` (`overrides="true"` in plugin.xml) so that, for Doris, the
 * "Explain Plan" action runs `EXPLAIN <query>` as a normal query and shows the plan as text rows in
 * the result grid — the native way a Doris plan is read (option C).
 *
 * DataGrip's graphical Explain Plan is a node-diagram built for typed plan nodes; Doris `EXPLAIN`
 * returns a hierarchical text plan (one line per row), which the diagram mangles into
 * "Operation(<line>)" boxes with indentation lost (see [DorisExplainPlanProvider]). Rather than
 * write a Doris-specific typed-node parser, we submit the `EXPLAIN` as a query through the console's
 * own data producer — the exact path a normal statement run takes — so the plan lands in the grid.
 *
 * Only the DORIS branch is changed; every other dbms falls through to the stock graphical
 * [ExplainActionBase.Ui.Plan] behaviour via `super`.
 */
class DorisExplainPlanAction : ExplainActionBase.Ui.Plan() {

    override fun explainStatement(provider: ExplainPlanProvider, console: JdbcConsole, sql: String) {
        val session = console.session
        if (session.connectionPoint.dbms !== DorisDbms.DORIS) {
            super.explainStatement(provider, console, sql)
            return
        }
        try {
            // Submit `EXPLAIN <query>` through the console's data producer — the same request path a
            // normal run uses — so the plan text shows as rows in a result grid.
            val request = DataRequest.newRequest(session, "EXPLAIN $sql", session.connectionPoint.dbms)
            session.messageBus.dataProducer.processRequest(request)
        } catch (t: Throwable) {
            LOG.warn("Doris EXPLAIN-as-query failed; falling back to stock explain: ${t.message}")
            super.explainStatement(provider, console, sql)
        }
    }

    private companion object {
        val LOG = Logger.getInstance(DorisExplainPlanAction::class.java)
    }
}
