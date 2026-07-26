package dev.sort.doris.plan

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.plan.PlanModel
import com.intellij.database.util.DdlBuilder
import com.intellij.database.util.Version
import com.intellij.util.Consumer
import dev.sort.doris.DorisDbms

/**
 * Wires DataGrip's "Explain Plan" for Doris — as the **raw text** plan (`EXPLAIN <query>`), which is
 * how Doris plans are meant to be read.
 *
 * ## Why it was dead
 *
 * Explain Plan is driven by an [ExplainPlanProvider] resolved via `ExplainPlanProvider.EP.forDbms(dbms)`
 * (a DBMS-keyed extension). Every stock dialect registers one; the `DORIS` dbms had none, so
 * `forDbms(DORIS)` came up empty and every Explain action was hidden. MySQL's provider can't be
 * reused: Doris's `EXPLAIN` emits a distributed fragment/plan-node **text** plan, nothing like
 * MySQL's tabular/JSON EXPLAIN, so MySQL's plan SQL and its structured parser would break.
 *
 * ## Raw only — and why that's the item that shows
 *
 * `ExplainActionBase.update` does `presentation.setEnabledAndVisible(isSupported)`, so an unsupported
 * Explain action is **hidden**, not greyed. Two independently-gated paths exist:
 * `Console.Jdbc.ExplainPlan` (the graphical tree) checks [isSupported]; `Console.Jdbc.ExplainPlan.Raw`
 * checks [isRawSupported]. We report [isSupported] = `false` (the graphical "Explain Plan" disappears
 * — Doris's fragment plan doesn't map onto the platform's relational PlanModel tree, and parsing it
 * is a separate, larger effort) and [isRawSupported] = `true` for a plain plan, so the **raw** item
 * is the one that appears and works. The "analyse" variants stay hidden: Doris `EXPLAIN` never runs
 * the query, so there is no actual-stats plan to show.
 *
 * Single-DBMS provider, so the no-arg constructor pins [DorisDbms.DORIS] into the base (the H2
 * provider pattern), rather than taking an injected dbms like the shared MySQL base.
 */
class DorisExplainPlanProvider : ExplainPlanProvider(DorisDbms.DORIS) {

    override fun isSupported(version: Version, analyze: Boolean): Boolean = false

    override fun isRawSupported(version: Version, analyze: Boolean): Boolean = !analyze

    /**
     * Never reached — [isSupported] is `false`, so the structured request is never built. Guarded
     * defensively rather than fabricating an empty [PlanModel].
     */
    override fun createExplainRequest(
        owner: DataRequest.OwnerEx,
        consumer: Consumer<in PlanModel>,
        dataSource: LocalDataSource,
        sql: String,
        analyze: Boolean,
    ): DataRequest.RawRequest =
        throw UnsupportedOperationException("Doris has no structured explain plan; raw only")

    override fun createRawExplainTask(dataSource: LocalDataSource, analyze: Boolean): RawExplainTask =
        object : RawExplainTask() {
            /** `EXPLAIN <statement>` — Doris's native text plan. */
            override fun sqlExplainPlan(builder: DdlBuilder, statement: String): DdlBuilder =
                builder.keywords("EXPLAIN").space().plain(statement)
        }
}
