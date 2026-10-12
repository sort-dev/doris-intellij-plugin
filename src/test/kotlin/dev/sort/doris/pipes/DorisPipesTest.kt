package dev.sort.doris.pipes

import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.parser.PipeStageCut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.brikk.house.sql.metadata.DORIS_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.shape.SqlFragment
import kotlinx.serialization.json.Json
import dev.sort.doris.sql.DorisErrorAnnotator
import dev.sort.doris.sql.DorisSyntaxError
import dev.sort.doris.sql.withoutPipeChunkErrors

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
    fun `embedded core and metadata use the latest pinned artifact and platform serialization`() {
        assertTrue(SqlFragment::class.java.getResource("SqlFragment.class").toString().contains("brikk-sql-jvmMain-0.20.0-SNAPSHOT.jar"))
        assertTrue(FunctionDef::class.java.getResource("FunctionDef.class").toString().contains("brikk-sql-metadata-jvmMain-0.20.0-SNAPSHOT.jar"))
        val function = DORIS_FUNCTION_CATALOG.functions.first()
        val encoded = Json.encodeToString(FunctionDef.serializer(), function)
        assertEquals(function, Json.decodeFromString(FunctionDef.serializer(), encoded))
    }

    @Test
    fun `embedded core provides stage scopes and exact source maps`() {
        val text = "FROM t |> EXTEND id + 1 AS n |> SELECT n"
        val scope = DorisPipesEngine.stageScopeAt(text, text.indexOf("SELECT"), "t", listOf("id"))
        assertTrue(scope.toString(), scope?.containsAll(listOf("id", "n")) == true)
        val original = "FROM t |> WHERE missing_column > 0 |> SELECT missing_column"
        val result = DorisPipesEngine.transpile(original) as PipeTranslation.Lowered
        val lines = result.dorisSql.lines()
        val line = lines.indexOfFirst { it.contains("missing_column > 0") }
        assertTrue(result.dorisSql, line >= 0)
        val col = lines[line].indexOf("missing_column")
        val mapped = DorisPipesEngine.mapServerErrorExact("(line ${line + 1}, pos $col)", result.result!!)!!
        assertTrue(original.substring(mapped.startOffset!!, mapped.endOffset!! + 1).contains("missing_column"))
    }

    @Test
    fun `stage scope cache compares structural keys instead of integer hashes`() {
        fun scope(alias: String) = DorisPipesEngine.stageScopeAt(
            "FROM t |> EXTEND 1 AS $alias |> SELECT *",
            "FROM t |> EXTEND 1 AS $alias |> SELECT *".indexOf("SELECT"),
            "t",
            listOf("id"),
        )
        assertEquals("FB".hashCode(), "Ea".hashCode())
        assertEquals(listOf("id", "FB"), scope("FB"))
        assertEquals(listOf("id", "Ea"), scope("Ea"))
        assertEquals(listOf("id", "Ea"), scope("Ea"))
        assertEquals(listOf("id", "FB"), scope("FB"))
    }

    @Test
    fun `stage scopes expose only aliases produced before the caret stage`() {
        val text = "FROM t |> EXTEND 1 AS n |> SELECT n |> LIMIT 1"
        assertEquals(listOf("id"), DorisPipesEngine.stageScopeAt(text, text.indexOf("FROM"), "t", listOf("id")))
        assertEquals(listOf("id"), DorisPipesEngine.stageScopeAt(text, text.indexOf("EXTEND"), "t", listOf("id")))
        assertEquals(listOf("id", "n"), DorisPipesEngine.stageScopeAt(text, text.indexOf("SELECT"), "t", listOf("id")))
        assertEquals(listOf("n"), DorisPipesEngine.stageScopeAt(text, text.indexOf("LIMIT"), "t", listOf("id")))
    }

    @Test
    fun `visible code excludes future aliases comments and strings`() {
        val text = "FROM t |> WHERE future.id = 1 |> AS future " +
            "|> SELECT '-- |> AS fake', \$\$JOIN u AS fake2\$\$ /* |> AS fake3 */, `|> AS fake4` " +
            "|> AS `visible_alias`"
        val beforeAlias = DorisPipes.codeBefore(text, text.indexOf("future.id"))
        assertFalse(beforeAlias.contains("AS future"))
        val afterAlias = DorisPipes.codeBefore(text, text.indexOf("SELECT"))
        assertTrue(afterAlias.contains("AS future"))
        assertFalse(afterAlias.contains("AS fake"))
        val all = DorisPipes.codeBefore(text, text.length)
        assertFalse(all.contains("AS fake"))
        assertFalse(all.contains("AS fake2"))
        assertFalse(all.contains("AS fake3"))
        assertFalse(all.contains("AS fake4"))
        assertTrue(all.contains("AS `visible_alias`"))
        for (unfinished in listOf(
            "FROM t |> SELECT ' |> AS fake",
            "FROM t /* |> AS fake",
            "FROM t /* outer /* inner */ |> AS fake",
        )) {
            assertFalse(DorisPipes.codeBefore(unfinished, unfinished.length).contains("AS fake"))
        }
    }

    @Test
    fun `bare FROM translation is restricted to the first-stage scope`() {
        assertSame(PipeTranslation.NotPipe, DorisPipesEngine.transpile("FROM t"))
        val scoped = DorisPipesEngine.transpile("FROM t", allowNamedParameters = true, allowFirstFromStage = true)
        assertTrue(scoped.toString(), scoped is PipeTranslation.Lowered)
        scoped as PipeTranslation.Lowered
        assertNull(scoped.executionError)
        assertTrue(scoped.dorisSql, scoped.dorisSql.contains("FROM t"))
        assertTrue(DorisPipesEngine.transpile("SELECT 1", allowNamedParameters = true, allowFirstFromStage = true) is PipeTranslation.Refused)
    }

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
        assertTrue("expected Ok, got $r", r is PipeTranslation.Lowered)
        val sql = (r as PipeTranslation.Lowered).dorisSql
        assertTrue(sql.contains("WITH"))
        assertTrue(sql.contains("GROUP BY"))
        assertTrue(sql.contains("LIMIT 10"))
        assertFalse("no pipe operators may survive transpile", sql.contains(DorisPipes.MARKER))
    }

    @Test
    fun `nested-only pipelines are lowered and diagnosed`() {
        val nested = "SELECT * FROM (FROM t |> WHERE x > 0) s"
        val result = DorisPipesEngine.transpile(nested)
        assertTrue("expected Ok, got $result", result is PipeTranslation.Lowered)
        assertFalse(DorisPipes.containsPipeOperator((result as PipeTranslation.Lowered).dorisSql))

        val lossy = "SELECT * FROM (FROM t |> SELECT LAST_DAY(d, YEAR) AS period_end) s"
        assertNotNull((DorisPipesEngine.transpile(lossy) as PipeTranslation.Lowered).executionError)
        val errors = DorisPipesEngine.pipeSyntaxErrors("SELECT 1;\n$lossy")
        assertTrue(errors.isNotEmpty() && errors.all { it.line == 2 })
    }

    @Test
    fun `engine token and shape failures are handled refusals`() {
        // The native chunker reads dollar-quoted strings; the engine tokenizer does not.
        for (text in listOf("FROM t |> WHERE s = \$\$a'b\$\$", "FROM t |> WHERE s = \$\$a;b\$\$")) {
            assertTrue(text, DorisPipesEngine.transpile(text) is PipeTranslation.Refused)
        }
    }

    @Test
    fun `raw passthrough and unlowered output are refused`() {
        assertTrue(DorisPipesEngine.transpile("SHOW FRONTENDS |> LIMIT 1") is PipeTranslation.Refused)
        val lowered = DorisPipesEngine.transpile("FROM t |> SELECT '|>' AS marker") as PipeTranslation.Lowered
        assertFalse(lowered.result.isRawPassthroughStatement)
        assertFalse(DorisPipes.containsPipeOperator(lowered.sql))
        assertNull(lowered.blocker)
    }

    @Test
    fun `trailing semicolon is tolerated`() {
        assertTrue(DorisPipesEngine.transpile("$pipe;") is PipeTranslation.Lowered)
    }

    @Test
    fun `plain SQL with pipe marker inside a string literal is NotPipe`() {
        val r = DorisPipesEngine.transpile("SELECT '|>' AS marker FROM t")
        assertTrue("expected NotPipe, got $r", r is PipeTranslation.NotPipe)
    }

    @Test
    fun `named parameter ranges distinguish array slices from bracket expressions`() {
        val text = "arr[1:end_idx], arr[:idx], arr[1 + :offset], arr[1 & :mask], " +
            "arr[IF(x BETWEEN :lo AND 9, 1, 2)], arr[IF(name LIKE :pattern, 1, 2)], " +
            "arr[CASE :selector WHEN 1 THEN 2 ELSE 3 END], MAP{'k':date}, " +
            "MAP{'k' : value}, MAP{'k': IF(flag, :map_value, 0)}, " +
            "CAST(x AS STRUCT<name : INT>), name LIKE:compact"
        val parameters = DorisPipes.namedParameterRanges(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(
            listOf(":idx", ":offset", ":mask", ":lo", ":pattern", ":selector", ":map_value", ":compact"),
            parameters,
        )
    }

    @Test
    fun `broken pipe program yields positioned Err`() {
        val text = "FROM t\n|> WHERE\n|> LIMIT 5"
        val r = DorisPipesEngine.transpile(text)
        assertTrue("expected Err, got $r", r is PipeTranslation.Refused)
        val err = r as PipeTranslation.Refused
        assertEquals(3, 1 + text.take(err.range!!.start).count { it == '\n' })
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
    fun `pipeSyntaxErrors reports absolute lines for broken pipe chunks only`() {
        val text = "SELECT 1;\nFROM t\n|> WHERE\n|> LIMIT 5"
        val errors = DorisPipesEngine.pipeSyntaxErrors(text)
        assertEquals(1, errors.size)
        // Engine says relative line 3 of the chunk; the chunk starts on document line 1 (after
        // the ';' on line 1), so the |> on document line 4 is the anchor.
        assertEquals(4, errors.single().line)
        val chunk = DorisPipes.chunks(text).last()
        val engineError = DorisPipesEngine.transpile(chunk) as PipeTranslation.Refused
        val before = chunk.text.take(engineError.range!!.start)
        assertEquals(before.length - (before.lastIndexOf('\n') + 1), errors.single().col)
        assertTrue(errors.single().message.startsWith("Doris Pipes:"))
    }

    @Test
    fun `valid pipe chunks produce no errors`() {
        assertTrue(DorisPipesEngine.pipeSyntaxErrors("SELECT 1;\n$pipe").isEmpty())
    }

    @Test
    fun `native errors beside same-line pipe chunks retain their exact statement`() {
        val annotator = DorisErrorAnnotator()
        val text = "SELECT FROM; FROM t |> LIMIT 1;"
        val errors = annotator.doAnnotate(DorisErrorAnnotator.Input(text, pipesEnabled = true)).errors
        assertTrue(errors.isNotEmpty())
        assertTrue(errors.none { it.message.startsWith("Doris Pipes:") })
        assertTrue(errors.all { (it.startOffsetIn(text) ?: -1) < text.indexOf(';') })

        val reverse = "FROM t |> LIMIT 1; SELECT \uD83D\uDE00 FROM;"
        val pipeError = DorisSyntaxError(1, reverse.indexOf("LIMIT"), 5, "pipe parser noise")
        val nativeOffset = reverse.indexOf("FROM", reverse.indexOf(';'))
        val nativeError = DorisSyntaxError(1, reverse.codePointCount(0, nativeOffset), 4, "native error")
        assertEquals(listOf(nativeError), withoutPipeChunkErrors(reverse, listOf(pipeError, nativeError)))
    }

    @Test
    fun `later lexical failure does not erase an earlier pipe diagnostic`() {
        val text = "FROM t |> WHERE; FROM u |> WHERE name = 'unterminated"
        val errors = DorisPipesEngine.pipeSyntaxErrors(text)
        assertEquals(2, errors.size)
        assertTrue(errors.all { it.message.startsWith("Doris Pipes:") })
        assertTrue(errors[0].message, errors[0].line == 1)
        assertTrue(errors[1].message.contains("Unterminated SQL string"))
    }

    @Test
    fun `chunkAt finds the chunk around any caret offset`() {
        val text = "SELECT 1;\nFROM t\n|> LIMIT 1;\nSELECT 2"
        val fromOffset = text.indexOf("FROM")
        val chunk = DorisPipes.chunkAt(text, fromOffset)!!
        assertTrue(chunk.text.contains(DorisPipes.MARKER))
        // The delimiter belongs to the pipe; its exclusive end belongs to the next chunk.
        assertEquals(chunk, DorisPipes.chunkAt(text, chunk.endOffset - 1))
        assertEquals(DorisPipes.chunks(text).last(), DorisPipes.chunkAt(text, chunk.endOffset))
        // Caret in the trailing statement resolves to that one instead.
        assertFalse(DorisPipes.chunkAt(text, text.indexOf("SELECT 2"))!!.text.contains(DorisPipes.MARKER))
    }

    @Test
    fun `only delimiter tokens split statements`() {
        val statements = listOf(
            "FROM t |> WHERE s = 'a;b' |> LIMIT 5;",
            "FROM t |> WHERE s = 'a'';b' |> LIMIT 5;",
            "FROM t |> WHERE s = 'a\\';b' |> LIMIT 5;",
            "FROM t |> WHERE s = 'a\\\\' |> LIMIT 5;",
            "FROM t |> WHERE s = \"a;\"\"b\" |> LIMIT 5;",
            "FROM t |> WHERE s = \"a\\\";b\" |> LIMIT 5;",
            "FROM `ta;ble` |> SELECT `co``;lumn`;",
            "FROM t |> SELECT `\$\$identifier`;",
            "FROM t |> SELECT * -- ;\n|> WHERE tenant_id = 7;",
            "FROM t |> SELECT * --comment;\n|> WHERE tenant_id = 7;",
            "FROM t |> SELECT * -- continued\\\n; still comment\n|> LIMIT 5;",
            "FROM t |> SELECT * /* ; */ |> WHERE tenant_id = 7;",
            "FROM t |> SELECT * /* outer /* ; */ ; outer */ |> LIMIT 5;",
            "FROM t |> SELECT /*+ SET_VAR(query_timeout=5) ; */ * |> LIMIT 5;",
            "CREATE FUNCTION f() RETURNS STRING AS \$\$code; more code;\$\$;",
            "CREATE JOB j ON SCHEDULE AT CURRENT_TIMESTAMP DO INSERT INTO t SELECT 'a;b';",
        )
        for (statement in statements) {
            val text = "SELECT 0;\n$statement\nSELECT 2;"
            val chunks = DorisPipes.chunks(text)
            assertEquals(statement, 3, chunks.size)
            assertEquals("\n$statement", chunks[1].text)
            assertEquals(2, chunks[1].startLine)
            assertEquals(2 + statement.count { it == '\n' }, chunks[1].endLine)
            assertEquals(null, chunks[1].boundaryError)
            for (chunk in chunks) {
                assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
            }
        }
    }

    @Test
    fun `comment semicolon never removes the filtering stage from automatic execution input`() {
        val statement = "FROM t |> SELECT * -- ;\n|> WHERE tenant_id = 7;"
        val text = "SELECT 0;\n$statement\nSELECT 2;"
        val expectedSql = (DorisPipesEngine.transpile(statement) as PipeTranslation.Lowered).dorisSql
        for (caret in text.indexOf("FROM") until text.indexOf("\nSELECT 2")) {
            val chunk = DorisPipes.chunkAt(text, caret)!!
            assertEquals("caret=$caret", "\n$statement", chunk.text)
            val result = DorisPipesEngine.transpile(chunk)
            assertTrue("caret=$caret: $result", result is PipeTranslation.Lowered)
            val sql = (result as PipeTranslation.Lowered).dorisSql
            assertEquals(expectedSql, sql)
            assertTrue(sql.contains("tenant_id = 7"))
        }
    }

    @Test
    fun `quoted semicolon preserves full translation and stage prefix`() {
        val statement = "FROM t\n|> WHERE s = 'a;b'\n|> SELECT s\n|> LIMIT 5;"
        val text = "SELECT 0;\n$statement\nSELECT 2;"
        val chunk = DorisPipes.chunkAt(text, text.indexOf("a;b"))!!
        assertEquals("\n$statement", chunk.text)
        assertEquals(
            (DorisPipesEngine.transpile(statement) as PipeTranslation.Lowered).dorisSql,
            (DorisPipesEngine.transpile(chunk) as PipeTranslation.Lowered).dorisSql,
        )
        val prefix = DorisPipesEngine.stagePrefixAt(chunk.text, chunk.text.indexOf("SELECT"))!!
        assertEquals(3, prefix.stage + 1)
        assertEquals(4, prefix.totalStages)
        assertEquals("FROM t\n|> WHERE s = 'a;b'\n|> SELECT s", prefix.text.trim())
        assertTrue(DorisPipesEngine.transpile(prefix.text) is PipeTranslation.Lowered)
        assertTrue(DorisPipesEngine.pipeSyntaxErrors(text).isEmpty())
    }

    @Test
    fun `unterminated constructs keep the unresolved tail and block automatic transpilation`() {
        for (opener in listOf("'", "\"", "`", "/*", "/*+", "/* outer /* inner */", "\$\$")) {
            val tail = "\nFROM t |> SELECT * $opener unfinished;\n|> WHERE tenant_id = 7;\nSELECT 2;"
            val text = "SELECT 0;$tail"
            val chunks = DorisPipes.chunks(text)
            assertEquals(opener, 2, chunks.size)
            val chunk = chunks[1]
            assertEquals(tail, chunk.text)
            assertEquals(text.length, chunk.endOffset)
            assertTrue(opener, chunk.boundaryError != null)
            assertTrue(DorisPipesEngine.transpile(chunk) is PipeTranslation.Refused)
            assertEquals(chunk, DorisPipes.chunkAt(text, text.indexOf("WHERE")))
            assertEquals(chunk, DorisPipes.chunkAt(text, text.length))
        }
    }

    @Test
    fun `unclosed dollar string cannot expose a later pipeline as a new statement`() {
        val text = "SELECT \$\$unfinished;\nFROM secret |> SELECT *;"
        val chunk = DorisPipes.chunkAt(text, text.indexOf("FROM"))!!
        assertEquals(text, chunk.text)
        assertTrue(chunk.boundaryError != null)
        assertTrue(DorisPipesEngine.transpile(chunk) is PipeTranslation.Refused)
    }

    @Test
    fun `caret membership is half open with an EOF exception`() {
        for (text in listOf("FROM t |> LIMIT 1;SELECT 2;", "SELECT 2;FROM t |> LIMIT 1;")) {
            val chunks = DorisPipes.chunks(text)
            val boundary = chunks[0].endOffset
            assertEquals(chunks[0], DorisPipes.chunkAt(text, boundary - 1))
            assertEquals(chunks[1], DorisPipes.chunkAt(text, boundary))
            assertEquals(chunks[1], DorisPipes.chunkAt(text, text.length))
            assertEquals(null, DorisPipes.chunkAt(text, -1))
            assertEquals(null, DorisPipes.chunkAt(text, text.length + 1))
        }
        assertTrue(DorisPipes.chunks(" \n\t").isEmpty())
        assertEquals(null, DorisPipes.chunkAt("", 0))
        val withoutSemicolon = "FROM t |> LIMIT 1"
        assertEquals(withoutSemicolon, DorisPipes.chunkAt(withoutSemicolon, withoutSemicolon.length)?.text)
        for (trailing in listOf("", " ", "\n", "\r\n  ")) {
            val statement = "FROM t |> LIMIT 1;"
            assertEquals(statement, DorisPipes.chunkAt(statement + trailing, statement.length)?.text)
        }
    }

    @Test
    fun `document offsets stay UTF16 after supplementary characters`() {
        val text = "SELECT '\uD83D\uDE00;';\r\n  FROM t\n|> WHERE s = '\uD83D\uDE00;\nvalue';SELECT 2;"
        val chunks = DorisPipes.chunks(text)
        assertEquals(3, chunks.size)
        val chunk = chunks[1]
        assertEquals(text.indexOf("\r\n"), chunk.startOffset)
        assertEquals(text.indexOf("SELECT 2"), chunk.endOffset)
        assertEquals(2, chunk.startLine)
        assertEquals(4, chunk.endLine)
        assertEquals(chunk.text, text.substring(chunk.startOffset, chunk.endOffset))
        assertEquals(chunk, DorisPipes.chunkAt(text, text.indexOf("value")))
        assertEquals(chunks[2], DorisPipes.chunkAt(text, text.indexOf("SELECT 2")))
    }

    @Test
    fun `transaction keywords and malformed syntax do not merge independent statements`() {
        val text = "BEGIN;FROM t |> SELECT * EXCEPT(x;SELECT 2;COMMIT;"
        assertEquals(
            listOf("BEGIN;", "FROM t |> SELECT * EXCEPT(x;", "SELECT 2;", "COMMIT;"),
            DorisPipes.chunks(text).map { it.text },
        )
    }

    @Test
    fun `run-to-stage cuts only top-level stages of multi-stage programs`() {
        val nested = "FROM t |> WHERE id IN (FROM u |> WHERE id > 0 |> SELECT id) |> SELECT id"
        assertSame(
            PipeStageCut.InsideNestedPipeline,
            DorisPipesEngine.runnableStageCutAt(nested, nested.indexOf("WHERE id > 0")),
        )
        val outer = DorisPipesEngine.runnableStageCutAt(nested, nested.indexOf("WHERE id IN"))
        assertEquals("FROM t |> WHERE id IN (FROM u |> WHERE id > 0 |> SELECT id)", (outer as PipeStageCut.Prefix).text.trim())
        val nestedOnly = "SELECT id FROM (FROM t |> WHERE id > 0) s"
        assertSame(PipeStageCut.Unavailable, DorisPipesEngine.runnableStageCutAt(nestedOnly, 0))
    }

    @Test
    fun `stagePrefixAt cuts a runnable prefix at the caret's stage`() {
        // Caret inside the WHERE stage -> stages 1-2 only.
        val whereOffset = pipe.indexOf("WHERE") + 2
        val prefix = DorisPipesEngine.stagePrefixAt(pipe, whereOffset)!!
        assertEquals(2, prefix.stage + 1)
        assertEquals(5, prefix.totalStages)
        assertTrue(prefix.text.endsWith("'2026-01-01'"))
        // A stage prefix is itself a valid pipe program.
        assertTrue(DorisPipesEngine.transpile(prefix.text) is PipeTranslation.Lowered)
        // Caret in the last stage -> the whole program.
        val last = DorisPipesEngine.stagePrefixAt(pipe, pipe.indexOf("LIMIT") + 1)!!
        assertEquals(5, last.stage + 1)
        assertEquals(pipe.trimEnd(), last.text.trimEnd())
    }
}
