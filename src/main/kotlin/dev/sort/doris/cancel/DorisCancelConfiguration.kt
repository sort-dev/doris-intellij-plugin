package dev.sort.doris.cancel

import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import dev.brikk.house.sql.intellij.ActionInterceptor
import dev.brikk.house.sql.intellij.CooperativeActionConfiguration
import dev.brikk.house.sql.intellij.ExecutionClaim

internal class DorisCancelInterceptor(
    private val coordinator: DorisCancelCoordinator,
    private val predecessor: () -> AnAction?,
) : ActionInterceptor {
    override fun perform(event: AnActionEvent, delegate: () -> Unit): ExecutionClaim {
        val client = event.getData(SessionClientHolder.CLIENT_KEY) ?: return ExecutionClaim.DECLINED
        val session = client.session as? DatabaseSession ?: return ExecutionClaim.DECLINED
        if (!DorisCancel.shouldHandle(session.connectionPoint.dbms)) return ExecutionClaim.DECLINED
        val previous = predecessor()
        val place = event.place
        val presentation = event.presentation.clone()
        val fallback = {
            if (!session.project.isDisposed && previous != null) {
                val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, session.project)
                    .add(SessionClientHolder.CLIENT_KEY, client).build()
                ActionUtil.performAction(previous, AnActionEvent.createEvent(context, presentation.clone(), place, ActionUiKind.NONE, null))
            }
        }
        return if (coordinator.perform(session, fallback)) ExecutionClaim.HANDLED else ExecutionClaim.DECLINED
    }
}

class DorisCancelConfiguration : CooperativeActionConfiguration() {
    private var predecessor: AnAction? = null
    override val actionIds = listOf(CANCEL)
    override val interceptor: ActionInterceptor = DorisCancelInterceptor(DorisCancelCoordinator()) { predecessor }

    override fun registerActions(actionManager: ActionManager) {
        if (predecessor == null) predecessor = actionManager.getAction(CANCEL)
        super.registerActions(actionManager)
    }

    override fun unregisterActions(actionManager: ActionManager) {
        super.unregisterActions(actionManager)
        predecessor = null
    }
}
