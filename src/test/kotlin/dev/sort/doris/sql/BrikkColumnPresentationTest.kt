package dev.sort.doris.sql

import dev.brikk.house.intellij.ColumnContract
import dev.brikk.house.intellij.ColumnNullability
import dev.brikk.house.intellij.NavigationKind
import dev.brikk.house.intellij.NavigationTarget
import dev.brikk.house.intellij.RelationShape
import dev.brikk.house.intellij.ShapeKnowledge
import dev.brikk.house.intellij.ShapeOrigin
import dev.brikk.house.intellij.ShapeResult
import dev.brikk.house.intellij.SqlFamily
import org.junit.Assert.*
import org.junit.Test

class BrikkColumnPresentationTest {
    private val BrikkColumnDisplay.typeText get() = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(lookup()).typeText
    private val BrikkColumnDisplay.tailText get() = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(lookup()).tailText.orEmpty()

    private fun contracts(type: String = "kotlin.Long") = mapOf("src" to ShapeResult.Available(RelationShape(
        ShapeKnowledge.FULL, listOf(
            ColumnContract("id", type, ColumnNullability.NON_NULL, SqlFamily.UNKNOWN),
            ColumnContract("code", "kotlin.String", ColumnNullability.NULLABLE, SqlFamily.TEXT),
        ), ShapeOrigin.DECLARED)))

    @Test fun kotlinTypesAreLabelsNotNativeSqlTypes() {
        val column = ColumnContract("amount", "java.math.BigDecimal", ColumnNullability.NULLABLE, SqlFamily.DECIMAL)
        val display = BrikkColumnDisplay.from(column, "src")
        assertEquals("BigDecimal?", display.typeText)
        assertEquals("  brikk column (src)", display.tailText)
        assertNull(column.exactSqlType)
    }

    @Test fun relationFallbackIsNotPresentedAsAnAuthoredProperty() {
        val fallback = object : NavigationTarget {
            override val kind = NavigationKind.RELATION_FALLBACK
            override fun navigate(requestFocus: Boolean) = error("Presentation must not navigate")
        }
        val display = BrikkColumnDisplay.from(ColumnContract("id", null, ColumnNullability.UNKNOWN,
            SqlFamily.UNKNOWN, fallback), "src")
        assertEquals("unknown type (nullability unknown)", display.typeText)
        assertEquals("  relation contract (src)", display.tailText)
    }

    @Test fun incompleteCurrentStageUsesTheProvenInputPrefix() {
        val sql = "FROM src() |> WHERE "
        val items = BrikkPipeColumnDisplay.at(sql, sql.length, contracts())!!
        assertEquals(setOf("id", "code"), items.keys)
        assertEquals("Long", items.getValue("id").typeText)
    }

    @Test fun sharedFormatterKeepsEqualNamesFromDifferentAliasesDistinct() {
        val left = BrikkColumnDisplay("id", "kotlin.Long", ColumnNullability.NON_NULL, "left").lookup()
        val right = BrikkColumnDisplay("id", "kotlin.Long", ColumnNullability.NON_NULL, "right").lookup()
        assertEquals(left.lookupString, right.lookupString)
        assertNotEquals(left.`object`, right.`object`)
    }

    @Test fun directProjectionKeepsOriginWhileAnExpressionDoesNot() {
        val sql = "FROM src() AS e |> SELECT code AS renamed, id + 1 AS id |> WHERE "
        val items = BrikkPipeColumnDisplay.at(sql, sql.length, contracts())!!
        assertEquals("String?", items.getValue("renamed").typeText)
        assertEquals("  brikk column (e)", items.getValue("renamed").tailText)
        assertNull(items.getValue("id").kotlinClassId)
        assertEquals("  PIPE column", items.getValue("id").tailText)
    }

    @Test fun cachedLabelsInvalidateWithOnlyContractChanges() {
        val sql = "FROM src() |> WHERE "
        assertEquals("Long", BrikkPipeColumnDisplay.at(sql, sql.length, contracts())!!.getValue("id").typeText)
        assertEquals("Int", BrikkPipeColumnDisplay.at(sql, sql.length, contracts("kotlin.Int"))!!.getValue("id").typeText)
    }

    @Test fun physicalTablesDoNotReceiveKotlinMetadataByName() {
        val sql = "FROM physical AS p |> JOIN src() AS s ON p.id = s.id |> WHERE "
        assertNull(BrikkPipeColumnDisplay.at(sql, sql.length, contracts()))
    }
}
