package dev.sort.doris.pipes

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.configurationStore.deserialize
import com.intellij.configurationStore.serialize
import com.intellij.mock.MockProject
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.DebugUtil
import dev.sort.doris.setSqlDialectMapping
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.components.JBCheckBox
import dev.sort.doris.sql.DorisErrorAnnotator
import dev.sort.doris.sql.DorisSqlDialect
import java.lang.reflect.Proxy

class DorisPipesSettingsTest : BasePlatformTestCase() {
    private val settings get() = project.service<DorisPipesSettings>()
    private var previousFlag: String? = null
    private var previousEnabled = false

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DorisPipes.PROPERTY)
        previousEnabled = settings.enabled
        System.clearProperty(DorisPipes.PROPERTY)
        settings.enabled = false
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

    fun testDefaultOffAndLegacyPropertyOnlyVetoes() {
        assertFalse(DorisPipes.isEnabled(project))
        System.setProperty(DorisPipes.PROPERTY, "true")
        assertFalse(DorisPipes.isEnabled(project))
        settings.enabled = true
        assertTrue(DorisPipes.isEnabled(project))
        System.setProperty(DorisPipes.PROPERTY, "FALSE")
        assertFalse(DorisPipes.isEnabled(project))
        assertFalse(DorisPipes.isEnabled(null))
        assertFalse(DorisPipes.isEnabled(ProjectManager.getInstance().defaultProject))
    }

    fun testProjectPolicyAndExecutionMarksAreIsolated() {
        val other = MockProject(null, testRootDisposable)
        val otherSettings = DorisPipesSettings(other)
        other.registerService(DorisPipesSettings::class.java, otherSettings)
        settings.enabled = true
        assertTrue(DorisPipes.isEnabled(project))
        assertFalse(DorisPipes.isEnabled(other))
        val text = "FROM t |> LIMIT 1"
        val mark = DorisPipes.ExecMark(5, 6, "test", text.hashCode())
        DorisPipes.setExecMark(project, "file:///shared.sql", mark)
        assertEquals(mark, DorisPipes.execMarkFor(project, "file:///shared.sql", text))
        assertNull(DorisPipes.execMarkFor(other, "file:///shared.sql", text))
        otherSettings.loadState(DorisPipesSettings.Options(enabled = true))
        settings.enabled = false
        assertTrue(DorisPipes.isEnabled(other))
        assertFalse(DorisPipes.isEnabled(project))
        Disposer.dispose(other)
        assertFalse(DorisPipes.isEnabled(other))
    }

    fun testDisablingPipeClearsServerErrorMarks() {
        settings.enabled = true
        val text = "FROM t |> LIMIT 1"
        DorisPipes.setExecMark(project, "file:///stale.sql", DorisPipes.ExecMark(5, 6, "stale", text.hashCode()))
        assertNotNull(DorisPipes.execMarkFor(project, "file:///stale.sql", text))
        settings.enabled = false
        assertNull(DorisPipes.execMarkFor(project, "file:///stale.sql", text))
        DorisPipes.setExecMark(project, "file:///stale.sql", DorisPipes.ExecMark(5, 6, "delayed", text.hashCode()))
        settings.enabled = true
        assertNull(DorisPipes.execMarkFor(project, "file:///stale.sql", text))
    }

    fun testConfigurableApplyResetAndPersistentStateRoundTrip() {
        val configurable = DorisPipesConfigurable(project)
        try {
            val panel = configurable.createComponent()
            val checkbox = panel.components.filterIsInstance<JBCheckBox>().single()
            assertFalse(checkbox.isSelected)
            checkbox.doClick()
            assertTrue(configurable.isModified)
            assertFalse(settings.enabled)
            configurable.reset()
            assertFalse(checkbox.isSelected)
            assertFalse(configurable.isModified)
            checkbox.doClick()
            configurable.apply()
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(settings.enabled)
            assertFalse(configurable.isModified)
            val xml = serialize(settings.state, createElementIfEmpty = true)!!
            val restored = deserialize<DorisPipesSettings.Options>(xml)
            assertEquals("true", xml.getChild("option")?.getAttributeValue("value"))
            assertTrue(restored.enabled)
            assertEquals(settings.state, restored)
            settings.loadState(DorisPipesSettings.Options())
            configurable.reset()
            assertFalse(checkbox.isSelected)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testToggleReparsesExistingDirtyEditorAndKeepsActionObjects() {
        val file = myFixture.configureByText("pipes.sql", "FROM t |> WHERE x = 1 |> SELECT x;")
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val editor = myFixture.editor
        WriteCommandAction.runWriteCommandAction(project) { editor.document.insertString(0, "-- unsaved\n") }
        PsiDocumentManager.getInstance(project).commitAllDocuments() // Commit PSI, not the unsaved document to disk.
        editor.caretModel.moveToOffset(editor.document.text.indexOf("WHERE"))
        editor.selectionModel.setSelection(0, 4)
        val text = editor.document.text
        val stamp = editor.document.modificationStamp
        val caret = editor.caretModel.offset
        val actions = DorisPipesActionConfiguration.EXECUTE_IDS.map { ActionManager.getInstance().getAction(it) }
        val offTree = DebugUtil.psiToString(myFixture.file, true)
        val annotator = DorisErrorAnnotator()
        assertTrue(annotator.doAnnotate(annotator.collectInformation(myFixture.file)!!).errors.isNotEmpty())
        assertFalse(PreviewPipeSqlIntention().isAvailable(project, editor, myFixture.file))
        settings.enabled = true
        PlatformTestUtil.waitWithEventsDispatching(
            "PIPE enable must rebuild existing PSI",
            { DebugUtil.psiToString(myFixture.file, true).contains("SQL_STATEMENT") },
            10,
        )
        val onTree = DebugUtil.psiToString(myFixture.file, true)
        assertFalse("setting change must rebuild existing PSI", offTree == onTree)
        assertTrue(onTree.contains("SQL_STATEMENT"))
        assertTrue(annotator.doAnnotate(annotator.collectInformation(myFixture.file)!!).errors.isEmpty())
        assertTrue(PreviewPipeSqlIntention().isAvailable(project, editor, myFixture.file))
        assertTrue(RunPipesToStageIntention().isAvailable(project, editor, myFixture.file))
        settings.enabled = false
        PlatformTestUtil.waitWithEventsDispatching(
            "PIPE disable must restore native PSI",
            { DebugUtil.psiToString(myFixture.file, true) == offTree },
            10,
        )
        assertEquals(offTree, DebugUtil.psiToString(myFixture.file, true))
        assertFalse(PreviewPipeSqlIntention().isAvailable(project, editor, myFixture.file))
        assertEquals(text, editor.document.text)
        assertEquals(stamp, editor.document.modificationStamp)
        assertEquals(caret, editor.caretModel.offset)
        assertEquals(0, editor.selectionModel.selectionStart)
        assertEquals(4, editor.selectionModel.selectionEnd)
        assertEquals(actions, DorisPipesActionConfiguration.EXECUTE_IDS.map { ActionManager.getInstance().getAction(it) })
    }

    fun testExternalStateReloadReparsesClosedCachedFiles() {
        val original = myFixture.addFileToProject("cached.sql", "FROM t |> SELECT x;")
        val virtualFile = original.virtualFile
        setSqlDialectMapping(project, virtualFile, DorisSqlDialect.INSTANCE)
        fun tree() = DebugUtil.psiToString(PsiManager.getInstance(project).findFile(virtualFile)!!, true)
        val offTree = tree()
        ApplicationManager.getApplication().executeOnPooledThread {
            settings.loadState(DorisPipesSettings.Options(enabled = true))
        }.get()
        PlatformTestUtil.waitWithEventsDispatching("External enable must reparse cached PSI", { tree() != offTree }, 10)
        assertFalse(offTree == tree())
        settings.loadState(DorisPipesSettings.Options(enabled = false))
        PlatformTestUtil.waitWithEventsDispatching("External disable must restore cached PSI", { tree() == offTree }, 10)
        assertEquals(offTree, tree())
    }

    fun testStaleAnnotationPolicyIsNotAppliedAfterToggle() {
        val file = myFixture.configureByText("stale.sql", "FROM t |> SELECT x;")
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val annotator = DorisErrorAnnotator()
        val collected = annotator.collectInformation(myFixture.file)!!
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val stale = annotator.doAnnotate(collected)
        assertFalse(stale.pipesEnabled)
        assertTrue(stale.errors.isNotEmpty())
        val holder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(AnnotationHolder::class.java)) { _, method, _ ->
            error("stale diagnostics must not invoke ${method.name}")
        } as AnnotationHolder
        annotator.apply(myFixture.file, stale, holder)
    }

    fun testDisposedProjectSkipsDeferredRefresh() {
        val other = MockProject(null, testRootDisposable)
        DorisPipesSettings(other).enabled = true
        Disposer.dispose(other)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        assertFalse(DorisPipes.isEnabled(other))
    }

    fun testRapidTogglesUseTheFinalProjectPolicy() {
        val file = myFixture.configureByText("rapid.sql", "FROM t |> SELECT x;")
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val offTree = DebugUtil.psiToString(myFixture.file, true)
        settings.enabled = true
        settings.enabled = false
        settings.enabled = true
        PlatformTestUtil.waitWithEventsDispatching(
            "Rapid toggles must apply the final policy",
            { DebugUtil.psiToString(myFixture.file, true) != offTree },
            10,
        )
        assertTrue(DorisPipes.isEnabled(project))
        assertFalse(offTree == DebugUtil.psiToString(myFixture.file, true))
        assertEquals("FROM t |> SELECT x;", myFixture.editor.document.text)
    }

    fun testPipeStageCompletionFollowsProjectSetting() {
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE)
        try {
            myFixture.configureByText("completion.sql", "FROM t |> <caret>")
            val off = myFixture.completeBasic().orEmpty().map { it.lookupString }
            assertFalse(off.contains("EXTEND"))
            LookupManager.hideActiveLookup(project)
            settings.enabled = true
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val on = myFixture.completeBasic().orEmpty().map { it.lookupString }
            assertTrue("missing PIPE completion: $on", on.contains("EXTEND"))
        } finally {
            setSqlDialectMapping(project, null, null)
        }
    }
}
