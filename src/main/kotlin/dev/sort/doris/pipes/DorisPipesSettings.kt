package dev.sort.doris.pipes

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.PsiManagerEx
import com.intellij.util.xmlb.annotations.OptionTag
import dev.sort.doris.sql.DorisSqlDialect
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
@State(name = "DorisPipesSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class DorisPipesSettings(private val project: Project) :
    SerializablePersistentStateComponent<DorisPipesSettings.Options>(Options()) {
    // XMLB otherwise skips final scalar fields in these SDKs.
    data class Options(@field:OptionTag("enabled") @JvmField val enabled: Boolean = false)

    @Volatile private var initialized = false
    internal val execMarks = ConcurrentHashMap<String, DorisPipes.ExecMark>()

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            if (state.enabled == value) return
            updateState { it.copy(enabled = value) }
            refreshFiles()
        }

    override fun initializeComponent() { initialized = true }

    override fun loadState(state: Options) {
        val changed = state.enabled != this.state.enabled
        super.loadState(state)
        // Initial loading may happen while the parser is requesting the service.
        if (initialized && changed) refreshFiles()
    }

    private fun refreshFiles() {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val documents = PsiDocumentManager.getInstance(project)
            documents.commitAllDocuments()
            val psi = PsiManagerEx.getInstanceEx(project)
            val files = buildSet {
                psi.fileManager.allCachedFiles.forEach { add(it.viewProvider.virtualFile) }
                addAll(FileEditorManager.getInstance(project).openFiles)
                EditorFactory.getInstance().allEditors.filter { it.project === project }.forEach { editor ->
                    FileDocumentManager.getInstance().getFile(editor.document)?.let { add(it) }
                }
            }.filter { it.isValid && psi.findFile(it)?.language?.isKindOf(DorisSqlDialect.INSTANCE) == true }
            // Restarting the daemon alone would leave PIPE-dependent statement PSI cached.
            documents.reparseFiles(files, false)
            DaemonCodeAnalyzer.getInstance(project).restart()
        }
    }
}
