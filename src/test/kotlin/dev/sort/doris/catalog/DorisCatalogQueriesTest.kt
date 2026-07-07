package dev.sort.doris.catalog

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Offline assertions on the SQL text and row shapes the experimental multi-catalog introspector
 * runs. These are the parts of Gate 1 / Milestone 1 that are verifiable **without** a live Doris:
 * that the primary query text is the *stateless, catalog-qualified* form (no `SWITCH`), that
 * catalog identifiers are backtick-quoted wherever they are embedded, that the SWITCH-based
 * fallback forms are intact, and that the jdba row structs expose fields named to bind Doris's
 * `SHOW CATALOGS` / `information_schema` column labels.
 */
class DorisCatalogQueriesTest : BasePlatformTestCase() {

    fun testCatalogListingQueryText() {
        assertEquals("SHOW CATALOGS", DorisCatalogQueries.LIST_CATALOGS.sourceText)
    }

    fun testPrimaryDatabaseListingIsStatelessCatalogQualified() {
        assertEquals(
            "SHOW DATABASES FROM `hive_archive`",
            DorisCatalogQueries.listDatabasesIn("hive_archive").sourceText,
        )
        // A backtick inside the catalog name must be doubled, not left to break the statement.
        assertEquals(
            "SHOW DATABASES FROM `we``ird`",
            DorisCatalogQueries.listDatabasesIn("we`ird").sourceText,
        )
    }

    fun testPrimaryTableAndColumnQueriesAreCatalogQualifiedWithSchemaParam() {
        val tables = DorisCatalogQueries.listTablesIn("hive_archive").sourceText
        assertTrue(tables, tables.contains("FROM `hive_archive`.information_schema.tables"))
        assertTrue("must filter by schema name", tables.contains("TABLE_SCHEMA = ?"))
        assertTrue(tables.contains("TABLE_NAME") && tables.contains("TABLE_TYPE"))
        assertFalse("primary path must be stateless (no SWITCH)", tables.contains("SWITCH"))

        val columns = DorisCatalogQueries.listColumnsIn("hive_archive").sourceText
        assertTrue(columns, columns.contains("FROM `hive_archive`.information_schema.columns"))
        assertTrue(columns.contains("TABLE_SCHEMA = ?"))
        assertTrue(columns.contains("COLUMN_NAME") && columns.contains("ORDINAL_POSITION"))
        assertFalse(columns.contains("SWITCH"))

        // Catalog identifier quoting must survive embedded backticks in the qualified form too.
        val weird = DorisCatalogQueries.listTablesIn("we`ird").sourceText
        assertTrue(weird, weird.contains("FROM `we``ird`.information_schema.tables"))
    }

    fun testFallbackQueriesForOlderDoris() {
        // The SWITCH statement (fallback only — it mutates connection session state).
        assertEquals("SWITCH `hive_archive`", DorisCatalogQueries.switchCatalog("hive_archive"))
        assertEquals("SWITCH `we``ird`", DorisCatalogQueries.switchCatalog("we`ird"))
        assertEquals("SWITCH `internal`", DorisCatalogQueries.switchCatalog("internal"))

        // The unqualified forms the fallback runs after SWITCH.
        assertEquals("SHOW DATABASES", DorisCatalogQueries.LIST_DATABASES_CURRENT.sourceText)

        val tables = DorisCatalogQueries.LIST_TABLES_CURRENT.sourceText
        assertTrue(tables, tables.contains("FROM information_schema.tables"))
        assertTrue(tables.contains("TABLE_SCHEMA = ?"))

        val columns = DorisCatalogQueries.LIST_COLUMNS_CURRENT.sourceText
        assertTrue(columns, columns.contains("FROM information_schema.columns"))
        assertTrue(columns.contains("TABLE_SCHEMA = ?"))
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
