package dev.sort.doris.pipes

import dev.brikk.house.sql.ast.Command
import dev.brikk.house.sql.dialects.transpile
import dev.brikk.house.sql.shape.SqlFragment
import dev.sort.doris.sql.DorisFunctions
import org.apache.doris.nereids.exceptions.ParseException
import org.apache.doris.sqlparser.DorisSqlParser
import org.junit.Assert.*
import org.junit.Test

class Doris414UpgradeTest {
    private val native = DorisSqlParser()

    private fun roundTrip(sql: String, kind: String): String {
        val fragment = SqlFragment(sql, "doris")
        assertFalse(sql, fragment.ast is Command)
        assertEquals(sql, kind, fragment.ast.javaClass.simpleName)
        val generated = fragment.transpileTo("doris")
        assertFalse(sql, generated.isRawPassthroughStatement)
        assertEquals(sql, emptyList<String>(), generated.unsupportedMessages)
        native.parseStatement(sql)
        native.parseStatement(generated.sql)
        assertEquals(generated.sql, SqlFragment(generated.sql, "doris").transpileTo("doris").sql)
        return generated.sql
    }

    @Test
    fun `column defaults survive structured DML generation`() {
        roundTrip("UPDATE t SET c = DEFAULT(c)", "Update")
        roundTrip("INSERT INTO t (id, c) VALUES (1, DEFAULT(c)), (2, DEFAULT)", "Insert")
        val merge = roundTrip("MERGE INTO t USING s ON t.id = s.id " +
            "WHEN MATCHED THEN UPDATE SET c = DEFAULT(t.c) " +
            "WHEN NOT MATCHED THEN INSERT (id, c) VALUES (s.id, DEFAULT(c))", "Merge")
        assertTrue(merge, merge.contains("DEFAULT(t.c)") && merge.contains("DEFAULT(c)"))
        assertTrue(DorisFunctions.isKnown("DEFAULT"))
    }

    @Test
    fun `nested column paths and omitted constraints survive generation`() {
        for (sql in listOf(
            "ALTER TABLE t ADD COLUMN s.b INT NULL",
            "ALTER TABLE t ADD COLUMN arr.element.b INT NULL AFTER a",
            "ALTER TABLE t ADD COLUMN `s.t`.`b.c` STRING COMMENT 'new field'",
            "ALTER TABLE t MODIFY COLUMN s.b BIGINT",
            "ALTER TABLE t MODIFY COLUMN s.b COMMENT ''",
        )) assertEquals(sql, roundTrip(sql, "Alter"))
    }

    @Test
    fun `tablet compaction and compute groups use structured statements`() {
        for (kind in listOf("BASE", "CUMULATIVE", "FULL")) {
            val sql = "ADMIN COMPACT TABLET 9223372036854775807 WHERE TYPE = '$kind'"
            assertEquals(sql, roundTrip(sql, "DorisCompactTablet"))
        }
        assertEquals("SHOW COMPUTE GROUPS", roundTrip("SHOW COMPUTE GROUPS", "Show"))
    }

    @Test
    fun `timezone types and ISO conversion retain timezone and precision`() {
        for (type in listOf("TIMESTAMPTZ", "TIMESTAMPTZ(0)", "TIMESTAMPTZ(6)")) {
            val sql = "CREATE TABLE t (ts $type)"
            assertEquals(sql, roundTrip(sql, "Create"))
        }
        val generated = transpile("SELECT from_iso8601_timestamp_nanos(s)", read = "trino", write = "doris")
        assertEquals("SELECT CAST(s AS TIMESTAMPTZ(6))", generated)
        native.parseStatement(generated)
    }

    @Test
    fun `variant functions flow through completion metadata shapes and PIPE validation`() {
        for ((function, nullable) in listOf("PARSE_TO_VARIANT" to false, "TRY_PARSE_TO_VARIANT" to true)) {
            val info = DorisFunctions.INFO_BY_NAME.getValue(function)
            assertEquals(DorisFunctions.Kind.SCALAR, info.kind)
            assertEquals("VARCHAR", info.params)
            assertEquals("VARIANT", info.returnType)
            val sql = "SELECT $function('{\"k\":1}') AS v"
            roundTrip(sql, "Select")
            val column = SqlFragment(sql, "doris").outputShape().columns.single()
            assertEquals("VARIANT", column.type)
            assertEquals(nullable, column.nullable)
            assertEquals(true, SqlFragment("SELECT $function(NULL) AS v", "doris").outputShape().columns.single().nullable)
            val result = DorisPipesEngine.transpile("FROM t |> SELECT $function('{\"k\":1}') AS v")
            assertTrue(result.toString(), result is DorisPipesEngine.Transpile.Ok)
            result as DorisPipesEngine.Transpile.Ok
            assertNull(result.executionError)
            native.parseStatement(result.dorisSql)
        }
        // Schema-owned functions must not inherit builtin type/nullability claims.
        val udf = SqlFragment("SELECT custom.PARSE_TO_VARIANT('1') AS v", "doris").outputShape().columns.single()
        assertEquals("UNKNOWN", udf.type)
        assertNull(udf.nullable)
    }

    @Test
    fun `vector search is a known table function`() {
        assertEquals(DorisFunctions.Kind.TABLE, DorisFunctions.INFO_BY_NAME.getValue("VECTOR_SEARCH").kind)
        val sql = "SELECT * FROM VECTOR_SEARCH('table'='lance.db.t', 'column'='embedding', 'query_vector'='[0,0]')"
        roundTrip(sql, "Select")
        assertTrue(SqlFragment(sql, "doris").tableSlots.isEmpty())
    }

    @Test
    fun `native release grammar rejects malformed defaults and removed play command`() {
        for (sql in listOf(
            "SELECT DEFAULT()", "SELECT DEFAULT(c + 1)",
            "ALTER TABLE t ADD COLUMN s.b INT DEFAULT 7",
            "ADMIN COMPACT TABLET 12345",
            "PLAN REPLAYER PLAY '/tmp/plan.json'",
        )) assertThrows(sql, ParseException::class.java) { native.parseStatement(sql) }
    }
}
