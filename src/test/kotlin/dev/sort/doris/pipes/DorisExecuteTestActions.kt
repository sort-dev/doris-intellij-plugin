package dev.sort.doris.pipes

import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.ExecutionClaim

/** Test constructors use the shipped helper, with a sentinel instead of stock raw execution. */
internal open class DorisPipesRunQueryAction(
    index: Int,
    previous: AnAction,
    intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = PipesExecuteInterceptor::handle,
) : CooperativeExecuteAction(index, previous, ExecuteInterceptor { event, option ->
    if (intercept(event, option)) ExecutionClaim.HANDLED else ExecutionClaim.DECLINED
}, selection = index == -1)

internal class DorisPipesRunSelectionAction(
    previous: AnAction,
    intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean = PipesExecuteInterceptor::handle,
) : DorisPipesRunQueryAction(-1, previous, intercept)
