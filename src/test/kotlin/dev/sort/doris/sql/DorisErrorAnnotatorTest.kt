package dev.sort.doris.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

class DorisErrorAnnotatorTest : BasePlatformTestCase() {
    fun testFileProfileKeepsOptionalFeaturesOff() {
        assertEquals(dev.brikk.house.sql.intellij.diagnostics.VerificationUnit.File, DorisNativeErrorProfile.unit)
        assertFalse(DorisNativeErrorProfile.localParser)
        assertFalse(DorisNativeErrorProfile.reportsPsiErrors)
        assertEmpty(DorisNativeErrorProfile.injectionHosts)
        assertEquals(kotlin.time.Duration.ZERO, DorisNativeErrorProfile.debounce)
        assertNull(DorisNativeErrorProfile.sizeCap)
        assertNull(DorisNativeErrorProfile.budget)
    }

    fun testOnlyBrikkBindsAreMaskedAtEqualLength() {
        val text = "SELECT :tenant, :second"
        val masked = DorisNativeErrorProfile.verificationText(text, brikk = true)
        assertEquals(text.length, masked.length)
        assertEquals("SELECT ?      , ?      ", masked)
        assertEquals(text, DorisNativeErrorProfile.verificationText(text, brikk = false))
        assertTrue(DorisNativeErrorProfile.verifyScript(text, pipes = false, brikk = false) is dev.brikk.house.sql.verify.VerifyResult.Rejected)
        assertSame(dev.brikk.house.sql.verify.VerifyResult.Accepted,
            DorisNativeErrorProfile.verifyScript(text, pipes = false, brikk = true))
    }

    fun testServerMarkInvalidatesThePassWithoutEditingTheDocument() {
        val original = myFixture.configureByText("server.sql", "FROM t |> SELECT id")
        setSqlDialectMapping(project, original.virtualFile, DorisSqlDialect.INSTANCE)
        project.getService(dev.sort.doris.pipes.DorisPipesSettings::class.java).enabled = true
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        val file = myFixture.file
        val revision = DorisNativeErrorProfile.modificationCount(file)
        assertEmpty(dorisAnnotations(file))
        val offset = file.text.indexOf("id")
        dev.sort.doris.pipes.DorisPipes.setExecMark(project, file.virtualFile.url,
            dev.sort.doris.pipes.DorisPipes.ExecMark(offset, offset + 2, "Unable to resolve column id", file.text.hashCode()))
        assertTrue(DorisNativeErrorProfile.modificationCount(file) > revision)
        val marked = dorisAnnotations(file)
        assertEquals(listOf("Doris (server): Unable to resolve column id"), marked.map { it.message })
        assertEquals(offset, marked.single().startOffset)
        dev.sort.doris.pipes.DorisPipes.clearExecMark(project, file.virtualFile.url)
        assertEmpty(dorisAnnotations(file))
    }

    fun testValidationRunsWhenTheFileAlreadyHasErrors() {
        val valid = "SELECT 1;\n"
        val sql = valid + "SELECT * FROM t WHERE;"
        val file = myFixture.configureByText("validation.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val annotator = DorisErrorAnnotator()

        val input = checkNotNull(annotator.collectInformation(myFixture.file, myFixture.editor, hasErrors = true))

        assertNotNull(annotator.doAnnotate(input))
        val offsets = dorisAnnotations(myFixture.file).map { it.startOffset }
        assertTrue(offsets.isNotEmpty())
        assertTrue(offsets.all { it != null && it >= valid.length })
    }
}
