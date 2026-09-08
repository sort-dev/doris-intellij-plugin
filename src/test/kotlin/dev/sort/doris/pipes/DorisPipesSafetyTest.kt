package dev.sort.doris.pipes

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.CancellationException

class DorisPipesSafetyTest : BasePlatformTestCase() {
    fun testOwnershipUsesNativeTokensAndStopsAtUnterminatedConstructs() {
        for (text in listOf(
            "SELECT '|>'", "SELECT \"|>\"", "SELECT `|>`", "SELECT \$\$|>\$\$",
            "SELECT FROM -- |>\n", "SELECT /* |> */ FROM", "SELECT 1 | > 2",
            "SELECT 1 |/* comment */> 2", "SELECT 'unclosed |> fake", "SELECT \$\$unclosed; FROM t |> LIMIT 1",
        )) {
            assertFalse(text, DorisPipes.containsPipeOperator(text))
            assertSame(text, DorisPipesEngine.Transpile.NotPipe, DorisPipesEngine.transpile(text))
        }
        for (text in listOf(
            "FROM t |> LIMIT 1", "FROM t |>>", "FROM t |> SELECT '",
            "FROM t |> SELECT * /* unfinished |>", "SELECT '\uD83D\uDE00'; FROM t |> LIMIT 1",
        )) assertTrue(text, DorisPipes.containsPipeOperator(text))
        val chunks = DorisPipes.chunks("-- |>\nSELECT '|>'; FROM t |> LIMIT 1; /* trailing |> */")
        assertEquals(listOf(false, true, false), chunks.map { it.hasPipeOperator })
        assertEquals(listOf(true, true, false), chunks.map { it.hasSql })
    }

    fun testMixedSelectionsRejectWithoutDroppingNeighborsOrTrivia() {
        val pipe = "FROM t |> WHERE s = 'a; |> b' |> LIMIT 1"
        for (text in listOf("SELECT 0;$pipe", "$pipe;SELECT 2", "$pipe;$pipe")) {
            val result = DorisPipesEngine.transpile(text)
            assertTrue(text, result is DorisPipesEngine.Transpile.Err)
            assertTrue((result as DorisPipesEngine.Transpile.Err).message.contains("exactly one PIPE statement"))
        }
        val withTrivia = DorisPipesEngine.transpile("$pipe;; -- trailing |>\n/* comment ; */")
        assertTrue(withTrivia.toString(), withTrivia is DorisPipesEngine.Transpile.Ok)
        assertEquals((DorisPipesEngine.transpile(pipe) as DorisPipesEngine.Transpile.Ok).dorisSql,
            (withTrivia as DorisPipesEngine.Transpile.Ok).dorisSql)
        assertSame(DorisPipesEngine.Transpile.NotPipe, DorisPipesEngine.transpile("SELECT 1; SELECT '|>';"))
    }

    fun testWarningGatePreservesSqlMessagesAndSourceMapForPreview() {
        val text = "FROM t |> SELECT LAST_DAY(d, YEAR) AS period_end |> LIMIT 2"
        val result = DorisPipesEngine.transpile(text) as DorisPipesEngine.Transpile.Ok
        val raw = result.result!!
        val sql = result.dorisSql
        val warnings = raw.unsupportedMessages.toList()
        assertFalse(warnings.isEmpty())
        var reports = 0
        var submissions = 0
        assertTrue(dispatchPipeTranslation(result, { error ->
            reports++
            warnings.forEach { assertTrue(error.message, error.message.contains(it)) }
        }, { submissions++; true }))
        assertEquals(1, reports)
        assertEquals(0, submissions)
        assertSame(raw, result.result)
        assertSame(sql, result.dorisSql)
        assertSame(sql, raw.sourceMap!!.output)
        assertEquals(warnings, raw.unsupportedMessages)
        assertTrue(DorisPipesEngine.pipeSyntaxErrors(text).single().message.contains(warnings.single()))
        val safe = DorisPipesEngine.transpile("FROM t |> SELECT LAST_DAY(d) AS period_end |> LIMIT 2") as DorisPipesEngine.Transpile.Ok
        assertNull(safe.executionError)
        assertTrue(dispatchPipeTranslation(safe, { fail("A warning-free result must execute") }, { submissions++; true }))
        assertEquals(1, submissions)
        assertEquals(warnings, result.unsupportedMessages)
    }

    fun testAllUnsupportedDiagnosticsBlockIncludingEmptyOrMarkupLikeMessages() {
        val safe = DorisPipesEngine.transpile("FROM t |> LIMIT 1") as DorisPipesEngine.Transpile.Ok
        for (messages in listOf(listOf(""), listOf("first\nsecond", "<html>&literal</html>"))) {
            val result = safe.copy(result = safe.result!!.copy(unsupportedMessages = messages))
            assertNotNull(result.executionError)
            var errors = 0
            assertTrue(dispatchPipeTranslation(result, { error ->
                errors++
                messages.forEach { assertTrue(error.message.contains(it)) }
            }, { fail("Nonempty diagnostics cannot reach submission"); false }))
            assertEquals(1, errors)
            assertEquals(messages, result.unsupportedMessages)
        }
    }

    fun testFailedSubmissionIsHandledAndNeverDelegatesAcrossAllVariants() {
        val result = DorisPipesEngine.transpile("FROM t |> LIMIT 1")
        for (variant in 1..4) {
            var previousCalls = 0
            var reports = 0
            var attempts = 0
            val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { previousCalls++ } }
            val interceptor: (AnActionEvent, com.intellij.database.settings.DatabaseSettings.ExecOption) -> Boolean = { _, _ ->
                dispatchPipeTranslation(result, { reports++ }, { attempts++; false })
            }
            val action = if (variant == 4) DorisPipesRunSelectionAction(previous, interceptor)
                else DorisPipesRunQueryAction(variant, previous, interceptor)
            action.actionPerformed(event())
            assertEquals(1, reports)
            assertEquals(1, attempts)
            assertEquals(0, previousCalls)
        }
    }

    fun testTranslationNotificationAndSubmissionExceptionsNeverGrantRawFallback() {
        val safe = DorisPipesEngine.transpile("FROM t |> LIMIT 1")
        val lossy = DorisPipesEngine.transpile("FROM t |> SELECT LAST_DAY(d, YEAR)")
        for (failure in listOf(IllegalStateException("injected failure"), ProcessCanceledException(), CancellationException("cancelled"))) {
            for (site in listOf("translation", "notification", "submission")) {
                for (variant in 1..4) {
                    var previousCalls = 0
                    var reports = 0
                    var submissions = 0
                    val previous = object : AnAction() { override fun actionPerformed(e: AnActionEvent) { previousCalls++ } }
                    val translate: () -> DorisPipesEngine.Transpile = {
                        if (site == "translation") throw failure
                        if (site == "notification") lossy else safe
                    }
                    val interceptor: (AnActionEvent, com.intellij.database.settings.DatabaseSettings.ExecOption) -> Boolean = { _, _ ->
                        dispatchPipeTranslation(translate(), {
                            reports++
                            throw failure
                        }, {
                            submissions++
                            throw failure
                        })
                    }
                    val action = if (variant == 4) DorisPipesRunSelectionAction(previous, interceptor)
                        else DorisPipesRunQueryAction(variant, previous, interceptor)
                    try {
                        action.actionPerformed(event())
                        fail("Expected $site failure")
                    } catch (actual: Exception) {
                        assertSame(failure, actual)
                    }
                    assertEquals(0, previousCalls)
                    assertEquals(if (site == "notification") 1 else 0, reports)
                    assertEquals(if (site == "submission") 1 else 0, submissions)
                }
            }
        }
    }

    fun testOptionalRecoveryDoesNotSwallowCancellationOrFatalErrors() {
        for (failure in listOf(ProcessCanceledException(), CancellationException("cancelled"), AssertionError("fatal"), LinkageError("linkage"))) {
            var fallback = false
            try {
                runPipeCatching<Unit> { throw failure }.getOrElse { fallback = true }
                fail("Expected propagation")
            } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
            assertFalse(fallback)
        }
        val failure = IllegalArgumentException("recoverable optional feature")
        assertSame(failure, runPipeCatching<Unit> { throw failure }.exceptionOrNull())
    }

    private fun event() = AnActionEvent.createFromDataContext("DorisPipesSafetyTest", Presentation(), DataContext.EMPTY_CONTEXT)
}
