package dev.sort.doris.pipes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic coverage for the pipes SPIKE seam (engine calls run headless — no IDE needed). */
class DorisPipesTest {

    private val pipe = """
        FROM db1.events
        |> WHERE event_at >= '2026-01-01'
        |> AGGREGATE count(*) AS c GROUP BY user_id
        |> ORDER BY c DESC
        |> LIMIT 10
    """.trimIndent()

    @Test
    fun `flag parsing matches the catalogs-cancel convention`() {
        assertTrue(DorisPipes.isEnabledValue(null))
        assertTrue(DorisPipes.isEnabledValue("true"))
        assertTrue(DorisPipes.isEnabledValue("garbage"))
        assertFalse(DorisPipes.isEnabledValue("false"))
        assertFalse(DorisPipes.isEnabledValue("FALSE"))
    }

    @Test
    fun `valid pipe program transpiles to CTE-form Doris SQL`() {
        val r = DorisPipesEngine.transpile(pipe)
        assertTrue("expected Ok, got $r", r is DorisPipesEngine.Transpile.Ok)
        val sql = (r as DorisPipesEngine.Transpile.Ok).dorisSql
        assertTrue(sql.contains("WITH"))
        assertTrue(sql.contains("GROUP BY"))
        assertTrue(sql.contains("LIMIT 10"))
        assertFalse("no pipe operators may survive transpile", sql.contains(DorisPipes.MARKER))
    }

    @Test
    fun `trailing semicolon is tolerated`() {
        assertTrue(DorisPipesEngine.transpile("$pipe;") is DorisPipesEngine.Transpile.Ok)
    }

    @Test
    fun `plain SQL with pipe marker inside a string literal is NotPipe`() {
        val r = DorisPipesEngine.transpile("SELECT '|>' AS marker FROM t")
        assertTrue("expected NotPipe, got $r", r is DorisPipesEngine.Transpile.NotPipe)
    }

    @Test
    fun `broken pipe program yields positioned Err`() {
        val r = DorisPipesEngine.transpile("FROM t\n|> WHERE\n|> LIMIT 5")
        assertTrue("expected Err, got $r", r is DorisPipesEngine.Transpile.Err)
        val err = r as DorisPipesEngine.Transpile.Err
        assertEquals(3, err.line)
        assertTrue(err.message.isNotBlank())
    }

    @Test
    fun `chunks split on semicolons and track 1-based lines`() {
        val text = "SELECT 1;\nFROM t\n|> LIMIT 1;\nSELECT 2"
        val chunks = DorisPipes.chunks(text)
        assertEquals(3, chunks.size)
        assertEquals(1, chunks[0].startLine)
        assertTrue(chunks[1].text.contains(DorisPipes.MARKER))
        assertEquals(2, chunks[1].startLine) // first CONTENT line (leading newline skipped)
        assertEquals(3, chunks[1].endLine)
    }

    @Test
    fun `lineInsidePipeChunk isolates pipe statements from neighbours`() {
        val text = "SELECT 1;\nFROM t\n|> LIMIT 1;\nSELECT 2"
        assertFalse(DorisPipes.lineInsidePipeChunk(text, 1))
        assertTrue(DorisPipes.lineInsidePipeChunk(text, 2))
        assertTrue(DorisPipes.lineInsidePipeChunk(text, 3))
        assertFalse(DorisPipes.lineInsidePipeChunk(text, 4))
    }

    @Test
    fun `pipeSyntaxErrors reports absolute lines for broken pipe chunks only`() {
        val text = "SELECT 1;\nFROM t\n|> WHERE\n|> LIMIT 5"
        val errors = DorisPipesEngine.pipeSyntaxErrors(text)
        assertEquals(1, errors.size)
        // Engine says relative line 3 of the chunk; the chunk starts on document line 1 (after
        // the ';' on line 1), so the |> on document line 4 is the anchor.
        assertEquals(4, errors.single().line)
        assertTrue(errors.single().message.startsWith("Doris Pipes:"))
    }

    @Test
    fun `valid pipe chunks produce no errors`() {
        assertTrue(DorisPipesEngine.pipeSyntaxErrors("SELECT 1;\n$pipe").isEmpty())
    }

    @Test
    fun `chunkAt finds the chunk around any caret offset`() {
        val text = "SELECT 1;\nFROM t\n|> LIMIT 1;\nSELECT 2"
        val fromOffset = text.indexOf("FROM")
        val chunk = DorisPipes.chunkAt(text, fromOffset)!!
        assertTrue(chunk.text.contains(DorisPipes.MARKER))
        // Caret at the very end of the pipe chunk (right after ';') still resolves to it.
        assertEquals(chunk, DorisPipes.chunkAt(text, chunk.endOffset))
        // Caret in the trailing statement resolves to that one instead.
        assertFalse(DorisPipes.chunkAt(text, text.indexOf("SELECT 2"))!!.text.contains(DorisPipes.MARKER))
    }

    @Test
    fun `mapServerError maps a transpiled-position token back to the pipe line`() {
        val original = "FROM db1.events\n|> WHERE event_atx >= '2026-05-01'\n|> LIMIT 10"
        val transpiled = "SELECT\n  *\nFROM db1.events\nWHERE\n  event_atx >= '2026-05-01'\nLIMIT 10"
        val message = "errCode = 2, detailMessage = Unknown column 'event_atx' in 'table list' in FILTER clause(line 5, pos 2)"
        val mapped = DorisPipes.mapServerError(message, transpiled, original)!!
        assertEquals("event_atx", mapped.token)
        assertEquals(2, mapped.originalLine)
        assertEquals(5, mapped.transpiledLine)
    }

    @Test
    fun `mapServerError returns null without a position marker`() {
        assertEquals(null, DorisPipes.mapServerError("some other failure", "SELECT 1", "FROM t |> LIMIT 1"))
    }

    @Test
    fun `stagePrefixAt cuts a runnable prefix at the caret's stage`() {
        // Caret inside the WHERE stage -> stages 1-2 only.
        val whereOffset = pipe.indexOf("WHERE") + 2
        val prefix = DorisPipesEngine.stagePrefixAt(pipe, whereOffset)!!
        assertEquals(2, prefix.stage)
        assertEquals(5, prefix.totalStages)
        assertTrue(prefix.text.endsWith("'2026-01-01'"))
        // A stage prefix is itself a valid pipe program.
        assertTrue(DorisPipesEngine.transpile(prefix.text) is DorisPipesEngine.Transpile.Ok)
        // Caret in the last stage -> the whole program.
        val last = DorisPipesEngine.stagePrefixAt(pipe, pipe.indexOf("LIMIT") + 1)!!
        assertEquals(5, last.stage)
        assertEquals(pipe.trimEnd(), last.text.trimEnd())
    }
}
