package dev.sort.doris.plan

import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.plan.PlanModel
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

/**
 * ROUGH-DRAFT wiring for the structured Doris Explain Plan. Verifies the provider resolves for DORIS
 * and reports the new-UI graphical path supported (plain EXPLAIN, not analyse), plus the pure
 * text -> PlanModel builder. The query-run + rendering are the live-Doris bake.
 */
class DorisExplainPlanWiringTest : BasePlatformTestCase() {

    private val version: Version = Version.of(5, 7, 99)

    fun testProviderRegisteredForDoris() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)
        assertNotNull(provider)
        assertEquals("dev.sort.doris.plan.DorisExplainPlanProvider", provider!!.javaClass.name)
    }

    fun testGraphicalPlanSupportedPlainOnly() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)!!
        assertTrue("graphical Explain Plan should be supported", provider.isSupported(version, false))
        assertFalse("Explain Analyse unsupported (Doris EXPLAIN doesn't run the query)", provider.isSupported(version, true))
        assertFalse(provider.isRawSupported(version, false))
    }

    fun testBuildPlanModelIsFlatOnePerLine() {
        val explain = """
            PLAN FRAGMENT 0
              OUTPUT EXPRS: `a`

              1:VHASH JOIN

              0:VOlapScanNode
        """.trimIndent()
        val model = DorisExplainPlanProvider.buildPlanModel(explain)
        val kids = model.root.children
        // 4 non-blank lines -> 4 operation nodes under one root.
        assertEquals(4, kids.size)
        assertEquals("PLAN FRAGMENT 0", kids[0].title)
        assertEquals(PlanModel.NodeType.ROOT, model.root.type)
        assertTrue(kids.all { it.type == PlanModel.NodeType.OPERATION })
    }
}
