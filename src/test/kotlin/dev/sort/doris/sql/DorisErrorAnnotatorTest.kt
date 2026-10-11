package dev.sort.doris.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.setSqlDialectMapping

class DorisErrorAnnotatorTest : BasePlatformTestCase() {
    fun testValidationRunsWhenTheFileAlreadyHasErrors() {
        val valid = "SELECT 1;\n"
        val sql = valid + "SELECT * FROM t WHERE;"
        val file = myFixture.configureByText("validation.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE)
        val annotator = DorisErrorAnnotator()

        val input = checkNotNull(annotator.collectInformation(myFixture.file, myFixture.editor, hasErrors = true))

        val offsets = annotator.doAnnotate(input).errors.map { it.startOffsetIn(sql) }
        assertTrue(offsets.isNotEmpty())
        assertTrue(offsets.all { it != null && it >= valid.length })
    }
}
