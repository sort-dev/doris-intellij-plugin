package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Function completion decorates each item from the brikk-sql-metadata catalog: a kind-specific icon
 * (scalar / aggregate / window / table) plus, MySQL-style, the signature — the argument **types** as
 * grey tail text and the return type on the right. Functions whose signature is computed dynamically
 * (all table-valued) carry no static overload, so they stay a bare name with the kind label. This is
 * PRESENTATION only — kind is never used to gate which functions are offered.
 */
class DorisFunctionKindPresentationTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SqlDialectMappings.getInstance(project).setMapping(null, null)
        } finally {
            super.tearDown()
        }
    }

    /** Explicit-invoke completion of [prefix] as DorisSQL; the rendered presentation of [name]. */
    private fun presentationOf(prefix: String, name: String): LookupElementPresentation? {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("p.sql", "SELECT $prefix")
        val elements = myFixture.completeBasic() ?: return null
        val el = elements.firstOrNull { it.lookupString.equals(name, ignoreCase = true) } ?: return null
        return LookupElementPresentation.renderElement(el)
    }

    fun testCatalogKnowsAggregateVsScalar() {
        // Sanity on the catalog itself: COUNT is an aggregate, ABS is a scalar.
        assertEquals(DorisFunctions.Kind.AGGREGATE, DorisFunctions.BY_NAME["COUNT"])
        assertEquals(DorisFunctions.Kind.SCALAR, DorisFunctions.BY_NAME["ABS"])
    }

    fun testCatalogCarriesSignatures() {
        // Arg types + return type + arity flow through from brikk-sql (representative overload).
        val abs = DorisFunctions.INFO_BY_NAME["ABS"]!!
        assertEquals("DOUBLE", abs.params)
        assertEquals("DOUBLE", abs.returnType)
        assertTrue("abs is overloaded", abs.overloadCount > 1)

        val count = DorisFunctions.INFO_BY_NAME["COUNT"]!!
        assertEquals("", count.params) // count()'s first overload takes no args
        assertEquals("BIGINT", count.returnType)

        // Table-valued functions compute signatures dynamically -> no static params/return.
        val backends = DorisFunctions.INFO_BY_NAME["BACKENDS"]!!
        assertNull(backends.params)
        assertNull(backends.returnType)
        assertEquals(DorisFunctions.Kind.TABLE, backends.kind)
    }

    fun testScalarSignatureRendered() {
        val p = presentationOf("ab", "abs") ?: error("abs not offered")
        assertEquals("DOUBLE", p.typeText) // return type on the right
        assertNotNull("expected an arg-type tail", p.tailText)
        assertTrue("tail shows arg types, got '${p.tailText}'", p.tailText!!.startsWith("(DOUBLE)"))
    }

    fun testTableFunctionKeepsKindLabel() {
        // A dynamic-signature TVF has no static overload, so it keeps the kind label and no tail.
        val p = presentationOf("backend", "backends") ?: error("backends not offered")
        assertEquals("Doris table function", p.typeText)
        assertNull(p.tailText)
    }

    fun testKindMapCoversNames() {
        // NAMES is derived from the catalog, so every offered name has an Info (and a kind).
        assertTrue(DorisFunctions.NAMES.isNotEmpty())
        assertEquals(DorisFunctions.NAMES, DorisFunctions.BY_NAME.keys)
    }
}
