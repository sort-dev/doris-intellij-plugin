package dev.sort.doris.pipes

import com.intellij.openapi.components.service
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

class DorisPipesConfigurable(private val project: Project) : SearchableConfigurable {
    private var checkbox: JBCheckBox? = null

    override fun getId(): String = "dev.sort.doris.pipes"
    override fun getDisplayName(): String = "Apache Doris PIPE"

    override fun createComponent(): JComponent {
        val enabled = JBCheckBox("Enable PIPE syntax in this project").also { checkbox = it }
        val vetoed = !DorisPipes.isEnabledValue(System.getProperty(DorisPipes.PROPERTY))
        enabled.isEnabled = !vetoed
        reset()
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            enabled.alignmentX = 0f
            add(enabled)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(JBLabel(if (vetoed) "PIPE is disabled by the VM option -Ddoris.pipes=false."
                else "Enable editor support and transpile PIPE queries to Doris SQL on execution.").apply {
                alignmentX = 0f
            })
        }
    }

    override fun isModified(): Boolean = checkbox?.isSelected?.let { it != project.service<DorisPipesSettings>().enabled } ?: false

    override fun apply() {
        checkbox?.let { project.service<DorisPipesSettings>().enabled = it.isSelected }
    }

    override fun reset() { checkbox?.isSelected = project.service<DorisPipesSettings>().enabled }
    override fun disposeUIResources() { checkbox = null }
}
