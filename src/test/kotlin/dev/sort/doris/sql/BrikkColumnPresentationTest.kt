package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.relation.RelationColumnLabels
import dev.brikk.house.sql.scope.*
import org.junit.Assert.*
import org.junit.Test

class BrikkColumnPresentationTest {
    private val labels = RelationColumnLabels(declared = "brikk column")
    private fun facts(type: String = "Long") = RelationFacts(slots = mapOf("src" to RelationFact(listOf(
        ColumnFact("id", displayType = type, nullability = Nullability.NON_NULL, provenance = "id"),
        ColumnFact("code", displayType = "String", nullability = Nullability.NULLABLE, provenance = "code"),
    ), Knowledge.FULL)))
    private fun columns(sql: String, facts: RelationFacts = facts()) =
        RelationScope.complete(sql, "doris", facts, sql.length).items.mapNotNull { it.column }.associateBy { it.name }

    @Test fun kotlinTypesAreLabelsNotNativeSqlTypes() {
        val column = ScopeColumn("amount", "src", ColumnOrigin.DECLARED, Nullability.NULLABLE, displayType = "BigDecimal")
        assertEquals("BigDecimal", labels.label(column).type)
        assertEquals("brikk column", labels.label(column).origin)
        assertNull(column.sqlType)
    }

    @Test fun relationFallbackIsNotPresentedAsAnAuthoredProperty() {
        val column = ScopeColumn("id", "src", ColumnOrigin.RELATION_CONTRACT, Nullability.UNKNOWN)
        assertEquals("relation contract", labels.label(column).origin)
        assertEquals("src", labels.label(column).relation)
    }

    @Test fun incompleteCurrentStageUsesTheProvenInputPrefix() {
        val columns = columns("FROM src() |> WHERE ")
        assertEquals(setOf("id", "code"), columns.keys)
        assertEquals("Long", labels.label(columns.getValue("id")).type)
    }

    @Test fun equalNamesFromDifferentAliasesRemainDistinct() {
        val sql = "FROM src() AS a |> JOIN src() AS b ON a.id=b.id |> WHERE "
        val completion = RelationScope.complete(sql, "doris", facts(), sql.length)
        val ids = completion.items.mapNotNull { it.column }.filter { it.name == "id" }
        assertEquals(setOf("a", "b"), ids.map { it.relation }.toSet())
    }

    @Test fun directProjectionKeepsOriginWhileAnExpressionDoesNot() {
        val columns = columns("FROM src() AS e |> SELECT code AS renamed, id + 1 AS id |> WHERE ")
        assertEquals("String", labels.label(columns.getValue("renamed")).type)
        assertEquals("src", labels.label(columns.getValue("renamed")).relation)
        assertEquals("computed column", labels.label(columns.getValue("id")).origin)
    }

    @Test fun labelsChangeWithOnlyContractChanges() {
        val sql = "FROM src() |> WHERE "
        assertEquals("Long", labels.label(columns(sql).getValue("id")).type)
        assertEquals("Int", labels.label(columns(sql, facts("Int")).getValue("id")).type)
    }

    @Test fun physicalTablesDoNotReceiveKotlinMetadataByName() {
        val facts = facts().copy(tables = mapOf("physical" to RelationFact(listOf(
            ColumnFact("id", sqlType = "INTEGER", origin = ColumnOrigin.PHYSICAL_TABLE),
        ), Knowledge.FULL)))
        val sql = "FROM physical AS p |> JOIN src() AS s ON p.id=s.id |> WHERE "
        val completion = RelationScope.complete(sql, "doris", facts, sql.length)
        val physical = completion.items.mapNotNull { it.column }.single { it.relation == "p" }
        assertNull(physical.displayType)
        assertEquals("column", labels.label(physical).origin)
        assertEquals("INTEGER", labels.label(physical).type)
    }
}
