package dev.sort.doris.pipes

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.lower
import java.util.concurrent.CancellationException

class DorisPipesSafetyTest : BasePlatformTestCase() {
    fun testOwnershipUsesNativeTokensAndStopsAtUnterminatedConstructs() {
        for (text in listOf(
            "SELECT '|>'", "SELECT \"|>\"", "SELECT `|>`", "SELECT \$\$|>\$\$",
            "SELECT FROM -- |>\n", "SELECT /* |> */ FROM", "SELECT 1 | > 2",
            "SELECT 1 |/* comment */> 2", "SELECT 'unclosed |> fake", "SELECT \$\$unclosed; FROM t |> LIMIT 1",
        )) {
            assertFalse(text, DorisPipes.containsPipeOperator(text))
            val result = DorisPipeDialect.lower(text)
            if (DorisPipes.chunks(text).any { it.boundaryError != null }) assertTrue(text, result is PipeTranslation.Refused)
            else assertSame(text, PipeTranslation.NotPipe, result)
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
            val result = DorisPipeDialect.lower(text)
            assertTrue(text, result is PipeTranslation.Refused)
            assertTrue((result as PipeTranslation.Refused).message.contains("exactly one statement"))
        }
        val withTrivia = DorisPipeDialect.lower("$pipe;; -- trailing |>\n/* comment ; */")
        assertTrue(withTrivia.toString(), withTrivia is PipeTranslation.Lowered)
        assertEquals((DorisPipeDialect.lower(pipe) as PipeTranslation.Lowered).sql,
            (withTrivia as PipeTranslation.Lowered).sql)
        assertSame(PipeTranslation.NotPipe, DorisPipeDialect.lower("SELECT 1; SELECT '|>';"))
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

}
