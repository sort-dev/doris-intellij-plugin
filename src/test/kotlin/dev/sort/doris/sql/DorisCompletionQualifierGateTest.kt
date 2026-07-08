package dev.sort.doris.sql

import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Dogfood 2026-07-08 P2 (0.4.0): [DorisCompletionContributor]'s function items must be gated OFF
 * after a qualifier. Typing `v.` (a table alias) used to offer Doris functions like `mid` — the
 * table has no such column, and accepting produced `v.mid()`. After a dot only members of the
 * qualified relation may be offered; bare positions keep the full Doris function list.
 */
class DorisCompletionQualifierGateTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SqlDialectMappings.getInstance(project).setMapping(null, null)
        } finally {
            super.tearDown()
        }
    }

    private fun completionsAt(sql: String): List<String> {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("c.sql", sql)
        return myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
    }

    fun testAliasQualifiedPositionOffersColumnsNotFunctions() {
        val lookups = completionsAt(
            "SELECT v.<caret> FROM (SELECT 1 AS CreateTime, 2 AS Extra) v;",
        )
        // The relation's members still come from the platform...
        assertTrue("columns of v offered, got: ${lookups.take(25)}", lookups.contains("CreateTime"))
        // ...but none of OUR function items leak in (the user-reported offender first).
        assertFalse("scalar function 'mid' must not be offered after 'v.'", lookups.contains("mid"))
        assertFalse("scalar function 'abs' must not be offered after 'v.'", lookups.contains("abs"))
        assertFalse("TVF 'iceberg_meta' must not be offered after 'v.'", lookups.contains("iceberg_meta"))
    }

    fun testTableQualifiedPositionOffersNoFunctionsEither() {
        // Qualifier that is a (unresolvable) table name rather than an alias — still qualified,
        // still no function items.
        val lookups = completionsAt("SELECT acme_db.tbl.<caret> FROM acme_db.tbl;")
        assertFalse("'mid' must not be offered after a qualified path", lookups.contains("mid"))
    }

    fun testBareSelectPositionStillOffersFunctions() {
        // A typed prefix keeps the lookup under the platform's 500-variant cap, which would
        // otherwise truncate the alphabet before 'm'.
        val lookups = completionsAt("SELECT mi<caret>")
        assertTrue("'mid' offered at bare position, got: ${lookups.take(25)}", lookups.contains("mid"))
        assertTrue("'min' offered at bare position", lookups.contains("min"))
    }
}
