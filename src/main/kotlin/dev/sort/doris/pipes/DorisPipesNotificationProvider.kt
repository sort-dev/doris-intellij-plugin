package dev.sort.doris.pipes

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.Collections
import java.util.function.Function

/**
 * PIPES SPIKE: banner at the top of a console editor when a pipe statement references a table
 * that is enumerated in the model but NOT introspected (the M9 "enumerated-but-childless" case —
 * column completion silently has nothing to offer until the schema is introspected). Recorded by
 * the pipe column provider on a resolution miss; cleared on dismiss or on the first successful
 * resolution for the file.
 */
class DorisPipesNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out EditorNotificationPanel?>? {
        val message = MISSES[file.url] ?: return null
        return Function { _: FileEditor ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Warning).apply {
                text = message
                createActionLabel("Dismiss") {
                    MISSES.remove(file.url)
                    EditorNotifications.getInstance(project).updateNotifications(file)
                }
            }
        }
    }

    companion object {
        private val MISSES: MutableMap<String, String> = Collections.synchronizedMap(HashMap())

        fun reportMiss(project: Project, file: VirtualFile, tableFqn: String) {
            val message = "Doris Pipes: '$tableFqn' is not introspected — column completion is " +
                "unavailable. Introspect it in the Database view (or adjust the introspection scope)."
            if (MISSES.put(file.url, message) != message) {
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
        }

        fun clearMiss(project: Project, file: VirtualFile) {
            if (MISSES.remove(file.url) != null) {
                EditorNotifications.getInstance(project).updateNotifications(file)
            }
        }
    }
}
