package dev.sort.doris.plan

import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.plan.PlanModel
import com.intellij.database.util.Version
import com.intellij.util.Consumer
import dev.sort.doris.DorisDbms

/**
 * Reports Explain Plan as **not supported** for Doris, so the console's "Explain Plan" action is
 * HIDDEN rather than shown-and-broken. This is a deliberate stub, not the feature.
 *
 * ## Why this exists
 *
 * Explain Plan resolves an [ExplainPlanProvider] via `ExplainPlanProvider.EP.forDbms(dbms)`. With no
 * provider registered for `DORIS`, that lookup falls back to MySQL's provider (via
 * `extensionFallback DORIS -> MYSQL`), whose [isSupported] is `true` — so "Explain Plan" appears in
 * the menu but does nothing, because MySQL's plan SQL and its structured parser cannot read Doris's
 * distributed fragment/plan-node text. `ExplainActionBase.update` does
 * `setEnabledAndVisible(isSupported)`, so returning `false` here removes the dead item entirely.
 *
 * ## Deferred, not abandoned
 *
 * A working Explain Plan needs the graphical (new-UI) path: [isSupported] = `true` plus a real
 * [PlanModel] built from Doris's `EXPLAIN <query>` text — a `RawPlanData` subclass to run it and an
 * `AbstractPlanModelBuilder` (or a direct `PlanModel.GenericNode` tree) to parse the plan. That is a
 * Doris-specific parser that can only be verified against a live connection, so it is deferred. When
 * it lands, flip [isSupported] to `true` and implement [createExplainRequest]. (The raw path is not
 * an option: `ExplainActionBase$Raw` is gated off by the `database.explain.plan.new.ui` registry
 * flag, which defaults on, so raw Explain actions are hidden in current DataGrip regardless.)
 */
class DorisExplainPlanProvider : ExplainPlanProvider(DorisDbms.DORIS) {

    override fun isSupported(version: Version, analyze: Boolean): Boolean = false

    override fun isRawSupported(version: Version, analyze: Boolean): Boolean = false

    /** Unreachable — both support checks are `false`, so no Explain action is ever enabled. */
    override fun createExplainRequest(
        owner: DataRequest.OwnerEx,
        consumer: Consumer<in PlanModel>,
        dataSource: LocalDataSource,
        sql: String,
        analyze: Boolean,
    ): DataRequest.RawRequest =
        throw UnsupportedOperationException("Explain Plan is not yet supported for Doris")

    /** Unreachable — see [createExplainRequest]. */
    override fun createRawExplainTask(dataSource: LocalDataSource, analyze: Boolean): RawExplainTask =
        throw UnsupportedOperationException("Explain Plan is not yet supported for Doris")
}
