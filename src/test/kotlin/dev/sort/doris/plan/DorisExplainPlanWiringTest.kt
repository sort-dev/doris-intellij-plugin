package dev.sort.doris.plan

import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.util.Version
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

class DorisExplainPlanWiringTest : BasePlatformTestCase() {
    fun testProviderMakesTheItemVisiblePlainOnly() {
        val provider = checkNotNull(ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS))
        assertTrue(provider.isSupported(Version.of(5, 7, 99), false))
        assertFalse(provider.isSupported(Version.of(5, 7, 99), true))
        assertFalse(provider.isRawSupported(Version.of(5, 7, 99), false))
    }

    fun testExplainIsWrappedCooperatively() {
        val action = ActionManager.getInstance().getAction("Console.Jdbc.ExplainPlan")!!
        assertEquals("dev.brikk.house.sql.intellij.CooperativeAction", action.javaClass.name)
        val predecessor = (action as ActionWithDelegate<*>).delegate as AnAction
        assertTrue(predecessor.javaClass.name, predecessor.javaClass.name.startsWith("com.intellij.database.actions.ExplainActionBase"))
    }

    fun testProfileExplainsQueriesOnly() {
        assertSame(dev.brikk.house.sql.shape.ExplainableStatements.Queries, DorisTextExplainProfile.explainableStatements)
        assertSame(dev.sort.doris.pipes.DorisPipeDialect, DorisTextExplainProfile.pipe)
    }

    fun testHelperSchemaSwitchUsesTheDorisPathShape() {
        val path = com.intellij.database.util.SearchPath.of(com.intellij.database.util.ObjectPath
            .create("warehouse", com.intellij.database.model.ObjectKind.DATABASE).append("analytics", com.intellij.database.model.ObjectKind.SCHEMA))
        assertEquals("use `warehouse`.`analytics`", DorisTextExplainProfile.searchPath.switchSql(path))
    }
}
