package dev.sort.doris.pipes

import dev.brikk.house.sql.ast.Distinct
import dev.brikk.house.sql.ast.Limit
import dev.brikk.house.sql.ast.Literal
import dev.brikk.house.sql.ast.Offset
import dev.brikk.house.sql.ast.Select
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.optimizer.qualify
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import org.apache.doris.sqlparser.DorisSqlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.apache.doris.nereids.exceptions.SyntaxParseException
import org.junit.Test

/** Upgrade coverage through the plugin adapter. Native parsing checks syntax, not server semantics. */
class DorisPipesUpgradeTest {
    private val nativeParser = DorisSqlParser()

    private fun generated(text: String): DorisPipesEngine.Transpile.Ok {
        val result = DorisPipesEngine.transpile(text)
        assertTrue("Expected generated SQL for $text, got $result", result is DorisPipesEngine.Transpile.Ok)
        return (result as DorisPipesEngine.Transpile.Ok).also {
            assertNotNull("The adapter must retain diagnostics and the source map", it.result)
            assertEquals(it.dorisSql, it.result!!.sql)
        }
    }

    private fun supported(text: String, vararg expected: String): DorisPipesEngine.Transpile.Ok {
        val result = generated(text)
        assertEquals("Unexpected lossy translation: $text", emptyList<String>(), result.result!!.unsupportedMessages)
        // Use the throwing facade, not the annotator's recovery path, which can swallow exceptions.
        nativeParser.parseStatement(result.dorisSql)
        for (part in expected) assertTrue("Missing <$part> in:\n${result.dorisSql}", part in result.dorisSql)
        return result
    }

    @Test
    fun `escaped strings preserve quotes backslashes controls and literal pipe markers`() {
        // DorisTokenizerTest covers these at token level; exercise generation and the native grammar too.
        val literals = listOf(
            "'O''Reilly'" to "'O''Reilly'",
            "'O\\'Reilly'" to "'O''Reilly'",
            "\"double\\\"quoted\"" to "'double\"quoted'",
            "'C:\\\\tmp\\\\file'" to "'C:\\\\tmp\\\\file'",
            "'a\\nb\\tc'" to "'a\\nb\\tc'",
            "'a\\%b'" to "'a\\\\%b'",
            "'a\\qb'" to "'aqb'",
            "'a; |> LIMIT 999'" to "'a; |> LIMIT 999'",
        )
        for ((input, output) in literals) {
            supported(
                "FROM t |> WHERE tenant_id = 7 |> SELECT $input AS payload |> LIMIT 5",
                "$output AS payload", "tenant_id = 7", "LIMIT 5",
            )
        }
    }

    @Test
    fun `adjacent strings newly accepted by the parser retain each argument`() {
        supported(
            "FROM t |> SELECT 'ab' 'cd' AS joined |> LIMIT 2",
            "CONCAT('ab', 'cd') AS joined", "LIMIT 2",
        )
    }

    @Test
    fun `backtick qualified and reserved identifiers remain identifiers`() {
        supported(
            "FROM `internal`.`sales db`.`order` |> WHERE `tenant id` = 7 " +
                "|> SELECT `string`, `co``lumn` AS `display name` |> LIMIT 3",
            "`internal`.`sales db`.`order`", "`tenant id` = 7",
            "`string`", "`co``lumn` AS `display name`", "LIMIT 3",
        )
    }

    @Test
    fun `date parts and asymmetric function arguments keep Doris order`() {
        supported(
            "FROM t |> SELECT DATE_TRUNC(event_at, 'HOUR') AS bucket, " +
                "TIMESTAMPDIFF(HOUR, started_at, ended_at) AS elapsed, " +
                "DATE_ADD(event_at, 7) AS next_week, " +
                "DATE_SUB(event_at, INTERVAL 3 MONTH) AS prior_quarter |> LIMIT 4",
            "DATE_TRUNC(event_at, 'HOUR') AS bucket",
            "TIMESTAMPDIFF(HOUR, started_at, ended_at) AS elapsed",
            "DATE_ADD(event_at, INTERVAL 7 DAY) AS next_week",
            "DATE_SUB(event_at, INTERVAL '3' MONTH) AS prior_quarter", "LIMIT 4",
        )
    }

    @Test
    fun `date format coercion never leaks internal AST function names`() {
        val result = supported(
            "FROM t |> SELECT DATE_FORMAT(event_at, '%Y-%m-%d %H:%i:%s') AS formatted |> LIMIT 1",
            // Both releases normalize the equivalent time format to %T, without adding a cast.
            "DATE_FORMAT(event_at, '%Y-%m-%d %T') AS formatted",
        )
        assertFalse(result.dorisSql, result.dorisSql.contains("TS_OR_DS_TO_"))
    }

    @Test
    fun `lag and lead supply defaults without dropping explicit arguments`() {
        supported(
            "FROM t |> SELECT LAG(amount) OVER (ORDER BY event_at) AS previous_amount, " +
                "LEAD(amount, 2, -1) OVER (ORDER BY event_at) AS next_amount |> LIMIT 6",
            "LAG(amount, 1, NULL) OVER (ORDER BY event_at) AS previous_amount",
            "LEAD(amount, 2, -1) OVER (ORDER BY event_at) AS next_amount", "LIMIT 6",
        )
    }

    @Test
    fun `group concat uses Doris comma separator argument not MySQL SEPARATOR`() {
        val result = supported(
            "FROM t |> WHERE tenant_id = 7 " +
                "|> AGGREGATE GROUP_CONCAT(label, ';') AS labels GROUP BY category |> LIMIT 8",
            "GROUP_CONCAT(`label`, ';') AS labels", "tenant_id = 7", "GROUP BY", "category", "LIMIT 8",
        )
        assertFalse(result.dorisSql, result.dorisSql.contains("SEPARATOR"))
    }

    @Test
    fun `one argument LAST_DAY and explicit MONTH are warning free`() {
        for (call in listOf("LAST_DAY(d)", "LAST_DAY(d, MONTH)")) {
            supported("FROM t |> SELECT $call AS month_end |> LIMIT 1", "LAST_DAY(d) AS month_end")
        }
    }

    @Test
    fun `B3 unsupported LAST_DAY date part diagnostics survive the adapter`() {
        for (unit in listOf("YEAR", "QUARTER", "WEEK")) {
            val result = generated("FROM t |> SELECT LAST_DAY(d, $unit) AS period_end |> LIMIT 1")
            val warnings = result.result!!.unsupportedMessages
            assertTrue(warnings.toString(), "Date parts are not supported in LAST_DAY." in warnings)
            // Syntax acceptance is NOT evidence that dropping the requested date part is safe.
            nativeParser.parseStatement(result.dorisSql)
        }
    }

    @Test
    fun `B3 approximate count accuracy warning is retained and does not leak to the next query`() {
        val result = generated("FROM t |> AGGREGATE APPROX_COUNT_DISTINCT(user_id, 0.01) AS users")
        val warnings = result.result!!.unsupportedMessages
        assertTrue(warnings.toString(), warnings.any {
            it.contains("'accuracy'") && it.contains("ApproxDistinct") && it.contains("Doris")
        })
        nativeParser.parseStatement(result.dorisSql)
        supported("FROM t |> AGGREGATE APPROX_COUNT_DISTINCT(user_id) AS users",
            "APPROX_COUNT_DISTINCT(user_id) AS users")
    }

    @Test
    fun `Doris arrays preserve one based indexing and angle bracket casts`() {
        supported(
            "FROM t |> WHERE tenant_id = 7 |> SELECT arr[1] AS first_item, " +
                "CAST(arr AS ARRAY<INT>) AS ints, ARRAY(1, 2, 3) AS constants |> LIMIT 2",
            "arr[1] AS first_item", "CAST(arr AS ARRAY<INT>) AS ints",
            "ARRAY(1, 2, 3) AS constants", "tenant_id = 7", "LIMIT 2",
        )
    }

    @Test
    fun `new Doris storage type parsing also applies to pipe casts`() {
        supported(
            "FROM t |> SELECT CAST(n AS LARGEINT) AS wide, CAST(d AS DATEV2) AS day_value, " +
                "CAST(ts AS DATETIMEV2(3)) AS millis, CAST(n AS DECIMALV3(18, 2)) AS money |> LIMIT 3",
            "CAST(n AS LARGEINT) AS wide", "CAST(d AS DATE) AS day_value",
            "CAST(ts AS DATETIME(3)) AS millis", "CAST(n AS DECIMAL(18, 2)) AS money", "LIMIT 3",
        )
    }

    @Test
    fun `Doris lateral view remains a table function rather than a scalar explode`() {
        supported(
            "FROM t LATERAL VIEW EXPLODE(arr) exploded AS item " +
                "|> WHERE tenant_id = 7 |> SELECT item |> LIMIT 4",
            "LATERAL VIEW", "EXPLODE(arr)", "exploded AS item", "tenant_id = 7", "LIMIT 4",
        )
    }

    @Test
    fun `B2 escaped delimiters and comments cannot remove trailing filters and limit`() {
        val statement = "FROM t |> WHERE payload = 'a\\'; |> LIMIT 999' " +
            "|> SELECT * -- ; |> LIMIT 999\n|> WHERE tenant_id = 7 |> LIMIT 5;"
        val document = "SELECT 0;\n$statement\nSELECT 2;"
        val chunk = DorisPipes.chunkAt(document, document.indexOf("tenant_id"))!!
        assertEquals("\n$statement", chunk.text)
        assertEquals(3, DorisPipes.chunks(document).size)
        val result = supported(chunk.text, "tenant_id = 7", "LIMIT 5", "'a''; |> LIMIT 999'")
        val chunkResult = DorisPipesEngine.transpile(chunk)
        assertTrue(chunkResult is DorisPipesEngine.Transpile.Ok)
        assertEquals(result.dorisSql, (chunkResult as DorisPipesEngine.Transpile.Ok).dorisSql)
        assertEquals(emptyList<String>(), chunkResult.result!!.unsupportedMessages)
    }

    @Test
    fun `stage prefix ignores markers in arguments and excludes only later stages`() {
        val text = "FROM t\n|> WHERE tenant_id = 7\n" +
            "|> EXTEND CONCAT(label, '; |> SELECT fake') AS display_label\n" +
            "|> SELECT display_label\n|> LIMIT 9"
        val prefix = DorisPipesEngine.stagePrefixAt(text, text.indexOf("fake"))!!
        assertEquals(3, prefix.stage)
        assertEquals(5, prefix.totalStages)
        assertEquals(text.substringBefore("\n|> SELECT display_label"), prefix.text)
        supported(prefix.text, "tenant_id = 7", "CONCAT(`label`, '; |> SELECT fake') AS display_label")
        supported(text, "tenant_id = 7", "LIMIT 9")
        assertEquals(listOf("tenant_id", "label", "display_label"),
            DorisPipesEngine.stageScopeAt(text, text.indexOf("SELECT display_label"), "t", listOf("tenant_id", "label")))
        assertEquals(listOf("display_label"),
            DorisPipesEngine.stageScopeAt(text, text.indexOf("LIMIT 9"), "t", listOf("tenant_id", "label")))
    }

    @Test
    fun `source map identifies the filtered occurrence after escaped strings and repeated columns`() {
        val text = "FROM t\n|> EXTEND 'O''Reilly' AS label\n" +
            "|> WHERE missing_column > 0\n|> SELECT missing_column, label"
        val result = supported(text, "missing_column > 0")
        val lines = result.dorisSql.lines()
        val line = lines.indexOfFirst { "missing_column > 0" in it }
        val col = lines[line].indexOf("missing_column")
        val mapped = DorisPipesEngine.mapServerErrorExact("(line ${line + 1}, pos $col)", result.result!!)!!
        assertEquals(3, mapped.originalLine)
        assertEquals(text.indexOf("missing_column"), mapped.startOffset)
        assertEquals("missing_column", text.substring(mapped.startOffset!!, mapped.endOffset!! + 1))
        assertEquals(null, DorisPipesEngine.mapServerErrorExact("Unknown column without a position", result.result!!))
    }

    @Test
    fun `B14 remains open because warning free pipe RENAME fails the native grammar`() {
        // Characterize the pre-existing defect; this is not an accepted executable translation.
        val result = generated("FROM t |> RENAME x AS y |> WHERE y > 0 |> LIMIT 5")
        assertEquals(emptyList<String>(), result.result!!.unsupportedMessages)
        assertTrue(result.dorisSql, "y > 0" in result.dorisSql)
        assertTrue(result.dorisSql, "LIMIT 5" in result.dorisSql)
        val error = assertThrows(SyntaxParseException::class.java) { nativeParser.parseStatement(result.dorisSql) }
        assertTrue(error.message, error.message!!.contains("RENAME"))
    }

    @Test
    fun `supplementary characters must not truncate a stage prefix`() {
        // Truncating 100 to 10 still parses, but broadens the requested filter.
        for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
            for (newline in listOf(" ", "\n", "\r\n")) {
                val text = "FROM t${newline}|> EXTEND '$payload' AS label${newline}|> WHERE missing_column > 100${newline}|> LIMIT 5"
                val prefix = DorisPipesEngine.stagePrefixAt(text, text.indexOf("WHERE"))!!
                assertEquals(text.substringBefore("${newline}|> LIMIT"), prefix.text)
                assertEquals(3, prefix.stage)
                assertEquals(4, prefix.totalStages)
                supported(prefix.text, "missing_column > 100")
                supported(text, "missing_column > 100", "LIMIT 5")
                assertEquals(text, DorisPipesEngine.stagePrefixAt(text, text.indexOf("LIMIT"))!!.text)
                assertEquals(listOf("missing_column", "label"), DorisPipesEngine.stageScopeAt(
                    text, text.indexOf("WHERE"), "t", listOf("missing_column")))
            }
        }
    }

    @Test
    fun `supplementary characters must not shift exact source offsets`() {
        val text = "FROM t |> EXTEND '\uD83D\uDE00' AS label |> WHERE missing_column > 0 |> LIMIT 5"
        val result = supported(text, "missing_column > 0", "LIMIT 5")
        val lines = result.dorisSql.lines()
        val line = lines.indexOfFirst { "missing_column > 0" in it }
        val col = lines[line].indexOf("missing_column")
        val mapped = DorisPipesEngine.mapServerErrorExact("(line ${line + 1}, pos $col)", result.result!!)!!
        assertEquals(text.indexOf("missing_column"), mapped.startOffset)
        assertEquals("missing_column", text.substring(mapped.startOffset!!, mapped.endOffset!! + 1))
    }

    @Test
    fun `server code point columns map to UTF16 after supplementary characters on the same output line`() {
        for (payload in listOf("ascii", "\uD83D\uDE00", "\uD83D\uDE00\uD83D\uDE80")) {
            for (newline in listOf(" ", "\n", "\r\n")) {
                val text = "FROM t${newline}|> WHERE CONCAT('$payload', missing_column) = 'x'${newline}|> LIMIT 5"
                val result = supported(text, "missing_column", "LIMIT 5")
                val lines = result.dorisSql.lines()
                val line = lines.indexOfFirst { "missing_column" in it }
                val col = lines[line].indexOf("missing_column")
                val nativeToken = nativeParser.newLexer(result.dorisSql).allTokens.single { it.text == "missing_column" }
                val pos = nativeToken.charPositionInLine
                assertEquals(line + 1, nativeToken.line)
                assertEquals(lines[line].codePointCount(0, col), pos)
                val mapped = DorisPipesEngine.mapServerErrorExact("(line ${line + 1}, pos $pos)", result.result!!)!!
                assertEquals(text, text.indexOf("missing_column"), mapped.startOffset)
                assertEquals("missing_column", text.substring(mapped.startOffset!!, mapped.endOffset!! + 1))
                assertEquals(text.substringBefore("missing_column").count { it == '\n' } + 1, mapped.originalLine)
                assertEquals(line + 1, mapped.transpiledLine)
                assertEquals(pos, mapped.transpiledPos)
            }
        }
    }

    @Test
    fun `invalid server coordinates never wrap onto a different generated line`() {
        val result = supported("FROM t |> WHERE missing_column > 0 |> LIMIT 5").result!!
        val lines = result.sql.lines()
        val firstLineLength = lines.first().codePointCount(0, lines.first().length)
        for (message in listOf(
            "(line 0, pos 0)", "(line ${lines.size + 1}, pos 0)",
            "(line 1, pos ${firstLineLength + 1})", "(line 1, pos 2147483647)",
            "(line 999999999999, pos 0)", "(line 1, pos 999999999999)",
        )) {
            assertEquals(message, null, DorisPipesEngine.mapServerErrorExact(message, result))
        }
    }

    @Test
    fun `Doris full outer join aggregates the complete joined relation once`() {
        // Splitting the SELECT into separately aggregated UNION ALL branches changes the rows.
        val result = supported("FROM a |> FULL OUTER JOIN b ON a.id = b.id |> AGGREGATE COUNT(*) AS n",
            "FULL OUTER JOIN b", "COUNT(*) AS n")
        assertFalse(result.dorisSql, result.dorisSql.contains("UNION"))
    }

    @Test
    fun `Doris full outer join groups matched and unmatched rows together`() {
        val result = supported(
            "FROM a |> FULL OUTER JOIN b ON a.id = b.id |> AGGREGATE COUNT(*) AS n GROUP BY COALESCE(a.category, b.category) AS bucket",
            "FULL OUTER JOIN b", "COUNT(*) AS n", "COALESCE(a.category, b.category) AS bucket",
        )
        assertFalse(result.dorisSql, result.dorisSql.contains("UNION"))
    }

    @Test
    fun `Doris full outer join applies DISTINCT across the complete joined relation`() {
        val result = supported(
            "FROM a |> FULL OUTER JOIN b ON a.id = b.id |> SELECT DISTINCT COALESCE(a.category, b.category) AS category",
            "FULL OUTER JOIN b", "DISTINCT", "COALESCE(a.category, b.category) AS category",
        )
        assertFalse(result.dorisSql, result.dorisSql.contains("UNION"))
    }

    @Test
    fun `Doris full outer join preserves an OR filter before ordering and limiting`() {
        val result = supported(
            "FROM a |> FULL OUTER JOIN b ON a.id = b.id |> WHERE a.id = 1 OR b.id = 3 |> ORDER BY 1 DESC, 3 |> LIMIT 2",
            "FULL OUTER JOIN b", "a.id = 1 OR b.id = 3", "ORDER BY", "LIMIT 2",
        )
        assertFalse(result.dorisSql, result.dorisSql.contains("UNION"))
    }

    @Test
    fun `pipe DISTINCT survives ordinary projection and replaces explicit ALL`() {
        for (text in listOf(
            "FROM t |> SELECT DISTINCT category |> SELECT category |> LIMIT 2",
            "SELECT ALL category FROM t |> SELECT DISTINCT category |> LIMIT 2",
        )) {
            val result = supported(text, "DISTINCT", "category", "LIMIT 2")
            val selects = SqlFragment(result.dorisSql, "doris").ast.findAll(Select::class).toList()
            assertEquals(result.dorisSql, 1, selects.count { it.args["distinct"] is Distinct })
        }
    }

    @Test
    fun `pipe DISTINCT cannot move ahead of an earlier LIMIT stage`() {
        val text = "FROM t |> ORDER BY id |> LIMIT 3 |> SELECT DISTINCT category |> LIMIT 2"
        val prefix = DorisPipesEngine.stagePrefixAt(text, text.indexOf("LIMIT 3"))!!
        val beforeDistinct = supported(prefix.text, "LIMIT 3")
        assertFalse(beforeDistinct.dorisSql.contains("DISTINCT"))
        val result = supported(text, "DISTINCT", "LIMIT 3", "LIMIT 2")
        val selects = SqlFragment(result.dorisSql, "doris").ast.findAll(Select::class).toList()
        val limitedInput = selects.single { ((it.args["limit"] as? Limit)?.expressionArg as? Literal)?.name == "3" }
        assertEquals("The first LIMIT must apply before deduplication", null, limitedInput.args["distinct"])
        assertEquals(result.dorisSql, 1, selects.count { it.args["distinct"] is Distinct })
    }

    @Test
    fun `pipe DISTINCT cannot move ahead of an earlier OFFSET stage`() {
        val result = supported(
            "FROM t |> ORDER BY id |> LIMIT 3 OFFSET 1 |> SELECT DISTINCT category |> LIMIT 2",
            "DISTINCT", "category",
        )
        val selects = SqlFragment(result.dorisSql, "doris").ast.findAll(Select::class).toList()
        val restrictedInput = selects.single { ((it.args["offset"] as? Offset)?.expressionArg as? Literal)?.name == "1" }
        assertEquals("The OFFSET must select input rows before deduplication", null, restrictedInput.args["distinct"])
        assertEquals("3", ((restrictedInput.args["limit"] as? Limit)?.expressionArg as? Literal)?.name)
        assertEquals(result.dorisSql, 1, selects.count { it.args["distinct"] is Distinct })
    }

    @Test
    fun `pipe DISTINCT cannot erase the head DISTINCT ON row selection`() {
        supported(
            "SELECT DISTINCT ON (category) id, category FROM t ORDER BY category, id |> SELECT DISTINCT id, category",
            "ROW_NUMBER() OVER", "PARTITION BY", "DISTINCT",
        )
    }

    @Test
    fun `pipe DISTINCT ON star must not expose internal ranking columns`() {
        val text = "FROM t |> SELECT DISTINCT ON (category) *"
        val result = supported(text)
        val catalog = ShapeCatalog(mapOf("t" to Shape(listOf(
            ColumnShape("id", "INT", false), ColumnShape("category", "STRING", true),
        ))), emptyMap())
        assertEquals(listOf("id", "category"), SqlFragment(text, "doris").outputShape(catalog).names())
        assertEquals("Executable output must match the requested star projection",
            listOf("id", "category"), SqlFragment(result.dorisSql, "doris").outputShape(catalog).names())
    }

    @Test
    fun `intentional engine refusals become handled errors rather than escaped failures`() {
        for (text in listOf(
            "FROM t AS a |> FULL OUTER JOIN u AS b ON a.id = b.id |> LIMIT 2 |> SELECT a.id",
            "FROM t |> SELECT DISTINCT ON (1) *",
        )) {
            val refusal = assertThrows(UnsupportedError::class.java) {
                SqlFragment(text, "doris").toExecutable("doris", pretty = true)
            }
            val result = DorisPipesEngine.transpile(text)
            assertTrue("A safety refusal must not delegate or escape: $result", result is DorisPipesEngine.Transpile.Err)
            val error = result as DorisPipesEngine.Transpile.Err
            assertEquals(refusal.message, error.message)
            assertEquals(null, error.line)
            assertEquals(null, error.col)
        }
    }

    @Test
    fun `refused stage prefixes retain the error and annotate only their own statement`() {
        val text = "FROM t AS a |> FULL OUTER JOIN u AS b ON a.id = b.id |> LIMIT 2 |> SELECT a.id"
        val prefix = DorisPipesEngine.stagePrefixAt(text, text.indexOf("SELECT"))!!
        assertEquals(text, prefix.text)
        assertTrue(DorisPipesEngine.transpile(prefix.text) is DorisPipesEngine.Transpile.Err)
        val earlier = DorisPipesEngine.stagePrefixAt(text, text.indexOf("LIMIT"))!!
        supported(earlier.text, "FULL OUTER JOIN", "LIMIT 2")
        val document = "SELECT 0;\n$text;\nSELECT 2;"
        val chunk = DorisPipes.chunkAt(document, document.indexOf("FULL"))!!
        assertTrue(DorisPipesEngine.transpile(chunk) is DorisPipesEngine.Transpile.Err)
        val errors = DorisPipesEngine.pipeSyntaxErrors(document)
        assertEquals(1, errors.size)
        assertEquals(2, errors.single().line)
        assertTrue(errors.single().message.contains("Cannot preserve a pipe SELECT/DISTINCT input boundary"))
    }

    private fun assertLateralProjectionOrRefusal(projection: String, expectedColumns: List<String>) {
        val text = "FROM t LATERAL VIEW EXPLODE(arr) e AS item |> LIMIT 2 |> SELECT $projection"
        val result = DorisPipesEngine.transpile(text)
        if (result is DorisPipesEngine.Transpile.Err) {
            assertTrue("A safe refusal must explain why it cannot translate", result.message.isNotBlank())
            return
        }
        assertTrue(result.toString(), result is DorisPipesEngine.Transpile.Ok)
        result as DorisPipesEngine.Transpile.Ok
        assertEquals(emptyList<String>(), result.result!!.unsupportedMessages)
        nativeParser.parseStatement(result.dorisSql)
        val columns = linkedMapOf("id" to "INT", "category" to "STRING", "arr" to "ARRAY<INT>")
        val generated = SqlFragment(result.dorisSql, "doris")
        qualify(generated.ast.copy(), dialect = Dialects.forName("doris"),
            schema = mapOf("t" to columns), validateQualifyColumns = true)
        val catalog = ShapeCatalog(mapOf("t" to Shape(columns.map { (name, type) -> ColumnShape(name, type, true) })), emptyMap())
        assertEquals("A boundary must preserve the requested relation's columns",
            expectedColumns, generated.outputShape(catalog).names())
    }

    @Test
    fun `lateral column qualifiers survive an input boundary or are refused`() {
        assertLateralProjectionOrRefusal("e.item", listOf("item"))
    }

    @Test
    fun `base table star excludes lateral columns across an input boundary or is refused`() {
        assertLateralProjectionOrRefusal("t.*", listOf("id", "category", "arr"))
    }

    @Test
    fun `standalone OFFSET preserves its argument with an overflow safe Doris LIMIT`() {
        for (offset in listOf(0L, 1L, 2L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
            val result = supported("FROM t |> ORDER BY id |> OFFSET $offset", "ORDER BY", "LIMIT")
            val select = SqlFragment(result.dorisSql, "doris").ast as Select
            val emittedOffset = ((select.args["offset"] as? Offset)?.expressionArg as? Literal)?.name?.toLong() ?: 0L
            val emittedLimit = ((select.args["limit"] as Limit).expressionArg as Literal).name.toLong()
            assertEquals(offset, emittedOffset)
            assertEquals(Long.MAX_VALUE - offset, emittedLimit)
        }
    }

    @Test
    fun `unsafe standalone head OFFSET values are handled refusals`() {
        for (offset in listOf("-1", "1.5", "?", "'1'", "1 + 1", "9223372036854775808")) {
            // A head OFFSET reaches the new generator check. Malformed |> OFFSET stages still
            // fail earlier in the inherited literalLong path, tracked separately with B10.
            val result = DorisPipesEngine.transpile("SELECT id FROM t OFFSET $offset |> ORDER BY id")
            assertTrue("OFFSET $offset must not silently change its argument: $result", result is DorisPipesEngine.Transpile.Err)
            assertEquals("Doris OFFSET without LIMIT requires a non-negative signed 64-bit integer literal",
                (result as DorisPipesEngine.Transpile.Err).message)
        }
    }
}
