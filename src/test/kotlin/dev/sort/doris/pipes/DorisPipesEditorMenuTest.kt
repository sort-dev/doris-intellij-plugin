package dev.sort.doris.pipes

import com.intellij.openapi.components.service
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.EditorPopupContract
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.doris.sql.DorisSqlDialect

class DorisPipesEditorMenuTest : BasePlatformTestCase() {
    private val settings get() = project.service<DorisPipesSettings>()
    private val contract by lazy {
        EditorPopupContract(myFixture, "DorisPipes.EditorMenu", listOf("DorisPipes.PreviewSql", "DorisPipes.RunToStage"))
    }
    private var previousFlag: String? = null
    private var previousEnabled = false

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DorisPipes.PROPERTY)
        previousEnabled = settings.enabled
        System.clearProperty(DorisPipes.PROPERTY)
        settings.enabled = true
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            settings.enabled = previousEnabled
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            previousFlag?.let { System.setProperty(DorisPipes.PROPERTY, it) } ?: System.clearProperty(DorisPipes.PROPERTY)
        } finally {
            super.tearDown()
        }
    }

    private fun openValidPipeContext() {
        myFixture.configureByText("pipes.sql", "FROM t |> WHERE x = 1 |> SEL<caret>ECT x;")
    }

    fun testEditorPopupShowsTheMenuOnlyWherePipesApply() {
        contract.check(unrelated = mapOf("popup-notes.txt" to "SELECT 1", "Notes.kt" to "fun main() { println(\"FROM t |> SELECT x\") }")) {
            openValidPipeContext()
        }
    }

    fun testOtherDialectNoPipeAndPipesOffHideTheMenu() {
        openValidPipeContext()
        contract.assertShown(what = "a PIPE statement")
        settings.enabled = false
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        contract.assertAbsent(what = "PIPE syntax off")
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        myFixture.configureByText("plain.sql", "SELECT x FROM t WHERE x = 1;")
        contract.assertAbsent(what = "a statement without PIPE stages")
        myFixture.configureByText("mysql.sql", "FROM t |> SELECT x;")
        setSqlDialectMapping(project, myFixture.file.virtualFile, MysqlDialect.INSTANCE, testRootDisposable)
        contract.assertAbsent(what = "PIPE text in another SQL dialect")
    }
}
