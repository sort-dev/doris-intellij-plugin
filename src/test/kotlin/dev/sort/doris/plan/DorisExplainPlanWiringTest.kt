package dev.sort.doris.plan

import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.util.Version
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

/**
 * Wiring for option C: the "Explain Plan" action shows for Doris (provider reports it supported) and
 * is overridden by [DorisExplainPlanAction], which runs `EXPLAIN <query>` as a query (text in the
 * grid) instead of the graphical diagram. Running the query + grid display are the live-Doris bake.
 */
class DorisExplainPlanWiringTest : BasePlatformTestCase() {

    private val version: Version = Version.of(5, 7, 99)

    fun testProviderMakesTheItemVisiblePlainOnly() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)
        assertNotNull(provider)
        assertEquals("dev.sort.doris.plan.DorisExplainPlanProvider", provider!!.javaClass.name)
        // isSupported = the graphical "Explain Plan" action is visible for plain EXPLAIN...
        assertTrue(provider.isSupported(version, false))
        // ...but "Explain Analyse" stays hidden (Doris EXPLAIN doesn't run the query), and no raw.
        assertFalse(provider.isSupported(version, true))
        assertFalse(provider.isRawSupported(version, false))
    }

    /** `overrides="true"` replaced the stock Explain Plan action with ours (compare by FQN). */
    fun testExplainPlanActionIsOverridden() {
        val action = ActionManager.getInstance().getAction("Console.Jdbc.ExplainPlan")
        assertNotNull("Console.Jdbc.ExplainPlan not registered", action)
        assertEquals("dev.sort.doris.plan.DorisExplainPlanAction", action!!.javaClass.name)
    }

    /** Our action must remain an Explain Plan action (stock delegation for non-Doris). */
    fun testOverrideExtendsStockExplainAction() {
        val action = ActionManager.getInstance().getAction("Console.Jdbc.ExplainPlan")!!
        val superNames = generateSequence(action.javaClass.superclass) { it.superclass }.map { it.name }.toList()
        assertTrue(
            "expected ExplainActionBase in super chain, got $superNames",
            superNames.any { it.startsWith("com.intellij.database.actions.ExplainActionBase") },
        )
    }
}
