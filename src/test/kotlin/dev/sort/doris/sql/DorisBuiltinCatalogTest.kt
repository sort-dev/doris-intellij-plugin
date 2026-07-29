package dev.sort.doris.sql

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Reconciliation of the brikk-sql baseline against the connected server's `SHOW BUILTIN FUNCTIONS`
 * name set (harvested per data source by [DorisBuiltinFunctionInterceptor]). The name-set decisions
 * are pure and unit-testable without a live connection: with no harvested data the brikk baseline is
 * kept whole; with data, brikk names the server lacks are dropped and server-only names surface.
 */
class DorisBuiltinCatalogTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            DorisBuiltinCatalog.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testNoHarvestKeepsFullBrikkBaseline() {
        // Nothing harvested for this data source → every brikk name is served, nothing extra.
        assertTrue(DorisBuiltinCatalog.isServed("ABS", "ds-unharvested"))
        assertTrue(DorisBuiltinCatalog.isServed("REGEXP_LIKE", "ds-unharvested"))
        assertTrue(DorisBuiltinCatalog.extraNames(setOf("ABS"), "ds-unharvested").isEmpty())
    }

    fun testServerSetDropsAbsentAndAddsExtra() {
        // Server reports ABS + a server-only function, but NOT REGEXP_LIKE.
        DorisBuiltinCatalog.record("ds1", setOf("ABS", "SERVER_ONLY_FN"))
        assertTrue("kept: server has ABS", DorisBuiltinCatalog.isServed("ABS", "ds1"))
        assertFalse("dropped: server lacks REGEXP_LIKE", DorisBuiltinCatalog.isServed("REGEXP_LIKE", "ds1"))
        assertEquals(
            "server-only name surfaces as an extra",
            setOf("SERVER_ONLY_FN"),
            DorisBuiltinCatalog.extraNames(setOf("ABS", "REGEXP_LIKE"), "ds1"),
        )
    }

    fun testUnknownDataSourceFallsBackToBaseline() {
        DorisBuiltinCatalog.record("ds1", setOf("ABS"))
        // A different (or null) data source has no harvested data → baseline, not ds1's set.
        assertTrue(DorisBuiltinCatalog.isServed("REGEXP_LIKE", "ds2"))
        assertTrue(DorisBuiltinCatalog.isServed("REGEXP_LIKE", null))
        assertTrue(DorisBuiltinCatalog.extraNames(setOf("ABS"), null).isEmpty())
    }

    fun testEmptyHarvestIsNotRecorded() {
        // An empty result must not "poison" the data source into dropping the whole baseline.
        DorisBuiltinCatalog.record("ds-empty", emptySet())
        assertFalse(DorisBuiltinCatalog.hasData("ds-empty"))
        assertTrue(DorisBuiltinCatalog.isServed("ABS", "ds-empty"))
    }

    // ---- platform MySQL-builtin suppression (DorisMysqlFunctionFilter's decision) ----------------

    fun testSuppressesMysqlOnlyBuiltinWhenHarvested() {
        DorisBuiltinCatalog.record("ds1", setOf("ABS", "CONCAT"))
        // regexp_like: server doesn't serve it AND Doris doesn't know it -> suppressed.
        assertTrue(
            DorisBuiltinCatalog.suppresses("REGEXP_LIKE", "ds1", known = DorisFunctions.isKnown("regexp_like")),
        )
    }

    fun testDoesNotSuppressGrammarBuiltinsOrServed() {
        DorisBuiltinCatalog.record("ds1", setOf("ABS"))
        // Grammar builtins are "known" -> never suppressed even if the server list omits them.
        assertFalse(DorisBuiltinCatalog.suppresses("CAST", "ds1", known = DorisFunctions.isKnown("cast")))
        assertFalse(DorisBuiltinCatalog.suppresses("EXTRACT", "ds1", known = DorisFunctions.isKnown("extract")))
        // A served name is kept even if not "known" to the brikk pin.
        DorisBuiltinCatalog.record("ds2", setOf("SOME_NEW_FN"))
        assertFalse(DorisBuiltinCatalog.suppresses("SOME_NEW_FN", "ds2", known = false))
    }

    fun testNeverSuppressesWithoutHarvest() {
        // Offline / unattached: no server data -> suppress nothing (platform list left as-is).
        assertFalse(DorisBuiltinCatalog.suppresses("REGEXP_LIKE", "ds-none", known = false))
        assertFalse(DorisBuiltinCatalog.suppresses("REGEXP_LIKE", null, known = false))
    }

    fun testBrikkKnowsItsOwnGrammarBuiltins() {
        // Sanity on the 0.9.0 isKnown wiring: grammar builtins known, a pure MySQL fn not.
        assertTrue(DorisFunctions.isKnown("cast"))
        assertTrue(DorisFunctions.isKnown("extract"))
        assertFalse(DorisFunctions.isKnown("regexp_like"))
    }
}
