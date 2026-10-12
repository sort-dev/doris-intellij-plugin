package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import dev.brikk.house.sql.intellij.CooperativeExecutePromoter
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor

/** Project ownership and settings stay in Doris; registration and detach belong to the helper. */
class DorisPipesActionConfiguration : CooperativeExecuteConfiguration() {
    override val interceptor = PipeExecuteInterceptor(DorisPipeDialect)

    companion object {
        internal val EXECUTE_IDS = IDS
    }
}

class DorisPipesActionPromoter : CooperativeExecutePromoter()
