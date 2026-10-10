package dev.sort.doris.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.util.function.Function
import javax.swing.JComponent

class BrikkDorisNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        val psi = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return null
        val active = PsiTreeUtil.findChildrenOfType(psi, KtStringTemplateExpression::class.java)
            .any { host -> BrikkDorisTemplate.isRecognized(host) &&
                InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
                    ?.any { BrikkDorisContext.isActive(it.first.containingFile) } == true }
        if (!active) return null
        return Function { editor -> EditorNotificationPanel(editor).apply {
            text = "Brikk-house Doris editing profile: Kotlin binds, relation contracts and pipe completion enabled. Templates are not executable SQL."
        } }
    }
}
