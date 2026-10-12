package dev.sort.doris.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.ToggleConfigurable

class DorisPipesConfigurable(private val project: Project) : ToggleConfigurable(
    "dev.sort.doris.pipes", "Apache Doris PIPE", "Enable PIPE syntax in this project",
    "Enable editor support and transpile PIPE queries to Doris SQL on execution.", DorisPipes.PROPERTY,
) {
    override val settings get() = project.service<DorisPipesSettings>()
}
