package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Function completion decorates each item with a kind-specific icon + label (scalar / aggregate /
 * window / table) sourced from the brikk-sql-metadata catalog. This is PRESENTATION only — kind is
 * never used to gate which functions are offered.
 */
class DorisFunctionKindPresentationTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SqlDialectMappings.getInstance(project).setMapping(null, null)
        } finally {
            super.tearDown()
        }
    }

    /** Explicit-invoke completion of [prefix] as DorisSQL; the type-text of the [name] item. */
    private fun typeTextOf(prefix: String, name: String): String? {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("p.sql", "SELECT $prefix")
        val elements = myFixture.completeBasic() ?: return null
        val el = elements.firstOrNull { it.lookupString.equals(name, ignoreCase = true) } ?: return null
        return LookupElementPresentation.renderElement(el).typeText
    }

    fun testCatalogKnowsAggregateVsScalar() {
        // Sanity on the catalog itself: COUNT is an aggregate, ABS is a scalar.
        assertEquals(DorisFunctions.Kind.AGGREGATE, DorisFunctions.BY_NAME["COUNT"])
        assertEquals(DorisFunctions.Kind.SCALAR, DorisFunctions.BY_NAME["ABS"])
    }

    fun testScalarLabel() = assertEquals("Doris function", typeTextOf("ab", "abs"))

    fun testAggregateLabel() = assertEquals("Doris aggregate", typeTextOf("cou", "count"))

    fun testKindMapCoversNames() {
        // NAMES is derived from the kind map, so every offered name has a kind.
        assertTrue(DorisFunctions.NAMES.isNotEmpty())
        assertEquals(DorisFunctions.NAMES, DorisFunctions.BY_NAME.keys)
    }
}
