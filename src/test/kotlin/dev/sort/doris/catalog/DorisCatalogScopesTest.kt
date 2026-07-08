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

    fun testDefaultScopeSerializesNonEmptyAndNamesInternal() {
        // M7: the fresh-data-source default scope must be a real, non-empty pattern (the seam that
        // stops the empty tree). Its serialized form — logged by DorisIntrospector.getDefaultScope
        // for runtime diagnosis — must name `internal`.
        val pattern = DorisCatalogScopes.multiCatalogDefaultScope()
        assertTrue("default scope must be non-empty", pattern.isNotEmpty)
        val serialized = TreePatternUtils.serialize(pattern)
        assertTrue("serialized default scope must be non-blank: '$serialized'", serialized.isNotBlank())
        assertTrue(
            "serialized default scope must reference '${DorisCatalogScopes.INTERNAL_CATALOG}': '$serialized'",
            serialized.contains(DorisCatalogScopes.INTERNAL_CATALOG),
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

    fun testAllCatalogsImportPatternShape() {
        // M3: the SQL-editor import pattern that lets qualified reference heads resolve to catalogs.
        val dsName = ObjectName.plain("doris-ds")
        val pattern = DorisCatalogScopes.allCatalogsImportPattern(arrayOf(dsName))

        // Root group: data sources, containing our data source by name.
        val dsGroup = pattern.root.getGroup(com.intellij.sql.dialects.SqlImportUtil.DATA_SOURCE)
        assertNotNull("import pattern must be rooted at the data-source level", dsGroup)
        val dsNodes = dsGroup!!.children.orEmpty().filter { it.naming.matches(dsName, Casing.EXACT) }
        assertEquals("exactly one node for the data source", 1, dsNodes.size)

        // Under the data source: a DATABASE (catalog) group whose node matches ANY catalog name...
        val catalogGroup = dsNodes[0].getGroup(ObjectKind.DATABASE)
        assertNotNull("catalog (DATABASE) level must be importable", catalogGroup)
        val internal = ObjectName.plain(DorisCatalogScopes.INTERNAL_CATALOG)
        val external = ObjectName.plain("extcat")
        assertTrue(catalogGroup!!.children.orEmpty().any { it.naming.matches(internal, Casing.EXACT) })
        assertTrue(catalogGroup.children.orEmpty().any { it.naming.matches(external, Casing.EXACT) })

        // ...with NO schema children: catalogs become qualifier heads, but their contents are not
        // swept into the unqualified scope.
        assertTrue(
            "catalog import nodes must not auto-import schemas",
            catalogGroup.children.orEmpty().all { it.getGroup(ObjectKind.SCHEMA) == null },
        )
    }

    fun testMetadataDatabaseDialectStaysMysql() {
        // M3 audit outcome: getDatabaseDialect() is the WIRE-FACING dialect (grid paging SQL,
        // extractors, console engine — 86 consumer classes). It must stay MYSQL in both flag
        // modes; qualification depth is fixed at the meta-model + SqlLanguageDialect-imports
        // layer instead (getBaseImports).
        assertEquals(
            com.intellij.database.Dbms.MYSQL,
            dev.sort.doris.sql.DorisSqlDialect.INSTANCE.databaseDialect.dbms,
        )
    }

    // ---- M8: explicit-selection classification + expansion -----------------------------------------

    /** A scope pattern: one DATABASE group with the given nodes. */
    private fun scopeOf(vararg nodes: com.intellij.database.util.TreePatternNode) =
        com.intellij.database.util.TreePattern(
            com.intellij.database.util.TreePatternNode.Group(ObjectKind.DATABASE, arrayOf(*nodes)),
        )

    /** A positive (schemas-pane-tick-shaped) DATABASE node. */
    private fun tickedCatalog(name: String, vararg groups: com.intellij.database.util.TreePatternNode.Group) =
        com.intellij.database.util.TreePatternNode(
            com.intellij.database.util.TreePatternNode.PositiveNaming(ObjectName.plain(name)),
            arrayOf(*groups),
        )

    private fun schemaGroup(vararg schemaNames: String): com.intellij.database.util.TreePatternNode.Group {
        val nodes = schemaNames.map {
            com.intellij.database.util.TreePatternNode(
                com.intellij.database.util.TreePatternNode.PositiveNaming(ObjectName.plain(it)),
                com.intellij.database.util.TreePatternNode.NO_GROUPS,
            )
        }
        return com.intellij.database.util.TreePatternNode.Group(ObjectKind.SCHEMA, nodes.toTypedArray())
    }

    fun testCatalogTickedAloneIsExplicitDeepAndExpands() {
        // The M8 repro: the pane serializes a ticked catalog (databases never loaded) as a naked
        // positive DATABASE node. Classification must say EXPLICIT_DEEP...
        val scope = scopeOf(tickedCatalog("extcat"))
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_DEEP,
            DorisCatalogScopes.classifyCatalog(scope, "extcat"),
        )
        // ...and expansion must add the all-schemas group so its databases deep-introspect.
        val expanded = DorisCatalogScopes.expandExplicitCatalogSelections(scope)
        assertNotSame(scope, expanded)
        val node = expanded.root!!.getGroup(ObjectKind.DATABASE)!!.children.orEmpty()
            .single { it.naming.matches(ObjectName.plain("extcat"), Casing.EXACT) }
        val schemas = node.getGroup(ObjectKind.SCHEMA)
        assertNotNull("expansion must add an all-schemas group", schemas)
        assertTrue(schemas!!.children.orEmpty().any { TreePatternUtils.isWildcard(it) })
        // After expansion the classification is the already-deep one (idempotence).
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_WITH_SCHEMAS,
            DorisCatalogScopes.classifyCatalog(expanded, "extcat"),
        )
        assertSame("expansion is idempotent", expanded, DorisCatalogScopes.expandExplicitCatalogSelections(expanded))
    }

    fun testCatalogWithExplicitSchemaIsUntouched() {
        // The user's second gesture: catalog + one explicit database ticked. The pattern already
        // says which schemas — expansion must not add a wildcard next to the explicit selection.
        val scope = scopeOf(tickedCatalog("extcat", schemaGroup("goodio")))
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_WITH_SCHEMAS,
            DorisCatalogScopes.classifyCatalog(scope, "extcat"),
        )
        assertSame(scope, DorisCatalogScopes.expandExplicitCatalogSelections(scope))
    }

    fun testNothingTickedIsNotInScope() {
        val empty = com.intellij.database.util.TreePattern(
            com.intellij.database.util.TreePatternNode.Group(ObjectKind.DATABASE, arrayOf()),
        )
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.NOT_IN_SCOPE,
            DorisCatalogScopes.classifyCatalog(empty, "extcat"),
        )
        assertSame(empty, DorisCatalogScopes.expandExplicitCatalogSelections(empty))
    }

    fun testDefaultScopeIsAFixedPointOfExpansion() {
        // CRITICAL M2 preservation: the default (internal deep + externals enumerate-only via the
        // negative-with-exceptions node) must NOT be reinterpreted as an explicit selection.
        val default = DorisCatalogScopes.multiCatalogDefaultScope()
        assertSame(
            "the M2 default must be a fixed point of the M8 expansion",
            default,
            DorisCatalogScopes.expandExplicitCatalogSelections(default),
        )
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_WITH_SCHEMAS,
            DorisCatalogScopes.classifyCatalog(default, DorisCatalogScopes.INTERNAL_CATALOG),
        )
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.ENUMERATED_DEFAULT,
            DorisCatalogScopes.classifyCatalog(default, "extcat"),
        )
    }

    fun testBareAllCatalogsWildcardTickIsExplicitDeep() {
        // The pane's "*" pseudo-entry serializes as NegativeNaming with NO exception names —
        // distinct from the default's negative-with-exceptions node. Ticking it means everything.
        val allTick = com.intellij.database.util.TreePatternNode(
            com.intellij.database.util.TreePatternNode.NegativeNaming.WILDCARD,
            com.intellij.database.util.TreePatternNode.NO_GROUPS,
        )
        val scope = scopeOf(allTick)
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_DEEP,
            DorisCatalogScopes.classifyCatalog(scope, "extcat"),
        )
        val expanded = DorisCatalogScopes.expandExplicitCatalogSelections(scope)
        assertNotSame(scope, expanded)
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_WITH_SCHEMAS,
            DorisCatalogScopes.classifyCatalog(expanded, "anything"),
        )
    }

    fun testMostSpecificNodeWinsClassification() {
        // Default enumerate-only node + an explicit tick for one catalog: the explicit node must
        // decide that catalog; others stay default-classified.
        val default = DorisCatalogScopes.multiCatalogDefaultScope()
        val defaultNodes = default.root!!.getGroup(ObjectKind.DATABASE)!!
            .children.orEmpty().filterNotNull().toTypedArray()
        val scope = scopeOf(*defaultNodes, tickedCatalog("extcat", schemaGroup("goodio")))
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.EXPLICIT_WITH_SCHEMAS,
            DorisCatalogScopes.classifyCatalog(scope, "extcat"),
        )
        assertEquals(
            DorisCatalogScopes.CatalogScopeClass.ENUMERATED_DEFAULT,
            DorisCatalogScopes.classifyCatalog(scope, "othercat"),
        )
    }
}
