package dev.sort.doris.catalog

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * M9 Part B offline assertions: the persisted-model shape sniffer that the migration listener
 * ([DorisModelMigrationListener]) uses to decide whether a Doris model file matches the current
 * flag state. Snippets mirror the on-disk ground truth (`<dataSource><database-model ...>` with
 * `<root id="1">` and `parent` id references).
 */
class DorisModelShapeTest : BasePlatformTestCase() {

    private val catalogsShaped = """
        <?xml version="1.0" encoding="UTF-8"?>
        <dataSource name="doris-ds">
          <database-model serializer="dbm" dbms="DORIS" family-id="MSSQL" format-version="4.55">
            <root id="1">
              <ServerVersion>5.7.99</ServerVersion>
            </root>
            <database id="2" parent="1" name="internal">
              <Current>1</Current>
            </database>
            <database id="3" parent="1" name="extcat"/>
            <schema id="4" parent="2" name="somedb"/>
          </database-model>
        </dataSource>
    """.trimIndent()

    private val flatShaped = """
        <?xml version="1.0" encoding="UTF-8"?>
        <dataSource name="doris-ds">
          <database-model serializer="dbm" dbms="DORIS" family-id="MYSQL" format-version="4.55">
            <root id="1">
              <ServerVersion>5.7.99</ServerVersion>
            </root>
            <schema id="2" parent="1" name="somedb">
              <Current>1</Current>
            </schema>
            <table id="3" parent="2" name="sometable"/>
          </database-model>
        </dataSource>
    """.trimIndent()

    fun testCatalogsShape() {
        assertEquals(DorisModelShape.Shape.CATALOGS, DorisModelShape.sniff(catalogsShaped))
    }

    fun testFlatShape() {
        assertEquals(DorisModelShape.Shape.FLAT, DorisModelShape.sniff(flatShaped))
    }

    fun testGarbageAndMissingAreLeftAlone() {
        assertNull(DorisModelShape.sniff(null))                       // missing file
        assertNull(DorisModelShape.sniff(""))
        assertNull(DorisModelShape.sniff("   "))
        assertNull(DorisModelShape.sniff("not xml at all"))
        assertNull(DorisModelShape.sniff("<dataSource><database-model/></dataSource>")) // no nodes
        // A schema whose parent is NOT the root must not be mistaken for the flat shape.
        assertNull(DorisModelShape.sniff("""<schema id="4" parent="12" name="x"/>"""))
    }

    fun testFirstRootChildDecides() {
        // Deeper schema elements (parent="2") must not override the database-under-root signal.
        val s = DorisModelShape.sniff(catalogsShaped)
        assertEquals(DorisModelShape.Shape.CATALOGS, s)
    }
}
