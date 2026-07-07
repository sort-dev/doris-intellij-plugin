package dev.sort.doris.catalog

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Offline assertions on the SQL text and row shapes the experimental multi-catalog introspector
 * runs. These are the parts of Gate 1 / Milestone 1 that are verifiable **without** a live Doris:
 * that the query text is exactly what a Doris FE understands, that catalog identifiers are
 * backtick-quoted in `SWITCH`, and that the jdba row structs expose fields named to bind Doris's
 * `SHOW CATALOGS` / `information_schema` column labels.
 */
class DorisCatalogQueriesTest : BasePlatformTestCase() {

    fun testCatalogAndDatabaseQueryText() {
        assertEquals("SHOW CATALOGS", DorisCatalogQueries.LIST_CATALOGS.sourceText)
        assertEquals("SHOW DATABASES", DorisCatalogQueries.LIST_DATABASES.sourceText)
    }

    fun testTableAndColumnQueriesTargetInformationSchemaWithSchemaParam() {
        val tables = DorisCatalogQueries.LIST_TABLES.sourceText
        assertTrue(tables, tables.contains("information_schema.tables"))
        assertTrue("must filter by the switched-catalog schema name", tables.contains("TABLE_SCHEMA = ?"))
        assertTrue(tables.contains("TABLE_NAME") && tables.contains("TABLE_TYPE"))

        val columns = DorisCatalogQueries.LIST_COLUMNS.sourceText
        assertTrue(columns, columns.contains("information_schema.columns"))
        assertTrue(columns.contains("TABLE_SCHEMA = ?"))
        assertTrue(columns.contains("COLUMN_NAME") && columns.contains("ORDINAL_POSITION"))
    }

    fun testSwitchCatalogBacktickQuotesIdentifier() {
        assertEquals("SWITCH `hive_archive`", DorisCatalogQueries.switchCatalog("hive_archive"))
        // A backtick inside the catalog name must be doubled, not left to break the statement.
        assertEquals("SWITCH `we``ird`", DorisCatalogQueries.switchCatalog("we`ird"))
        assertEquals("SWITCH `internal`", DorisCatalogQueries.switchCatalog("internal"))
    }

    fun testViewTypeClassification() {
        assertTrue(DorisCatalogQueries.isViewType("VIEW"))
        assertTrue(DorisCatalogQueries.isViewType("view"))
        assertFalse(DorisCatalogQueries.isViewType("BASE TABLE"))
        assertFalse(DorisCatalogQueries.isViewType(null))
    }

    fun testRowStructFieldNamesMatchDorisColumnLabels() {
        // jdba structOf binds by field name -> result column label; these must match Doris output.
        val catalogFields = DorisCatalogQueries.CatalogRow::class.java.fields.map { it.name }.toSet()
        assertTrue("SHOW CATALOGS exposes CatalogName/CatalogId", catalogFields.containsAll(setOf("CatalogName", "CatalogId")))

        val tableFields = DorisCatalogQueries.TableRow::class.java.fields.map { it.name }.toSet()
        assertTrue(tableFields.containsAll(setOf("TABLE_NAME", "TABLE_TYPE")))

        val columnFields = DorisCatalogQueries.ColumnRow::class.java.fields.map { it.name }.toSet()
        assertTrue(columnFields.containsAll(setOf("TABLE_NAME", "COLUMN_NAME", "DATA_TYPE", "ORDINAL_POSITION")))
    }
}
