package dev.sort.doris.sql

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.doris.pipes.DorisPipeDialect
import java.util.concurrent.CancellationException

class DorisNativeValidationTest : BasePlatformTestCase() {
    fun testClasspathVerifierAcceptsCompleteScripts() {
        val text = "SELECT 1;\r\nSELECT '\uD83D\uDE00' FROM t;\nSELECT 2"
        assertSame(VerifyResult.Accepted, DorisNativeValidation.verify(text))
        assertSame(NativeVerdict.Accepted, DorisPipeDialect.validator.validate(text, null))
        val result = DorisErrorAnnotator().doAnnotate(DorisErrorAnnotator.Input(text, false))
        assertEmpty(result.errors)
        assertSame(NativeVerdict.Accepted, result.nativeVerdict)
    }

    fun testScriptErrorsKeepSortedExactTokenSpansAcrossUnicodeAndCrlf() {
        val text = "SELECT 1 FROM;\r\nSELECT '\uD83D\uDE00', FROM t;\nSELECT 2 ```"
        val expected = listOf(text.indexOf(';'), text.indexOf("FROM t"), text.lastIndexOf('`'))
        val result = DorisNativeValidation.verify(text) as VerifyResult.Rejected
        assertEquals(expected, result.errors.map { it.offset })
        val errors = DorisErrorAnnotator().doAnnotate(DorisErrorAnnotator.Input(text, false)).errors
        assertEquals(expected, errors.map { it.startOffsetIn(text) })
        assertEquals(listOf(1, 4, 1), errors.map { it.length })
        val document = myFixture.configureByText("positions.sql", text).viewProvider.document!!
        val editorText = document.text
        assertEquals(listOf(editorText.indexOf(';'), editorText.indexOf("FROM t"), editorText.lastIndexOf('`')),
            errors.map { it.toTextRange(document)!!.startOffset })
        assertEquals(listOf(";", "FROM", "`"), errors.map { it.toTextRange(document)!!.substring(editorText) })
    }

    fun testPostProcessingErrorsRemainSorted() {
        val text = "SELECT 1 FROM a-"
        val errors = DorisNativeValidation.errors(text, DorisNativeValidation.verify(text))
        val offsets = errors.map { it.startOffsetIn(text) }
        assertEquals(2, offsets.size)
        assertEquals(offsets.sortedBy { it }, offsets)
    }

    fun testCancellationAtEveryGrammarRulePropagatesTheExactException() {
        for (text in listOf("SELECT * FROM a-b", "SELECT `a` FROM `t`", "SELECT 1 FROM;\nSELECT 2")) {
            var rules = 0
            DorisNativeValidation.verify(text) { rules++ }
            for (index in 1..rules) {
                for (cancellation in listOf(ProcessCanceledException(), CancellationException("cancelled"))) {
                    var seen = 0
                    assertSame(cancellation, runCatching {
                        DorisNativeValidation.verify(text) { if (++seen == index) throw cancellation }
                    }.exceptionOrNull())
                }
            }
        }
    }

    fun testUnverifiedAndUnavailableProduceNoAnnotationsAndNeverBecomeAccepted() {
        for (result in listOf(VerifyResult.Unverified("unsupported"), VerifyResult.Unavailable("offline"))) {
            assertEmpty(DorisNativeValidation.errors("SELECT 1", result))
            assertSame(NativeVerdict.Unavailable, DorisNativeValidation.verdict(result))
        }
        assertEquals(NativeVerdict.Rejected("first\nsecond"), DorisNativeValidation.verdict(
            VerifyResult.Rejected(listOf(VerifyError("first"), VerifyError("second")))))
        assertSame(NativeVerdict.Accepted, DorisNativeValidation.verdict(VerifyResult.Accepted))
    }
}
