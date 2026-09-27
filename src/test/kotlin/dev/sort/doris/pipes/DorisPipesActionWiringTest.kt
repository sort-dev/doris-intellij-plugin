package dev.sort.doris.pipes

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DorisPipesActionWiringTest : BasePlatformTestCase() {
    fun testActionsRegisterIndependentlyOfTheCompanionAndProjectSetting() {
        val mode = System.getProperty("test.sqlTranspiler")
        val provider = PluginManagerCore.getPlugin(PluginId.getId(PROVIDER))
        val installed = mode == "installed"
        if (installed) {
            assertNotNull(provider)
            assertTrue(provider!!.isEnabled)
        } else {
            assertNull("provider descriptor must be absent, not merely disabled", provider)
        }
        assertFalse("PIPE must default off even when the companion is installed", DorisPipes.isEnabled(project))
        val stockClasses = listOf("Alt1", "Alt2", "Alt3", "RunSelectionExactlyAsOneStatement")
        for ((index, id) in DorisPipesActionConfiguration.EXECUTE_IDS.withIndex()) {
            val action = ActionManager.getInstance().getAction(id)!!
            val expected = if (index == 3) "DorisPipesRunSelectionAction" else "DorisPipesRunQueryAction"
            assertEquals("dev.sort.doris.pipes.$expected", action.javaClass.name)
            val previous = (action as ActionWithDelegate<*>).delegate
            assertEquals("com.intellij.database.actions.RunQueryAction\$${stockClasses[index]}", previous.javaClass.name)
        }
    }

    fun testCustomizerUsesOnlyThePublicDynamicExtensionPoint() {
        val area = ApplicationManager.getApplication().extensionArea
        val dynamic = area.getExtensionPoint<Any>("com.intellij.dynamicActionConfigurationCustomizer")
        assertTrue(dynamic.isDynamic)
        assertTrue(dynamic.extensionList.any { it is DorisPipesActionConfiguration })
        val legacy = area.getExtensionPoint<Any>("com.intellij.actionConfigurationCustomizer")
        assertFalse(legacy.extensionList.any { it.javaClass.name.startsWith("dev.sort.doris.") })
    }

    fun testUnregisterRestoresTheCapturedPredecessor() {
        val manager = ActionManager.getInstance()
        val before = DorisPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it)!! }
        val configuration = DorisPipesActionConfiguration()
        try {
            configuration.registerActions(manager)
            configuration.registerActions(manager)
            for ((index, id) in DorisPipesActionConfiguration.EXECUTE_IDS.withIndex()) {
                val top = manager.getAction(id) as DorisPipesRunQueryAction
                assertSame("registration must be idempotent", before[index], top.delegate)
            }
            configuration.unregisterActions(manager)
            for ((index, id) in DorisPipesActionConfiguration.EXECUTE_IDS.withIndex()) {
                assertSame(before[index], manager.getAction(id))
            }
        } finally {
            DorisPipesActionConfiguration.EXECUTE_IDS.forEachIndexed { index, id -> manager.replaceAction(id, before[index]) }
        }
    }

    fun testUnregisterUnderAPeerLeavesAPassThroughWrapper() {
        val manager = ActionManager.getInstance()
        val before = DorisPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it)!! }
        val configuration = DorisPipesActionConfiguration()
        try {
            configuration.registerActions(manager)
            val ours = DorisPipesActionConfiguration.EXECUTE_IDS.map { manager.getAction(it) as DorisPipesRunQueryAction }
            val peers = DorisPipesActionConfiguration.EXECUTE_IDS.mapIndexed { index, id ->
                PeerAction(ours[index]).also { manager.replaceAction(id, it) }
            }
            configuration.unregisterActions(manager)
            for ((index, id) in DorisPipesActionConfiguration.EXECUTE_IDS.withIndex()) {
                assertSame("a peer's chain is not rewritten", peers[index], manager.getAction(id))
                assertTrue(ours[index].isDetached)
                assertSame(before[index], ours[index].delegate)
            }
        } finally {
            DorisPipesActionConfiguration.EXECUTE_IDS.forEachIndexed { index, id -> manager.replaceAction(id, before[index]) }
        }
    }

    private class PeerAction(private val previous: AnAction) : AnAction(), ActionWithDelegate<AnAction> {
        override fun getDelegate(): AnAction = previous
        override fun actionPerformed(e: AnActionEvent) = previous.actionPerformed(e)
    }

    private companion object {
        const val PROVIDER = "dev.sort.sql-transpiler-intellij-plugin"
    }
}
