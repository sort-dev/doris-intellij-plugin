package dev.sort.doris.catalog

import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.Casing
import com.intellij.database.util.TreePatternUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Offline assertions for the M2 default-introspection-scope patterns and the flag-ON "catalog"
 * terminology. What a live Doris must still confirm: that the platform actually adopts these
 * defaults on a fresh data source (it copies `DBIntrospector.getDefaultScope()` into an empty-scope
 * data source on first introspection) — the pattern *content* is fully assertable here.
 */
class DorisCatalogScopesTest : BasePlatformTestCase() {

    fun testMultiCatalogDefaultScopeShape() {
        val pattern = DorisCatalogScopes.multiCatalogDefaultScope()
        val dbGroup = pattern.root.getGroup(ObjectKind.DATABASE)
        assertNotNull("default scope must have a DATABASE (catalog) level", dbGroup)
        dbGroup!!

        val internal = ObjectName.plain(DorisCatalogScopes.INTERNAL_CATALOG)
        val external = ObjectName.plain("hive_archive")

        val internalNodes = dbGroup.children.orEmpty().filter { it.naming.matches(internal, Casing.EXACT) }
        assertTrue("'internal' must be matched by the scope", internalNodes.isNotEmpty())
        assertTrue(
            "'internal' must be DEEP: at least one matching node carries a SCHEMA group (all schemas)",
            internalNodes.any { node ->
                val schemas = node.getGroup(ObjectKind.SCHEMA)
                schemas != null && schemas.children.orEmpty().any { TreePatternUtils.isWildcard(it) }
            },
        )

        val externalNodes = dbGroup.children.orEmpty().filter { it.naming.matches(external, Casing.EXACT) }
        assertTrue("external catalogs must be matched (enumerated) by the scope", externalNodes.isNotEmpty())
        assertTrue(
            "external catalogs must be SHALLOW: no matching node may select any schemas",
            externalNodes.all { it.getGroup(ObjectKind.SCHEMA) == null },
        )
    }

    fun testSingleDatabaseDefaultScopeAddsCurrentDatabaseByName() {
        // Base = the platform's own single-database default (the '@' current-schema pattern).
        // Simulate it precisely: a SCHEMA group with the '@' name, as SINGLE_DB_SCOPE builds it.
        val base = com.intellij.database.util.TreePattern(
            TreePatternUtils.create(ObjectName.quoted("@"), ObjectKind.SCHEMA),
        )

        // No current database known -> the base pattern must be returned untouched (MySQL parity).
        assertSame(base, DorisCatalogScopes.singleDatabaseDefaultScope(base, null))
        assertSame(base, DorisCatalogScopes.singleDatabaseDefaultScope(base, "  "))

        // Current database known -> it must be selectable by NAME in addition to '@' (issue #5:
        // '@' resolves to nothing when no schema is flagged current).
        val scoped = DorisCatalogScopes.singleDatabaseDefaultScope(base, "acme_dwh")
        val schemaGroup = scoped.root.getGroup(ObjectKind.SCHEMA)
        assertNotNull(schemaGroup)
        val name = ObjectName.plain("acme_dwh")
        assertTrue(
            "named current database must match the default scope",
            schemaGroup!!.children.orEmpty().any { it.naming.matches(name, Casing.EXACT) },
        )
        assertTrue(
            "the '@' (current) pattern must be preserved for MySQL parity",
            schemaGroup.children.orEmpty().any { it.naming.matches(ObjectName.quoted("@"), Casing.EXACT) },
        )
        // Unrelated schemas must stay out of the default scope.
        assertFalse(
            "unrelated schemas must not be selected by default",
            schemaGroup.children.orEmpty().any { it.naming.matches(ObjectName.plain("some_other_db"), Casing.EXACT) },
        )
    }

    fun testCatalogTerminologyFlagOn() {
        val helper = DorisCatalogModelHelper()
        assertEquals("catalog", helper.getName(ObjectKind.DATABASE, false))
        assertEquals("catalogs", helper.getName(ObjectKind.DATABASE, true))
        // Other kinds keep their platform names (no blanket renaming).
        assertNull(helper.getCustomName(ObjectKind.SCHEMA, true))
        assertNull(helper.getCustomName(ObjectKind.TABLE, true))
    }
}
