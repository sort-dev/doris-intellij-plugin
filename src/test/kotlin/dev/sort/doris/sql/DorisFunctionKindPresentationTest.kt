package dev.sort.doris.sql

import com.intellij.codeInsight.lookup.LookupElementPresentation
import dev.sort.doris.setSqlDialectMapping
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
            setSqlDialectMapping(project, null, null)
        } finally {
            super.tearDown()
        }
    }

    /** Explicit-invoke completion of [prefix] as DorisSQL; the rendered presentation of [name]. */
    private fun presentationOf(prefix: String, name: String): LookupElementPresentation? {
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("p.sql", "SELECT $prefix")
        val elements = myFixture.completeBasic() ?: return null
        val el = elements.firstOrNull { it.lookupString.equals(name, ignoreCase = true) } ?: return null
        return LookupElementPresentation.renderElement(el)
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

}
