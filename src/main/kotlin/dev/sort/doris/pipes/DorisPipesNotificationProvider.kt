package dev.sort.doris.pipes

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import dev.brikk.house.sql.intellij.FileBanner
import dev.brikk.house.sql.intellij.FileBannerProvider

private val introspectionBanner = FileBanner("DorisPipes.introspectionMiss")

class DorisPipesNotificationProvider : FileBannerProvider(introspectionBanner, EditorNotificationPanel.Status.Warning) {
    companion object {
        fun reportMiss(project: Project, file: VirtualFile, message: String) = introspectionBanner.show(project, file, message)
        fun clearMiss(project: Project, file: VirtualFile) = introspectionBanner.clear(project, file)
    }
}
