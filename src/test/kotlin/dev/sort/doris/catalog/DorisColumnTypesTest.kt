package dev.sort.doris.catalog

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * M5 item 2 offline assertions: the column type-string parsing table. Doris exotics and hive-side
 * strings must produce a [com.intellij.database.types.DasType] that **preserves the type name**
 * (no blank/unknown in the UI) and must never throw; the full `COLUMN_TYPE` spec is preferred over
 * the bare `DATA_TYPE`; sizes/scales survive the round trip.
 */
class DorisColumnTypesTest : BasePlatformTestCase() {

    private fun spec(dataType: String?, columnType: String?): String {
        val das = DorisCatalogQueries.columnDasType(dataType, columnType)
        assertNotNull("type must parse: dataType=$dataType columnType=$columnType", das)
        return das!!.toDataType().specification
    }

    fun testMysqlLikeTypesKeepSizes() {
        assertEquals("varchar(65533)", spec("varchar", "varchar(65533)"))
        val decimal = spec("decimal", "decimal(27,9)")
        assertTrue(decimal, decimal.startsWith("decimal") && decimal.contains("27") && decimal.contains("9"))
        assertEquals("int", spec("int", "int"))
        assertEquals("datetime", spec("datetime", "datetime"))
    }

    fun testDorisExoticTypesPreserveNames() {
        // The whole point of the guard: these are unknown to the MySQL type system but must keep
        // their names so completion/tree show 'variant' etc. instead of 'unknown'.
        for (exotic in listOf("variant", "bitmap", "hll", "largeint", "agg_state", "json", "ipv6")) {
            val s = spec(exotic, exotic)
            assertTrue("'$exotic' must be preserved in '$s'", s.contains(exotic, ignoreCase = true))
        }
    }

    fun testGenericAndHiveishTypesDoNotCrash() {
        // Doris generics.
        assertTrue(spec("array", "array<int>").contains("array", ignoreCase = true))
        assertTrue(spec("map", "map<string,int>").contains("map", ignoreCase = true))
        // Hive-side strings an external catalog might report.
        assertTrue(spec("struct", "struct<a:int,b:string>").contains("struct", ignoreCase = true))
        assertTrue(spec("decimal", "decimal(38,18)").contains("decimal", ignoreCase = true))
    }

    fun testColumnTypePreferredOverDataType() {
        // COLUMN_TYPE carries the size; DATA_TYPE is bare — the full spec must win.
        assertEquals("varchar(255)", spec("varchar", "varchar(255)"))
        // Blank/absent COLUMN_TYPE falls back to DATA_TYPE.
        assertTrue(spec("variant", null).contains("variant", ignoreCase = true))
        assertTrue(spec("variant", "  ").contains("variant", ignoreCase = true))
    }

    fun testUnusableInputYieldsNullNotCrash() {
        assertNull(DorisCatalogQueries.columnDasType(null, null))
        assertNull(DorisCatalogQueries.columnDasType("", "   "))
    }
}
