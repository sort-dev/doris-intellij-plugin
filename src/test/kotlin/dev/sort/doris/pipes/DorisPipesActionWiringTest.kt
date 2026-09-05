package dev.sort.doris.pipes

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DorisPipesActionWiringTest : BasePlatformTestCase() {
    fun testOptionalDescriptorRegistersOnlyWithTheProvider() {
        val mode = System.getProperty("b1.provider")
        val provider = PluginManagerCore.getPlugin(PluginId.getId(PROVIDER))
        val installed = mode == "installed"
        if (installed) {
            assertNotNull(provider)
            assertTrue(provider!!.isEnabled)
        } else if (mode == "absent") {
            assertNull("provider descriptor must be absent, not merely disabled", provider)
        }
        val stockClasses = listOf("Alt1", "Alt2", "Alt3", "RunSelectionExactlyAsOneStatement")
        for ((index, id) in DorisPipesActionConfiguration.EXECUTE_IDS.withIndex()) {
            val action = ActionManager.getInstance().getAction(id)!!
            if (installed) {
                val expected = if (index == 3) "DorisPipesRunSelectionAction" else "DorisPipesRunQueryAction"
                assertEquals("dev.sort.doris.pipes.$expected", action.javaClass.name)
                val previous = (action as ActionWithDelegate<*>).delegate!!
                assertEquals("com.intellij.database.actions.RunQueryAction\$${stockClasses[index]}", previous.javaClass.name)
            } else {
                assertEquals("com.intellij.database.actions.RunQueryAction\$${stockClasses[index]}", action.javaClass.name)
            }
        }
    }

    fun testInstalledPipeSupportIsRestartBound() {
        if (System.getProperty("b1.provider") != "installed") return
        val ep = ApplicationManager.getApplication().extensionArea
            .getExtensionPoint<Any>("com.intellij.actionConfigurationCustomizer")
        assertFalse(ep.isDynamic)
        assertTrue(ep.extensionList.any { it.javaClass.name == "dev.sort.doris.pipes.DorisPipesActionConfiguration" })
        for (id in listOf("dev.sort.doris-intellij-plugin", PROVIDER)) {
            val descriptor = PluginManagerCore.getPlugin(PluginId.getId(id))!!
            // The reason-returning validator was renamed and its parameter changed in 262.
            val validator = DynamicPlugins::class.java.methods.firstOrNull {
                it.name == "validateCanUnloadWithoutRestart" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isInstance(descriptor) && it.returnType == String::class.java
            } ?: DynamicPlugins::class.java.methods.first {
                it.name == "checkCanUnloadWithoutRestart" && it.parameterCount == 1 &&
                    it.parameterTypes[0].isInstance(descriptor) && it.returnType == String::class.java
            }
            assertNotNull("$id must require restart for unload", validator.invoke(DynamicPlugins, descriptor))
        }
    }

    private companion object {
        const val PROVIDER = "dev.sort.sql-transpiler-intellij-plugin"
    }
}
