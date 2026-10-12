package dev.sort.doris.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.ToggleConfigurable

class DorisPipesConfigurable(private val project: Project) : ToggleConfigurable(
    "dev.sort.doris.pipes", "Apache Doris PIPE", "Doris PIPE support",
    "Enable editor support and transpile queries supported by Doris PIPE syntax to Doris SQL on execution.", DorisPipes.PROPERTY,
) {
    override val settings get() = project.service<DorisPipesSettings>()
}
