package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import dev.brikk.house.sql.intellij.CooperativeExecutePromoter
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.ExecutionClaim

/** Project ownership and settings stay in Doris; registration and detach belong to the helper. */
class DorisPipesActionConfiguration : CooperativeExecuteConfiguration() {
    override val interceptor = ExecuteInterceptor { event, option ->
        if (PipesExecuteInterceptor.handle(event, option)) ExecutionClaim.HANDLED else ExecutionClaim.DECLINED
    }

    companion object {
        internal val EXECUTE_IDS = IDS
    }
}

class DorisPipesActionPromoter : CooperativeExecutePromoter()
