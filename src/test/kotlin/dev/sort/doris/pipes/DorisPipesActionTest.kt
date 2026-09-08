package dev.sort.doris.pipes

import com.intellij.database.actions.RunQueryAction
import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionWithDelegate
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.PerformWithDocumentsCommitted
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.PlaceProvider
import java.util.UUID
import javax.swing.JPanel
import javax.swing.KeyStroke

class DorisPipesActionTest : BasePlatformTestCase() {
    fun testAllVariantsAndRegistrationOrdersDelegateExactlyOnce() {
        val dialects = listOf("doris", "trino", "duckdb")
        val orders = dialects.flatMap { first ->
            (dialects - first).map { second -> listOf(first, second, dialects.single { it != first && it != second }) }
        }
        val manager = ActionManager.getInstance()
        for (variant in 1..4) {
            for (order in orders) {
                val visits = mutableListOf<String>()
                val terminal = object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) { visits.add("stock") }
                }
                val id = "Doris.B1.Test.${UUID.randomUUID()}"
                manager.registerAction(id, terminal)
                try {
                    for (dialect in order) {
                        val previous = manager.getAction(id)!!
                        val replacement = if (dialect == "doris") {
                            wrap(variant, previous) { e, _ ->
                                visits.add("doris")
                                e.getData(DIALECT) == "doris" && e.getData(PIPE) == true
                            }
                        } else {
                            PeerAction(previous, dialect, visits)
                        }
                        manager.replaceAction(id, replacement)
                    }
                    val action = manager.getAction(id)!!
                    for (dialect in dialects + "postgres") {
                        for (pipe in listOf(false, true)) {
                            visits.clear()
                            action.actionPerformed(event(dialect, pipe))
                            val reversed = order.reversed()
                            val expected = if (pipe && dialect in dialects) {
                                reversed.take(reversed.indexOf(dialect) + 1)
                            } else reversed + "stock"
                            assertEquals("variant=$variant order=$order dialect=$dialect pipe=$pipe", expected, visits)
                        }
                    }
                } finally {
                    manager.unregisterAction(id)
                }
            }
        }
    }

    fun testPresentationShortcutsAndExecutionPolicyAreDelegated() {
        var performed = 0
        var seenEvent: AnActionEvent? = null
        val previous = object : AnAction("Previous Execute"), PerformWithDocumentsCommitted {
            override fun update(e: AnActionEvent) {
                e.presentation.text = "Peer-specific text"
                e.presentation.description = "Peer-specific description"
                e.presentation.isEnabled = false
                e.presentation.isVisible = false
            }
            override fun actionPerformed(e: AnActionEvent) { performed++; seenEvent = e }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun isDumbAware(): Boolean = false
            override fun isInInjectedContext(): Boolean = true
            override fun isPerformWithDocumentsCommitted(): Boolean = true
        }
        previous.shortcutSet = CustomShortcutSet(KeyStroke.getKeyStroke("ctrl ENTER"))
        for (variant in 1..4) {
            val action = wrap(variant, previous) { _, _ -> false }
            assertSame(previous, action.delegate)
            assertEquals(previous.templatePresentation.text, action.templatePresentation.text)
            assertEquals(previous.shortcutSet.shortcuts.toList(), action.shortcutSet.shortcuts.toList())
            assertEquals(ActionUpdateThread.EDT, action.actionUpdateThread)
            assertFalse(action.isDumbAware)
            assertTrue(action.isInInjectedContext)
            assertTrue(action.isPerformWithDocumentsCommitted)
            val e = event("other", false)
            action.update(e)
            assertEquals("Peer-specific text", e.presentation.text)
            assertEquals("Peer-specific description", e.presentation.description)
            assertFalse(e.presentation.isEnabled)
            assertFalse(e.presentation.isVisible)
            action.actionPerformed(e)
            assertSame(e, seenEvent)
        }
        assertEquals(4, performed)
    }

    fun testVariantOptionsStayLiveAndSelectionMatchesStock() {
        val settings = DatabaseSettings.getSettings()
        val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) {} }
        val getOption = RunQueryAction::class.java.getDeclaredMethod("getExecOption").apply { isAccessible = true }
        for (variant in 1..3) {
            val action = wrap(variant, previous) { _, _ -> false }
            assertSame(settings.execOptions[variant - 1], getOption.invoke(action))
            val old = settings.execOptions[variant - 1]
            try {
                val replacement = DatabaseSettings.ExecOption().apply { newTab = true }
                settings.execOptions[variant - 1] = replacement
                assertSame(replacement, getOption.invoke(action))
            } finally {
                settings.execOptions[variant - 1] = old
            }
        }
        val actual = getOption.invoke(DorisPipesRunSelectionAction(previous)) as DatabaseSettings.ExecOption
        val stock = getOption.invoke(RunQueryAction.RunSelectionExactlyAsOneStatement()) as DatabaseSettings.ExecOption
        assertEquals(stock.newTab, actual.newTab)
        assertEquals(stock.execInside, actual.execInside)
        assertEquals(stock.execOutside, actual.execOutside)
        assertEquals(stock.execSelection, actual.execSelection)
    }

    fun testSelectionAvailabilityMatchesTheCapturedStockAction() {
        val file = myFixture.configureByText("selection.sql", "SELECT 1;")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()
        val stock = RunQueryAction.RunSelectionExactlyAsOneStatement()
        val wrapper = DorisPipesRunSelectionAction(stock) { _, _ -> error("update must not intercept execution") }
        for (selected in listOf(false, true)) {
            if (selected) myFixture.editor.selectionModel.setSelection(0, file.textLength)
            else myFixture.editor.selectionModel.removeSelection()
            val expected = AnActionEvent.createFromDataContext("B1Test", Presentation(), context)
            val actual = AnActionEvent.createFromDataContext("B1Test", Presentation(), context)
            stock.update(expected)
            wrapper.update(actual)
            assertEquals(expected.presentation.isEnabled, actual.presentation.isEnabled)
            assertEquals(expected.presentation.isVisible, actual.presentation.isVisible)
            if (!selected) {
                assertFalse(actual.presentation.isEnabled)
                assertFalse(actual.presentation.isVisible)
            }
        }
    }

    fun testStructureViewEditorFallbackPreservesThePipeCandidate() {
        val file = myFixture.configureByText("structure.sql", "FROM t |> SELECT * -- ;\n|> WHERE tenant_id = 7;")
        val editor = myFixture.editor
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_FILE, file)
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, arrayOf(file))
            .add(PlatformCoreDataKeys.FILE_EDITOR, TextEditorProvider.getInstance().getTextEditor(editor))
            .build()
        val e = AnActionEvent.createFromDataContext("StructureViewPopup", Presentation(), context)
        assertNull(e.getData(CommonDataKeys.EDITOR))
        assertSame(editor, editorForPipeExecution(e))
        val resolved = editorForPipeExecution(e)!!
        assertEquals(file.text, DorisPipes.chunkAt(resolved.document.text, resolved.caretModel.offset)!!.text)
        assertEquals(file.text, editor.document.text)
    }

    fun testDisabledAndConsoleFreeExecutionReachThePreviousAction() {
        val oldFlag = System.getProperty(DorisPipes.PROPERTY)
        var calls = 0
        val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { calls++ } }
        try {
            for (enabled in listOf("false", "true")) {
                System.setProperty(DorisPipes.PROPERTY, enabled)
                for (variant in 1..4) {
                    val action = if (variant == 4) DorisPipesRunSelectionAction(previous)
                        else DorisPipesRunQueryAction(variant, previous)
                    action.actionPerformed(event("doris", true))
                }
            }
            assertEquals(8, calls)
        } finally {
            if (oldFlag == null) System.clearProperty(DorisPipes.PROPERTY)
            else System.setProperty(DorisPipes.PROPERTY, oldFlag)
        }
    }

    fun testHandledRejectionAndThrownFailureDoNotInvokeThePreviousAction() {
        var calls = 0
        val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { calls++ } }
        for (variant in 1..4) {
            wrap(variant, previous) { _, _ -> true }.actionPerformed(event("doris", true))
            val failure = IllegalStateException("claimed handler failed")
            try {
                wrap(variant, previous) { _, _ -> throw failure }.actionPerformed(event("doris", true))
                fail("expected the handler failure")
            } catch (actual: IllegalStateException) {
                assertSame(failure, actual)
            }
        }
        assertEquals(0, calls)
    }

    fun testEngineSafetyRefusalReportsWithoutSubmittingOrDelegating() {
        var calls = 0
        var reports = 0
        var submissions = 0
        val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { calls++ } }
        val text = "FROM t AS a |> FULL OUTER JOIN u AS b ON a.id = b.id |> LIMIT 2 |> SELECT a.id"
        for (variant in 1..4) {
            wrap(variant, previous) { _, _ ->
                dispatchPipeTranslation(
                    DorisPipesEngine.transpile(text),
                    reportError = { error ->
                        reports++
                        assertTrue(error.message.contains("Cannot preserve a pipe SELECT/DISTINCT input boundary"))
                    },
                    submit = { submissions++; false },
                )
            }.actionPerformed(event("doris", true))
        }
        assertEquals(4, reports)
        assertEquals(0, submissions)
        assertEquals(0, calls)
        assertFalse(dispatchPipeTranslation(DorisPipesEngine.Transpile.NotPipe, { fail("NotPipe is not an error") }, { fail("NotPipe must delegate"); false }))
        for (submitted in listOf(false, true)) {
            val success = DorisPipesEngine.Transpile.Ok("SELECT 1")
            var submissionErrors = 0
            assertTrue(dispatchPipeTranslation(success, { submissionErrors++ }, { assertSame(success, it); submitted }))
            assertEquals(if (submitted) 0 else 1, submissionErrors)
        }
    }

    fun testShortcutPromotionSurvivesAChainWithNoDatabasePackageCandidate() {
        val component = object : JPanel(), PlaceProvider {
            override fun getPlace(): String = "StructureViewPopup"
        }
        val context = SimpleDataContext.builder().add(PlatformCoreDataKeys.CONTEXT_COMPONENT, component).build()
        val stock = RunQueryAction.Alt1()
        val wrapped = wrap(1, stock) { _, _ -> false }
        val top = PeerAction(wrapped, "trino", mutableListOf())
        val other = object : AnAction() { override fun actionPerformed(e: AnActionEvent) {} }
        val promoter = DorisPipesActionPromoter()
        assertEquals(listOf(wrapped), promoter.promote(listOf(other, wrapped), context))
        assertEquals(listOf(top), promoter.promote(listOf(other, top), context))
        assertTrue(promoter.promote(listOf(other, stock), context).isEmpty())
        assertTrue(promoter.promote(listOf(wrapped), DataContext.EMPTY_CONTEXT).isEmpty())
    }

    private class PeerAction(
        private val previous: AnAction,
        private val dialect: String,
        private val visits: MutableList<String>,
    ) : AnAction(), ActionWithDelegate<AnAction> {
        override fun getDelegate(): AnAction = previous
        override fun actionPerformed(e: AnActionEvent) {
            visits.add(dialect)
            if (e.getData(DIALECT) != dialect || e.getData(PIPE) != true) previous.actionPerformed(e)
        }
    }

    private fun wrap(
        variant: Int,
        previous: AnAction,
        intercept: (AnActionEvent, DatabaseSettings.ExecOption) -> Boolean,
    ): DorisPipesRunQueryAction = if (variant == 4) DorisPipesRunSelectionAction(previous, intercept)
        else DorisPipesRunQueryAction(variant, previous, intercept)

    private fun event(dialect: String, pipe: Boolean): AnActionEvent = AnActionEvent.createFromDataContext(
        "B1Test", Presentation(), SimpleDataContext.builder().add(DIALECT, dialect).add(PIPE, pipe).build(),
    )

    private companion object {
        val DIALECT = DataKey.create<String>("doris.b1.test.dialect")
        val PIPE = DataKey.create<Boolean>("doris.b1.test.pipe")
    }
}
