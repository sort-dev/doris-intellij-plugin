package dev.sort.doris.pipes

import dev.brikk.house.sql.ast.AggregateKeyProperty
import dev.brikk.house.sql.ast.AggregateTypeColumnConstraint
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.DType
import dev.brikk.house.sql.ast.DataType
import dev.brikk.house.sql.ast.DorisIndexParameters
import dev.brikk.house.sql.ast.DorisVariantField
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.PartitionByRangeProperty
import dev.brikk.house.sql.ast.PartitionRange
import dev.brikk.house.sql.shape.SqlFragment
import org.apache.doris.sqlparser.DorisSqlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Brikk 0.10.0 structured DDL, not PIPE syntax or opaque Command passthrough. */
class DorisEmbeddedDdlUpgradeTest {
    private val nativeParser = DorisSqlParser()

    private fun supported(input: String, expected: String = input): Expression {
        val source = SqlFragment(input, "doris")
        for (fragment in listOf(source, SqlFragment(expected, "doris"))) {
            assertTrue("Expected structured Create: ${fragment.sql}", fragment.ast is Create)
            assertFalse(fragment.sql, fragment.isRawPassthroughStatement)
            val result = fragment.transpileTo("doris")
            assertEquals(fragment.sql, emptyList<String>(), result.unsupportedMessages)
            assertFalse(fragment.sql, result.isRawPassthroughStatement)
            assertEquals("DDL arguments must survive generation and re-parsing", expected, result.sql)
            // The bundled July 2026 grammar is the gate, not upstream's newer verifier.
            // These cases must pass without recovery/skips; syntax acceptance is not server analysis.
            nativeParser.parseStatement(fragment.sql)
            nativeParser.parseStatement(result.sql)
            assertSame(fragment.sql, DorisPipesEngine.Transpile.NotPipe, DorisPipesEngine.transpile(fragment.sql))
        }
        return source.ast
    }

    @Test
    fun `aggregate key retains column aggregators and adjacent constraints`() {
        // 70a3716: DorisDialectTest.aggregateKeyTableWithColumnAggregators.
        val ast = supported(
            "CREATE TABLE t (k INT, v BIGINT SUM NULL DEFAULT '0' COMMENT 'total', " +
                "b BITMAP BITMAP_UNION, h HLL HLL_UNION, m INT MAX, " +
                "r INT REPLACE_IF_NOT_NULL, q QUANTILE_STATE QUANTILE_UNION) AGGREGATE KEY (k)",
        )
        assertEquals(listOf("k"), ast.find(AggregateKeyProperty::class)!!.expressionsArg.map { (it as Expression).name })
        assertEquals(
            listOf("SUM", "BITMAP_UNION", "HLL_UNION", "MAX", "REPLACE_IF_NOT_NULL", "QUANTILE_UNION"),
            ast.findAll(AggregateTypeColumnConstraint::class).map { it.name }.toList(),
        )
    }

    @Test
    fun `mixed partition bounds retain endpoints properties and bare MAXVALUE`() {
        // 7fe4599: DorisDialectTest.partitionDefinitionListsMixAllDorisEntryForms.
        val ast = supported(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) " +
                "(PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01') ('replication_num'='1'), " +
                "PARTITION p3 VALUES LESS THAN MAXVALUE)",
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d) " +
                "(PARTITION p1 VALUES [('2020-01-01'), ('2020-02-01')), " +
                "PARTITION p2 VALUES LESS THAN ('2020-03-01') ('replication_num'='1'), " +
                "PARTITION p3 VALUES LESS THAN (MAXVALUE))",
        )
        assertNotNull(ast.find(PartitionByRangeProperty::class))
        assertEquals(3, ast.findAll(PartitionRange::class).count())
    }

    @Test
    fun `multi column LESS THAN is not rewritten as a bracket range`() {
        // 7fe4599: partitionrangeSql distinguishes flat tuples from nested lower/upper bounds.
        val ast = supported(
            "CREATE TABLE t (d DATE, k INT) PARTITION BY RANGE (d, k) " +
                "(PARTITION p1 VALUES LESS THAN ('2020-01-01', 100), " +
                "PARTITION p2 VALUES LESS THAN ('2020-02-01', MAXVALUE))",
        )
        val bounds = ast.findAll(PartitionRange::class).toList()
        assertEquals(2, bounds.size)
        for (bound in bounds) {
            assertEquals(2, bound.expressionsArg.size)
            assertFalse("LESS THAN arguments must remain a flat tuple", bound.expressionsArg.first() is List<*>)
        }
    }

    @Test
    fun `create index keeps ordered columns using properties and comment`() {
        // 6cce620: DorisDialectTest.createAndDropIndexStatements; MySQL put USING before columns.
        val ast = supported(
            "CREATE INDEX IF NOT EXISTS idx ON db.t (s, k) USING INVERTED " +
                "PROPERTIES ('parser'='english', 'support_phrase'='true') COMMENT 'search'",
        )
        assertNotNull(ast.find(DorisIndexParameters::class))
    }

    @Test
    fun `typed variant retains field types nested array and type properties`() {
        // 7fe4599: DorisDialectTest.typedVariantWithFieldsAndProperties.
        val ast = supported(
            "CREATE TABLE t (v VARIANT<'x':LARGEINT, 'ip4':IPV4, 'arr':ARRAY<STRING>>, " +
                "w VARIANT<'a':INT, properties('variant_max_subcolumns_count'='10')>)",
        )
        assertEquals(listOf("x", "ip4", "arr", "a"), ast.findAll(DorisVariantField::class).map { it.name }.toList())
    }

    @Test
    fun `aggregate state retains function argument order types and nullability`() {
        // 7fe4599: DorisDialectTest.aggStateTypeKeepsFunctionSignatureAndArgNullability.
        val ast = supported(
            "CREATE TABLE t (k INT, v AGG_STATE<sum(INT)> GENERIC, " +
                "w AGG_STATE<max_by(INT NOT NULL, VARCHAR(10) NULL)> GENERIC) AGGREGATE KEY (k)",
        )
        val states = ast.findAll(DataType::class).filter { it.thisArg == DType.AGG_STATE }.toList()
        assertEquals(2, states.size)
        val functions = states.map { it.expressionsArg.single() as Expression }
        assertEquals(listOf("sum", "max_by"), functions.map { it.name })
        assertEquals(listOf(false, true), functions[1].expressionsArg.map { (it as Expression).args["nullable"] })
    }
}
