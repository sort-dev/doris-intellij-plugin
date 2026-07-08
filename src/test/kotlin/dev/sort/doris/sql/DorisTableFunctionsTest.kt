package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlTableType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Doris table-valued functions (RESEARCH-tvf-completion.md, Tier A/B):
 *  - static output schemas resolve columns with zero exec ([DorisTypeSystem] via the builtin
 *    overlay in [DorisSqlDialect.createTokensHelper]);
 *  - arg-conditional TVFs pick the schema variant from the property literal in the SQL text;
 *  - open-relation TVFs (s3, ...) degrade silently — no columns fabricated, no red;
 *  - property keys/enum values complete inside the parens.
 */
class DorisTableFunctionsTest : BasePlatformTestCase() {

    override fun tearDown() {
        try {
            SqlDialectMappings.getInstance(project).setMapping(null, null)
        } finally {
            super.tearDown()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Resolution (PSI-level, no fixture files needed)
    // ---------------------------------------------------------------------------------------

    private fun dorisFile(sql: String) = PsiFileFactory.getInstance(project)
        .createFileFromText("t.sql", Language.findLanguageByID("DorisSQL")!!, sql, false, true)!!

    /** Resolve the column reference with the given text in the SELECT list of [sql]. */
    private fun resolveColumn(sql: String, column: String): Boolean {
        val file = dorisFile(sql)
        val ref = PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)
            .firstOrNull { it.text == column }
            ?: error("no reference '$column' in: $sql")
        return ref.resolve() != null
    }

    private fun tableTypeColumns(sql: String): List<String> {
        val file = dorisFile(sql)
        val call = PsiTreeUtil.findChildOfType(file, SqlFunctionCallExpression::class.java)!!
        val type = call.dasType as? SqlTableType ?: return emptyList()
        return (0 until type.columnCount).map { type.getColumnName(it) }
    }

    /** THE PIN required by the seam spike: mv-variant columns resolve for tasks("type"="mv"). */
    fun testTasksMvVariantColumnsResolve() {
        val sql = "SELECT TaskId, MvName, RefreshMode FROM tasks(\"type\"=\"mv\")"
        assertTrue("TaskId must resolve", resolveColumn(sql, "TaskId"))
        assertTrue("MvName must resolve", resolveColumn(sql, "MvName"))
        assertTrue("RefreshMode must resolve", resolveColumn(sql, "RefreshMode"))
    }

    fun testTasksMvVariantExcludesInsertOnlyColumns() {
        // Label/TrackingUrl exist only in the insert variant; with an explicit mv literal they
        // must NOT resolve (schema-variant selection is real, not a union).
        val sql = "SELECT Label FROM tasks(\"type\"=\"mv\")"
        assertFalse("Label must NOT resolve for type=mv", resolveColumn(sql, "Label"))
        assertEquals(
            DorisTableFunctions.byName("tasks")!!.let {
                (it.schema as DorisTableFunctions.Schema.ByPropertyLiteral).variants["mv"]!!.map(DorisTableFunctions.Column::name)
            },
            tableTypeColumns(sql)
        )
    }

    fun testTasksInsertVariantViaSingleQuotes() {
        val sql = "SELECT Label, TrackingUrl FROM tasks('type'='insert')"
        assertTrue(resolveColumn(sql, "Label"))
        assertTrue(resolveColumn(sql, "TrackingUrl"))
        assertFalse("MvName is mv-only", resolveColumn("SELECT MvName FROM tasks('type'='insert')", "MvName"))
    }

    fun testTasksWithoutLiteralDegradesToVariantUnion() {
        val sql = "SELECT TaskId, Label, MvName FROM tasks()"
        assertTrue(resolveColumn(sql, "TaskId"))
        assertTrue("insert-variant column resolves in union degrade", resolveColumn(sql, "Label"))
        assertTrue("mv-variant column resolves in union degrade", resolveColumn(sql, "MvName"))
    }

    fun testCatalogsZeroArgFixedSchema() {
        val sql = "SELECT CatalogId, CatalogName FROM catalogs()"
        assertTrue(resolveColumn(sql, "CatalogId"))
        assertTrue(resolveColumn(sql, "CatalogName"))
        assertEquals(
            listOf("CatalogId", "CatalogName", "CatalogType", "Property", "Value"),
            tableTypeColumns(sql)
        )
    }

    fun testMvInfosFixedSchema() {
        val sql = "SELECT Name, State FROM mv_infos(\"database\"=\"acme_derived\") WHERE Name = 'acme_events_mv'"
        assertTrue(resolveColumn(sql, "Name"))
        assertTrue(resolveColumn(sql, "State"))
    }

    fun testIcebergMetaVariantByQueryTypeLiteral() {
        val sql = "SELECT snapshot_id, operation FROM iceberg_meta(\"table\"=\"acme_ctl.db.tbl\", \"query_type\"=\"snapshots\")"
        assertTrue(resolveColumn(sql, "snapshot_id"))
        assertTrue(resolveColumn(sql, "operation"))
        assertFalse(
            "files-only column must not resolve for snapshots",
            resolveColumn(
                "SELECT file_path FROM iceberg_meta(\"table\"=\"acme_ctl.db.tbl\", \"query_type\"=\"snapshots\")",
                "file_path"
            )
        )
    }

    fun testS3OpenRelationHasNoFabricatedColumns() {
        val sql = "SELECT c1 FROM s3(\"uri\"=\"s3://acme-bucket/x.csv\", \"format\"=\"csv\")"
        assertEquals("open relation must expose no fabricated columns", emptyList<String>(), tableTypeColumns(sql))
    }

    // ---------------------------------------------------------------------------------------
    // Registry invariants
    // ---------------------------------------------------------------------------------------

    fun testNonQueryableFunctionsAreNotRegistered() {
        for (name in DorisTableFunctions.EXCLUDED_NOT_QUERYABLE) {
            assertNull("$name must not be registered as a relation function", DorisTableFunctions.byName(name))
        }
    }

    fun testVariantSelectionIsCaseInsensitiveAndUnknownFallsBackToUnion() {
        val schema = DorisTableFunctions.byName("jobs")!!.schema as DorisTableFunctions.Schema.ByPropertyLiteral
        assertEquals(schema.variants["mv"], schema.variant("MV"))
        assertEquals(schema.variants["insert"], schema.variant("insert"))
        assertEquals(schema.union, schema.variant(null))
        assertEquals(schema.union, schema.variant("bogus"))
    }

    fun testAllRegisteredTvfsHaveBuiltinsAndSaneSchemas() {
        for (name in DorisTableFunctions.allNames) {
            val tvf = DorisTableFunctions.byName(name)!!
            assertTrue("builtin registered for $name", DorisTableFunctions.builtins.containsKey(name.lowercase()))
            when (val s = tvf.schema) {
                is DorisTableFunctions.Schema.Fixed -> assertTrue("$name has columns", s.columns.isNotEmpty())
                is DorisTableFunctions.Schema.ByPropertyLiteral -> {
                    assertTrue("$name has variants", s.variants.isNotEmpty())
                    assertTrue(
                        "$name discriminator '${s.key}' is a declared property key",
                        tvf.key(s.key) != null
                    )
                }
                is DorisTableFunctions.Schema.Open -> assertTrue("$name has property keys", tvf.keys.isNotEmpty())
            }
        }
    }

    fun testResearchDocTierAInventoryIsRegistered() {
        val expected = listOf(
            "backends", "frontends", "frontends_disks", "catalogs", "mv_infos", "partitions",
            "numbers", "jobs", "tasks", "hudi_meta", "iceberg_meta",
            "s3", "hdfs", "local", "http", "query", "partition_values",
        )
        assertEquals(expected.sorted(), DorisTableFunctions.allNames.sorted())
    }

    // ---------------------------------------------------------------------------------------
    // Highlighting: no red where the model says silence, red kept where validation is real
    // ---------------------------------------------------------------------------------------

    private fun unresolvedColumnErrors(sql: String): List<String> {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("h.sql", sql)
        assertEquals("DorisSQL", myFixture.file.language.id)
        myFixture.enableInspections(com.intellij.sql.inspections.SqlResolveInspection())
        return myFixture.doHighlighting()
            .filter { it.description?.contains("Unable to resolve column") == true }
            .map { it.text }
    }

    fun testTasksMvCallIsCompletelyClean() {
        // Includes the double-quoted property args, which the MySQL grammar reads as identifiers.
        assertEquals(emptyList<String>(), unresolvedColumnErrors("SELECT MvName FROM tasks(\"type\"=\"mv\");"))
    }

    fun testS3OpenRelationProducesNoUnresolvedColumnErrors() {
        assertEquals(
            emptyList<String>(),
            unresolvedColumnErrors(
                "SELECT c1, c2 FROM s3(\"uri\"=\"s3://acme-bucket/x.csv\", \"format\"=\"csv\") WHERE c1 > 0;"
            )
        )
    }

    fun testWrongColumnOnConditionalVariantStaysRed() {
        assertEquals(listOf("Label"), unresolvedColumnErrors("SELECT Label FROM tasks(\"type\"=\"mv\");"))
    }

    fun testUnknownFunctionColumnsStayRed() {
        assertEquals(listOf("c1"), unresolvedColumnErrors("SELECT c1 FROM some_unknown_tvf('a'='b');"))
    }

    // ---------------------------------------------------------------------------------------
    // Completion
    // ---------------------------------------------------------------------------------------

    private fun completionsAt(sql: String): List<String> {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("c.sql", sql)
        return myFixture.completeBasic()?.map { it.lookupString } ?: emptyList()
    }

    /** The other half of the required pin: mv-variant columns are OFFERED in SELECT position. */
    fun testCompletionOffersMvVariantColumns() {
        val lookups = completionsAt("SELECT <caret> FROM tasks(\"type\"=\"mv\");")
        assertTrue("MvName offered, got: $lookups", lookups.contains("MvName"))
        assertTrue("RefreshMode offered", lookups.contains("RefreshMode"))
        assertFalse("insert-only column not offered for mv", lookups.contains("Label"))
    }

    fun testCompletionOffersPropertyKeys() {
        val lookups = completionsAt("SELECT * FROM tasks(<caret>);")
        assertTrue("property key 'type' offered, got: $lookups", lookups.contains("type"))
    }

    fun testCompletionOffersPropertyKeysInsideQuotes() {
        val lookups = completionsAt("SELECT * FROM iceberg_meta(\"query<caret>\");")
        assertTrue("query_type offered, got: $lookups", lookups.contains("query_type"))
    }

    fun testCompletionOffersEnumValues() {
        val lookups = completionsAt("SELECT * FROM tasks(\"type\"=\"<caret>\");")
        assertTrue("enum values offered, got: $lookups", lookups.containsAll(listOf("insert", "mv")))
    }

    fun testCompletionOffersTvfNames() {
        SqlDialectMappings.getInstance(project).setMapping(null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("c.sql", "SELECT * FROM iceberg<caret>;")
        val items = myFixture.completeBasic()
        if (items == null) {
            // The single matching item was auto-inserted.
            assertTrue(
                "iceberg_meta auto-completed, got: ${myFixture.editor.document.text}",
                myFixture.editor.document.text.contains("iceberg_meta")
            )
        } else {
            assertTrue(
                "iceberg_meta offered, got: ${items.map { it.lookupString }.take(20)}",
                items.any { it.lookupString == "iceberg_meta" }
            )
        }
    }
}
