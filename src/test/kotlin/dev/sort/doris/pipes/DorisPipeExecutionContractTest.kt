package dev.sort.doris.pipes

import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.PipeExecutionContract
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.sql.DorisSqlDialect
import java.util.UUID

class DorisPipeExecutionContractTest : BasePlatformTestCase() {
    private val settings get() = project.service<DorisPipesSettings>()
    private var previousFlag: String? = null
    private var previousEnabled = false

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DorisPipes.PROPERTY)
        previousEnabled = settings.enabled
        System.clearProperty(DorisPipes.PROPERTY)
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            settings.enabled = previousEnabled
            settings.execMarks.clear()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            if (previousFlag == null) System.clearProperty(DorisPipes.PROPERTY)
            else System.setProperty(DorisPipes.PROPERTY, previousFlag!!)
        } finally {
            super.tearDown()
        }
    }

    private fun contract() = PipeExecutionContract(DorisPipeDialect, "doris", "mysql", { settings.enabled = it }) { sql ->
        val file = myFixture.configureByText("pipe-contract-${UUID.randomUUID()}.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        myFixture.editor
    }

    fun testDeclinesWhenNotOwned() = contract().declinesWhenNotOwned()
    fun testSelectsStatementsLikeStockExecute() = contract().selectsStatementsLikeStockExecute()
    fun testRefusesWholeScriptsItCannotRun() = contract().refusesWholeScriptsItCannotRun()
    fun testClaimsOnlyPipes() = contract().claimsOnlyPipes()
    fun testRefusesStaleInput() = contract().refusesStaleInput()
    fun testPropagatesCancellation() = contract().propagatesCancellation()
    fun testLowersNestedOnlyPipelines() = contract().lowersNestedOnlyPipelines()
    fun testRunsTopLevelStagesToTheCaret() = contract().runsTopLevelStagesToTheCaret()
    fun testRefusesDestinationChangesDuringPrompting() = contract().refusesDestinationChangesDuringPrompting()
    fun testNotifiesRefusals() = contract().notifiesRefusals()
}
