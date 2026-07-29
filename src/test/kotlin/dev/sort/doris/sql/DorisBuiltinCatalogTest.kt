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
}
