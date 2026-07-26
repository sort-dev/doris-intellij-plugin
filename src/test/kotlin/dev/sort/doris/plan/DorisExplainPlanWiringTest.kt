package dev.sort.doris.plan

import com.intellij.database.plan.ExplainPlanProvider
import com.intellij.database.util.Version
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisDbms

/**
 * Platform-wiring proof for Doris Explain Plan: the DBMS-keyed [ExplainPlanProvider] extension
 * resolves to ours for the DORIS dbms, and reports raw-only support so the console's raw Explain
 * item is the one that shows (the graphical variant is hidden — see [DorisExplainPlanProvider]).
 *
 * Class-identity is asserted by FQN string, not Class object: the platform instantiates plugin
 * classes in the plugin classloader while the test links the test-classpath copy (the DorisCancel
 * wiring-test pattern).
 */
class DorisExplainPlanWiringTest : BasePlatformTestCase() {

    private val version: Version = Version.of(5, 7, 99) // the version Doris reports over the MySQL wire

    fun testProviderRegisteredForDoris() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)
        assertNotNull("no ExplainPlanProvider registered for DORIS", provider)
        assertEquals(
            "dev.sort.doris.plan.DorisExplainPlanProvider",
            provider!!.javaClass.name,
        )
    }

    fun testRawSupportedButNotStructured() {
        val provider = ExplainPlanProvider.EP.forDbms(DorisDbms.DORIS)!!
        // Graphical plan tree off → Console.Jdbc.ExplainPlan (Ui) is hidden.
        assertFalse("structured plan must be unsupported", provider.isSupported(version, false))
        assertFalse(provider.isSupported(version, true))
        // Raw plan on → Console.Jdbc.ExplainPlan.Raw shows; analyse variant stays off.
        assertTrue("raw plan must be supported", provider.isRawSupported(version, false))
        assertFalse("Doris EXPLAIN does not run the query, so analyse is unsupported", provider.isRawSupported(version, true))
    }
}
