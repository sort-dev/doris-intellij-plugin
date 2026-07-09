package dev.sort.doris.cancel

import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Platform-wiring proof for the clean Doris cancel: the two plugin.xml seams actually engage in
 * a running IDE (the same fixture the other wiring tests use — the database plugin plus ours,
 * loaded via `idea.load.plugins.id`).
 *
 * Class-identity assertions compare FQN strings, not Class objects: the platform instantiates
 * plugin classes in the *plugin classloader*, while this test links against the test-classpath
 * copy — two different `Class` instances of the same name (the DorisCatalogs KDoc pattern).
 */
class DorisCancelWiringTest : BasePlatformTestCase() {

    /** `overrides="true"` replaced the stock `Console.Jdbc.Cancel` action with ours. */
    fun testStopButtonActionIsReplaced() {
        val action = ActionManager.getInstance().getAction("Console.Jdbc.Cancel")
        assertNotNull("Console.Jdbc.Cancel not registered at all", action)
        assertEquals(
            "dev.sort.doris.cancel.DorisCancelRunningStatementsAction",
            action!!.javaClass.name,
        )
    }

    /** Our subclass must remain a CancelRunningStatementsAction (stock delegation contract). */
    fun testReplacementActionExtendsStockAction() {
        val action = ActionManager.getInstance().getAction("Console.Jdbc.Cancel")!!
        var cls: Class<*>? = action.javaClass.superclass
        val superNames = generateSequence(cls) { it.superclass }.map { it.name }.toList()
        assertTrue(
            "expected stock CancelRunningStatementsAction in super chain, got $superNames",
            superNames.contains("com.intellij.database.actions.CancelRunningStatementsAction"),
        )
    }

    /** The connect-time interceptor is registered on the platform EP. */
    fun testConnectionInterceptorIsRegistered() {
        val names = DatabaseConnectionInterceptor.EP_NAME.extensionList.map { it.javaClass.name }
        assertTrue(
            "DorisTraceIdConnectionInterceptor missing from $names",
            names.contains("dev.sort.doris.cancel.DorisTraceIdConnectionInterceptor"),
        )
    }
}
