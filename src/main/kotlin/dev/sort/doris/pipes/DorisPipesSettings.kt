package dev.sort.doris.pipes

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.ToggleSettings
import dev.sort.doris.sql.DorisSqlDialect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
@State(name = "DorisPipesSettings", storages = [Storage(ToggleSettings.STORAGE)])
class DorisPipesSettings(project: Project) : ToggleSettings(project, DorisSqlDialect.INSTANCE) {
    internal val execMarks = ConcurrentHashMap<String, DorisPipes.ExecMark>()
    internal val diagnosticRevision = AtomicLong()

    override fun onChanged(enabled: Boolean) {
        if (!enabled) execMarks.clear()
    }
}
