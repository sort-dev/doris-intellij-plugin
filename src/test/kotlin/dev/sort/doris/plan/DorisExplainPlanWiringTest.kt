package dev.sort.doris.plan

import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

/**
 * Wiring proof for the Explain-Plan suppressor stub: the DBMS-keyed [ExplainPlanProvider] resolves
 * to ours for DORIS and reports BOTH paths unsupported, so the graphical (and raw) "Explain Plan"
 * actions are hidden rather than falling back to MySQL's provider and showing a dead menu item.
 * (Explain Plan itself is deferred until a Doris plan-model parser exists — see the provider KDoc.)
 */
class DorisExplainPlanWiringTest : BasePlatformTestCase() {

    private val version: Version = Version.of(5, 7, 99)

    fun testProviderRegisteredForDoris() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)
        assertNotNull("no ExplainPlanProvider registered for DORIS", provider)
        assertEquals(
            "dev.sort.doris.plan.DorisExplainPlanProvider",
            provider!!.javaClass.name,
        )
    }

    fun testExplainReportedUnsupportedSoTheItemIsHidden() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)!!
        // Ui action gates on isSupported -> false hides the graphical "Explain Plan".
        assertFalse(provider.isSupported(version, false))
        assertFalse(provider.isSupported(version, true))
        // Raw is flag-gated off in the new UI anyway; keep it explicitly unsupported too.
        assertFalse(provider.isRawSupported(version, false))
        assertFalse(provider.isRawSupported(version, true))
    }
}
