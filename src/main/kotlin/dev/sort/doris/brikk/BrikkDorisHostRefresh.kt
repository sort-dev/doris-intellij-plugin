package dev.sort.doris.brikk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import dev.brikk.house.intellij.BrikkHostState
import dev.brikk.house.intellij.BrikkHosts
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.util.concurrent.CopyOnWriteArrayList

@Service(Service.Level.PROJECT)
class BrikkDorisHostRefresh(private val project: Project) {
    private val pending = CopyOnWriteArrayList<SmartPsiElementPointer<KtStringTemplateExpression>>()

    init {
        project.messageBus.connect().subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { changed() })
    }

    fun watch(host: KtStringTemplateExpression) {
        if (pending.none { it.element === host }) pending += SmartPointerManager.getInstance(project).createSmartPsiElementPointer(host)
    }

    private fun changed() {
        pending.removeIf { it.element == null }
        if (pending.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            DumbService.getInstance(project).runWhenSmart {
                runReadAction {
                    val ready = pending.mapNotNull { pointer ->
                        val host = pointer.element
                        if (host == null) { pending.remove(pointer); null }
                        else when (val state = BrikkHosts.state(host)) {
                            is BrikkHostState.Recognized -> if (!state.context.uncertain) {
                                pending.remove(pointer)
                                host.containingFile
                            } else null
                            BrikkHostState.NotBrikk -> { pending.remove(pointer); host.containingFile }
                            is BrikkHostState.Pending -> null
                        }
                    }.distinct()
                    ready.forEach(DorisHostSqlChannel::requestReanalysis)
                }
            }
        }, project.disposed)
    }
}
