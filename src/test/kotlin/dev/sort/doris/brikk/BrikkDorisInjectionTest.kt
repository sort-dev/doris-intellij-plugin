package dev.sort.doris.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlParameter
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.pipes.DorisPipes
import dev.sort.doris.sql.DorisSqlDialect
import dev.brikk.house.intellij.ContextResult
import dev.brikk.house.intellij.EntryRole
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Run with -Ptest.kotlin=present. Exercises the actual Kotlin injection extension chain. */
class BrikkDorisInjectionTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val stdlib = java.io.File(com.intellij.util.PathUtil.getJarPathForClass(Unit::class.java))
        com.intellij.testFramework.PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        myFixture.addFileToProject("org/intellij/lang/annotations/Language.java", """
            package org.intellij.lang.annotations;
            public @interface Language { String value(); String prefix() default ""; String suffix() default ""; }
        """.trimIndent())
        myFixture.addFileToProject("Runtime.kt", """
            package dev.brikk.house.sql.runtime
            import org.intellij.lang.annotations.Language
            annotation class BrikkSql
            interface Partial
            interface Shape : Partial
            annotation class BrikkTrait
            class Rel<T>
            object Sql {
                fun doris(@Language("DorisSQL") sql: String): String = sql
                fun duckdb(@Language("DorisSQL") sql: String): String = sql
            }
        """.trimIndent())
    }

    private fun configure(sql: String, prelude: String = "", annotation: String = "@BrikkSql",
                          entryPoint: String = "Sql.doris", trim: String = ""): KtStringTemplateExpression {
        val file = myFixture.configureByText("Query.kt", """
            import dev.brikk.house.sql.runtime.*
            $prelude
            $annotation
            fun query(tenant: String, src: Rel<String>, minId: Int) = $entryPoint(""${'"'}$sql""${'"'}$trim)
        """.trimIndent())
        return PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java)
            .single { it.text.contains(sql) }
    }

    private fun injected(host: KtStringTemplateExpression): PsiFile {
        val files = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)
        assertNotNull("Expected Doris injection for ${host.text}", files)
        return files!!.first().first.containingFile.also {
            assertEquals(DorisSqlDialect.INSTANCE, it.language)
        }
    }

    private fun context(host: KtStringTemplateExpression, includeShapes: Boolean = false): dev.brikk.house.intellij.BrikkContext {
        val result = BrikkDorisTemplate.inspect(host, includeShapes)
        assertTrue(result.toString(), result is ContextResult.Available)
        return (result as ContextResult.Available).context
    }

    fun testScalarReferencesAreNamedBindsOnInitialInjection() {
        val host = configure("SELECT tenant FROM customers WHERE tenant = ${'$'}tenant OR tenant = ${'$'}{tenant}")
        val performers = com.intellij.lang.injection.general.LanguageInjectionPerformer.INJECTOR_EXTENSION.allForLanguage(host.language)
        assertTrue(performers.joinToString { it.javaClass.name }, performers.any { it is BrikkDorisInjectionPerformer })
        assertTrue("Resolved Brikk call must be recognized", BrikkDorisTemplate.isRecognized(host))
        val sql = injected(host)
        assertTrue(BrikkDorisContext.isActive(sql))
        assertEquals("SELECT tenant FROM customers WHERE tenant = :tenant OR tenant = :tenant", sql.text)
        val binds = PsiTreeUtil.findChildrenOfType(sql, SqlParameter::class.java)
        assertEquals("Each scalar must have parameter PSI, not column PSI: ${sql.node}", 2, binds.size)
        assertEquals(listOf("tenant", "tenant"), binds.map { it.name })
        assertFalse(PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java)
            .any { it.text == ":tenant" })
        assertFalse("Automatic editing must not enable console execution", DorisPipes.isEnabled(project))
    }

    fun testRelationSlotAndScalarRemainDistinctAndPipesActivate() {
        val sql = injected(configure("FROM ${'$'}src() |> WHERE id > ${'$'}minId"))
        assertEquals("FROM src() |> WHERE id > :minId", sql.text)
        assertTrue(DorisPipes.isEditingEnabled(sql))
        val errors = PsiTreeUtil.findChildrenOfType(sql, com.intellij.psi.PsiErrorElement::class.java)
        assertTrue(errors.joinToString { it.errorDescription }, errors.isEmpty())
    }

    fun testPipeVetoDisablesBrikkEditing() {
        val sql = injected(configure("FROM ${'$'}src() |> WHERE id > ${'$'}minId"))
        assertTrue(BrikkDorisContext.isActive(sql))
        assertTrue(DorisPipes.isEditingEnabled(sql))
        val previous = System.getProperty(DorisPipes.PROPERTY)
        System.setProperty(DorisPipes.PROPERTY, "false")
        try {
            assertFalse(DorisPipes.isEditingEnabled(sql))
        } finally {
            if (previous == null) System.clearProperty(DorisPipes.PROPERTY) else System.setProperty(DorisPipes.PROPERTY, previous)
        }
    }

    fun testSupportedConstantAndNonConstantProperty() {
        val sql = injected(configure("SELECT ${'$'}COLS FROM customers WHERE tenant = ${'$'}value",
            "const val COLS = \"tenant, customer_id\"\nval value: String get() = error(\"never evaluate\")"))
        assertEquals("SELECT tenant, customer_id FROM customers WHERE tenant = :value", sql.text)
    }

    fun testCompiledConstantUsesItsMetadataLiteral() {
        val sql = injected(configure("SELECT ${'$'}MAX_VALUE", "import kotlin.Int.Companion.MAX_VALUE"))
        assertEquals("SELECT 2147483647", sql.text)
    }

    fun testComputedConstantAndComplexInterpolationAreRefused() {
        val host = configure("SELECT ${'$'}COLS, ${'$'}{tenant.lowercase()} FROM customers", "const val COLS = \"a\" + \"b\"")
        val entries = context(host).entries.filter { it.role == EntryRole.UNSUPPORTED }
        val sql = injected(host)
        assertEquals(2, entries.size)
        assertEquals("SELECT ${entries[0].replacement}, ${entries[1].replacement} FROM customers", sql.text)
        assertTrue("Unsupported entries must keep distinct placeholder identities", entries[0].replacement != entries[1].replacement)
        assertTrue(entries[0].problem!!.contains("literal initializer"))
    }

    fun testUnrelatedCallsAndUnannotatedFunctionsKeepNativeInjection() {
        val unannotated = injected(configure("SELECT ${'$'}tenant", annotation = ""))
        assertFalse(BrikkDorisContext.isActive(unannotated))
        assertEquals("SELECT tenant", unannotated.text)
        val other = injected(configure("SELECT ${'$'}tenant", entryPoint = "Sql.duckdb"))
        assertFalse(BrikkDorisContext.isActive(other))
        assertEquals("SELECT tenant", other.text)
    }

    fun testAnnotationAliasAndEntryPointAliasResolve() {
        val host = configure("SELECT ${'$'}tenant",
            "import dev.brikk.house.sql.runtime.BrikkSql as QuerySql\nimport dev.brikk.house.sql.runtime.Sql.doris as sql",
            annotation = "@QuerySql", entryPoint = "sql")
        assertEquals("SELECT :tenant", injected(host).text)
    }

    fun testRelTypeAliasUsesSharedExpandedTypeClassification() {
        val file = myFixture.configureByText("Alias.kt", """
            import dev.brikk.house.sql.runtime.*
            typealias Input = Rel<Partial>
            @BrikkSql
            fun query(src: Input, n: Int) = Sql.doris("FROM ${'$'}{src}() |> WHERE id > ${'$'}n")
        """.trimIndent())
        val host = PsiTreeUtil.findChildOfType(file, KtStringTemplateExpression::class.java)!!
        val result = context(host)
        assertEquals(listOf("src"), result.slots)
        assertEquals(listOf("n"), result.binds)
        assertTrue("The injection path must not enumerate relation shapes", result.relations.isEmpty())
        assertEquals("FROM src() |> WHERE id > :n", injected(host).text)
    }

    fun testLookalikeTrimDoesNotActivateTheDorisAdapter() {
        for (trim in listOf("trimIndent", "trimMargin")) {
            val host = configure("SELECT ${'$'}tenant", "fun String.$trim(): String = this", trim = ".$trim()")
            assertEquals(ContextResult.NotBrikk, BrikkDorisTemplate.inspect(host))
            // Kotlin's native contributor may decline this custom wrapper entirely.
            val files = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
            assertTrue(files.toString(), files.none { BrikkDorisContext.isActive(it.first.containingFile) })
            assertNull(host.getUserData(BrikkDorisContext.PROFILE))
        }
    }

    fun testPendingProblemDoesNotProduceAnUnsupportedHostDiagnostic() {
        val host = configure("SELECT ${'$'}missing, ${'$'}another")
        val entries = context(host).entries.filter { it.role == EntryRole.PENDING }
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.problem != null })
        assertTrue(entries[0].replacement != entries[1].replacement)
        val sql = injected(host)
        assertTrue(BrikkDorisContext.hasUncertainTemplates(sql))
        assertTrue(InjectedLanguageManager.getInstance(project).shouldInspectionsBeLenient(sql))
        val highlights = myFixture.doHighlighting().mapNotNull { it.description }
        assertFalse(highlights.toString(), highlights.any { it.startsWith("Brikk-house:") })
    }

    fun testMalformedRelationSlotDiagnosticUsesSharedAuthoredRange() {
        val host = configure("SELECT '😀', ${'$'}{src}")
        val entry = context(host).entries.single { it.role == EntryRole.RELATION_SLOT }
        assertNotNull(entry.problem)
        assertEquals("${'$'}{src}", host.text.substring(entry.range.start, entry.range.end))
        val sql = injected(host)
        assertTrue(BrikkDorisContext.hasUncertainTemplates(sql))
        val highlight = myFixture.doHighlighting().single { it.description?.startsWith("Brikk-house: Rel parameter") == true }
        assertEquals(host.textRange.startOffset + entry.range.start, highlight.startOffset)
        assertEquals(host.textRange.startOffset + entry.range.end, highlight.endOffset)
    }

    fun testUnresolvedUnregisteredEntryDoesNotClaimDoris() {
        val host = configure("SELECT ${'$'}tenant", entryPoint = "notResolved")
        assertEquals(ContextResult.NotBrikk, BrikkDorisTemplate.inspect(host))
        host.putUserData(BrikkDorisContext.PROFILE, true)
        host.putUserData(BrikkDorisContext.UNCERTAIN, true)
        var registered = false
        val registrar = object : com.intellij.lang.injection.MultiHostRegistrar {
            override fun startInjecting(language: com.intellij.lang.Language): com.intellij.lang.injection.MultiHostRegistrar {
                registered = true
                return this
            }
            override fun addPlace(prefix: String?, suffix: String?, host: com.intellij.psi.PsiLanguageInjectionHost,
                                  rangeInsideHost: com.intellij.openapi.util.TextRange) = this
            override fun doneInjecting() = Unit
        }
        val injection = object : com.intellij.lang.injection.general.Injection {
            override fun getInjectedLanguageId() = "DorisSQL"
            override fun getInjectedLanguage() = DorisSqlDialect.INSTANCE
            override fun getPrefix() = ""
            override fun getSuffix() = ""
            override fun getSupportId() = "kotlin"
        }
        assertFalse(BrikkDorisInjectionPerformer().performInjection(registrar, injection, host))
        assertFalse(registered)
        assertNull(host.getUserData(BrikkDorisContext.PROFILE))
        assertNull(host.getUserData(BrikkDorisContext.UNCERTAIN))
        com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        // A later resolved non-Brikk declaration must restore the native path without touching SQL.
        myFixture.addFileToProject("Resolved.kt", "fun notResolved(sql: String): String = sql")
        assertEquals(ContextResult.NotBrikk, BrikkDorisTemplate.inspect(host))
        assertFalse(BrikkDorisInjectionPerformer().performInjection(registrar, injection, host))
        assertFalse(registered)
    }

    fun testSharedRelationContractsRemainFreshAndFunctionLocal() {
        val file = myFixture.configureByText("Contracts.kt", """
            import dev.brikk.house.sql.runtime.*
            @BrikkTrait interface Base : Partial { val id: Long }
            @BrikkTrait interface Event : Base { val code: String? }
            interface Closed : Shape { val other: Int }
            @BrikkSql fun <T : Event> query(src: Rel<T>) = Sql.doris("FROM ${'$'}src() |> SELECT id")
            @BrikkSql fun second(src: Rel<Closed>) = Sql.doris("FROM ${'$'}src() |> SELECT other")
        """.trimIndent())
        val hosts = PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java).toList()
        val first = (context(hosts[0], includeShapes = true).relations.getValue("src") as dev.brikk.house.intellij.ShapeResult.Available).shape
        val second = (context(hosts[1], includeShapes = true).relations.getValue("src") as dev.brikk.house.intellij.ShapeResult.Available).shape
        assertEquals(dev.brikk.house.intellij.ShapeKnowledge.PARTIAL, first.knowledge)
        assertEquals(setOf("id", "code"), first.columns.map { it.name }.toSet())
        assertEquals(dev.brikk.house.intellij.ColumnNullability.NULLABLE, first.columns.single { it.name == "code" }.nullability)
        assertEquals(dev.brikk.house.intellij.ShapeKnowledge.FULL, second.knowledge)
        assertEquals(listOf("other"), second.columns.map { it.name })
        val property = first.columns.single { it.name == "id" }.navigation!!
        assertEquals(dev.brikk.house.intellij.NavigationKind.PROPERTY, property.kind)
        assertEquals("val id: Long", file.text.substring(property.location!!.start, property.location!!.end))
        assertEquals("FROM src() |> SELECT id", injected(hosts[0]).text)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(myFixture.editor.document.text.replace("val code: String?", "val code: Int"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val updated = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).first()
        val shape = (context(updated, includeShapes = true).relations.getValue("src") as dev.brikk.house.intellij.ShapeResult.Available).shape
        assertEquals("kotlin.Int", shape.columns.single { it.name == "code" }.kotlinClassId)
        assertEquals(dev.brikk.house.intellij.ColumnNullability.NON_NULL, shape.columns.single { it.name == "code" }.nullability)
    }

    private fun relationSql(sql: String, second: String = "other: Rel<Other>"): PsiFile {
        val file = myFixture.configureByText("Relations.kt", """
            import dev.brikk.house.sql.runtime.*
            interface Row : Shape { val id: Long; val code: String? }
            interface Other : Shape { val id: Long; val label: String }
            @BrikkSql fun query(src: Rel<Row>, $second) = Sql.doris(""${'"'}$sql""${'"'})
        """.trimIndent())
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file)
        return injected(PsiTreeUtil.findChildOfType(top, KtStringTemplateExpression::class.java)!!)
    }

    fun testTypedRelationColumnResolvesToAuthoredKotlinProperty() {
        val sql = relationSql("SELECT e.code FROM ${'$'}src() AS e")
        val call = PsiTreeUtil.findChildOfType(sql, com.intellij.sql.psi.SqlFunctionCallExpression::class.java)!!
        assertEquals("dev.sort.doris.sql.DorisBrikkFunctionCall", call.javaClass.name)
        val type = call.dasType as? com.intellij.sql.psi.SqlTableType
        assertNotNull(type)
        assertEquals(setOf("id", "code"), (0 until type!!.columnCount).map { type.getColumnName(it) }.toSet())
        val ref = PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java).single { it.text == "e.code" }
        val resolved = ref.resolve()
        assertNotNull("Known slot column must resolve without a physical datasource", resolved)
        val targets = dev.sort.doris.sql.BrikkRelationNavigation().getGotoDeclarationTargets(ref, ref.textOffset, myFixture.editor)
        assertNotNull(targets)
        assertTrue(targets!!.single().text, targets.single().text.contains("val code: String?"))
    }

    fun testRelationAliasesJoinsAndProjectionUsePlatformScopes() {
        val sql = relationSql("SELECT q.projected FROM (SELECT e.code AS projected FROM ${'$'}src() AS e LEFT JOIN ${'$'}other() AS o ON e.id = o.id) AS q")
        val refs = PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java)
        for (name in listOf("q.projected", "e.code", "e.id", "o.id")) {
            assertNotNull("$name must resolve in its SQL scope", refs.single { it.text == name }.resolve())
        }
        val wrong = relationSql("SELECT e.code FROM ${'$'}src() AS hidden")
        assertNull(PsiTreeUtil.findChildrenOfType(wrong, SqlReferenceExpression::class.java).single { it.text == "e.code" }.resolve())
    }

    fun testRelationTraitEditRefreshesSqlWithoutEditingTheTemplate() {
        val sql = relationSql("SELECT code FROM ${'$'}src()")
        val ref = PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java).single { it.text == "code" }
        assertNotNull(ref.resolve())
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText(myFixture.editor.document.text.replace("val code: String?", "val renamed: String?"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val host = PsiTreeUtil.findChildOfType(myFixture.file, KtStringTemplateExpression::class.java)!!
        val updated = injected(host)
        assertNull(PsiTreeUtil.findChildrenOfType(updated, SqlReferenceExpression::class.java).single { it.text == "code" }.resolve())
    }

    fun testTypedSlotQualifiedCompletionAndMissingColumns() {
        relationSql("SELECT e.<caret> FROM ${'$'}src() AS e")
        val suggestions = myFixture.completeBasic().orEmpty().map { it.lookupString }
        assertTrue(suggestions.toString(), suggestions.containsAll(listOf("id", "code")))
        val sql = relationSql("SELECT e.missing FROM ${'$'}src() AS e")
        assertNull(PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java).single { it.text == "e.missing" }.resolve())
    }

    fun testPartialRelationDoesNotPretendToBeAClosedInventory() {
        val file = myFixture.configureByText("Partial.kt", """
            import dev.brikk.house.sql.runtime.*
            interface Row : Partial { val id: Long }
            @BrikkSql fun query(src: Rel<Row>) = Sql.doris("SELECT s.extra FROM ${'$'}src() AS s")
        """.trimIndent())
        val sql = injected(PsiTreeUtil.findChildOfType(file, KtStringTemplateExpression::class.java)!!)
        val ref = PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java).single { it.text == "s.extra" }
        assertTrue(dev.sort.doris.sql.BrikkRelationColumns.openColumnContext(ref))
        val info = com.intellij.codeInsight.daemon.impl.HighlightInfo.newHighlightInfo(com.intellij.codeInsight.daemon.impl.HighlightInfoType.ERROR)
            .range(ref.textRange).descriptionAndTooltip("Unable to resolve column 'extra'").create()!!
        assertFalse(dev.sort.doris.sql.DorisHighlightInfoFilter().accept(info, sql))
    }

    fun testKotlinSlotInputsPropagateThroughExistingPipeStageAnalysis() {
        val sql = relationSql("FROM ${'$'}src() |> EXTEND id + 1 AS added |> SELECT code, added |> WHERE code <> ''")
        val engineScope = dev.sort.doris.sql.BrikkRelationColumns.pipeScope(sql, sql.text, sql.text.indexOf("WHERE") + 6)
        assertEquals(setOf("code", "added"), engineScope?.toSet())
        val before = dev.sort.doris.sql.BrikkRelationColumns.pipeScope(sql, sql.text, sql.text.indexOf("EXTEND") + 7)
        assertEquals(setOf("id", "code"), before?.toSet())
    }

    fun testRowsKeywordSlotUsesThe019EngineWithoutBindingUnusedParameters() {
        val sql = relationSql("FROM ${'$'}rows() |> SELECT id, code |> WHERE id > 0", second = "rows: Rel<Row>")
        val scope = dev.sort.doris.sql.BrikkRelationColumns.pipeScope(sql, sql.text, sql.text.indexOf("WHERE") + 6)
        assertEquals(setOf("id", "code"), scope?.toSet())
        val fragment = dev.brikk.house.sql.shape.SqlFragment(sql.text, "doris")
        assertEquals(listOf("rows"), fragment.tableSlots)
    }

    fun testRowsWindowFrameRemainsNativeSyntaxAfterThe019Upgrade() {
        val sql = relationSql("SELECT SUM(id) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM ${'$'}src()")
        val fragment = dev.brikk.house.sql.shape.SqlFragment(sql.text, "doris")
        assertEquals(listOf("src"), fragment.tableSlots)
        assertTrue(fragment.toExecutable("doris").sql.contains("ROWS BETWEEN 1 PRECEDING AND CURRENT ROW"))
    }

    fun testPipeColumnCompletionUsesTheKotlinContractWithoutAConnection() {
        relationSql("FROM ${'$'}src() |> SELECT code |> WHERE co<caret>")
        val suggestions = myFixture.completeBasic().orEmpty().map { it.lookupString }
        assertTrue(suggestions.toString(), "code" in suggestions)
        assertFalse("Dropped input columns must not reappear", "id" in suggestions)
    }

    private fun columnPresentations(): Map<String, com.intellij.codeInsight.lookup.LookupElementPresentation> =
        myFixture.completeBasic().orEmpty().associate { item ->
            item.lookupString to com.intellij.codeInsight.lookup.LookupElementPresentation().also(item::renderElement)
        }

    fun testPipeCompletionRendersBrikkOriginAndNullableKotlinTypes() {
        relationSql("FROM ${'$'}src() |> WHERE <caret>")
        val items = columnPresentations()
        assertEquals("Long", items.getValue("id").typeText)
        assertEquals("String?", items.getValue("code").typeText)
        assertEquals("  brikk column (src)", items.getValue("code").tailText)
    }

    fun testPipeBrikkColumnsUseTheSharedBricksIcon() {
        relationSql("FROM ${'$'}src() |> WHERE <caret>")
        val items = columnPresentations()
        assertEquals(dev.brikk.house.sql.intellij.completion.SqlLookupIcons.BrikkColumn, items.getValue("id").icon)
        assertEquals(dev.brikk.house.sql.intellij.completion.SqlLookupIcons.BrikkColumn, items.getValue("code").icon)
    }

    fun testNativeQualifiedCompletionRendersWithoutReplacingPlatformResolution() {
        relationSql("SELECT e.<caret> FROM ${'$'}src() AS e")
        val items = columnPresentations()
        assertEquals("Long", items.getValue("id").typeText)
        assertEquals("String?", items.getValue("code").typeText)
        assertEquals("  brikk column (e)", items.getValue("code").tailText)
    }

    fun testCorrelatedCompletionRetainsBrikkColumnDecoration() {
        relationSql("SELECT 1 FROM ${'$'}src() AS e WHERE EXISTS (SELECT 1 FROM t WHERE t.x = e.<caret>)")
        val items = myFixture.completeBasic().orEmpty()
        val id = items.single { it.lookupString == "id" }
        val code = items.single { it.lookupString == "code" }
        assertTrue(id.`object` is com.intellij.sql.psi.SqlResolveResult)
        assertTrue(code.`object` is com.intellij.sql.psi.SqlResolveResult)
        assertEquals("Long", com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(id).typeText)
        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(code)
        assertEquals("String?", presentation.typeText)
        assertEquals("  brikk column (e)", presentation.tailText)
    }

    fun testDerivedTableCompletionRetainsBrikkColumnDecoration() {
        relationSql("SELECT <caret> FROM (SELECT * FROM ${'$'}src()) d")
        val items = myFixture.completeBasic().orEmpty()
        val id = items.single { it.lookupString == "id" }
        val code = items.single { it.lookupString == "code" }
        assertTrue(id.`object` is com.intellij.sql.psi.SqlResolveResult)
        assertTrue(code.`object` is com.intellij.sql.psi.SqlResolveResult)
        assertEquals("Long", com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(id).typeText)
        val presentation = com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(code)
        assertEquals("String?", presentation.typeText)
        assertEquals("  brikk column (src)", presentation.tailText)
    }

    fun testDerivedProjectionDoesNotRestoreDroppedBrikkColumns() {
        relationSql("SELECT <caret> FROM (SELECT code FROM ${'$'}src()) d")
        val items = columnPresentations()
        assertTrue("The projected code column is still offered", items.containsKey("code"))
        assertFalse("The derived projection dropped id", items.containsKey("id"))
    }

    fun testLocalAliasShadowsCorrelatedBrikkColumns() {
        relationSql("SELECT 1 FROM ${'$'}src() AS e WHERE EXISTS (SELECT 1 FROM t AS e WHERE e.<caret>)")
        val items = columnPresentations()
        assertFalse("The local e alias hides the Brikk relation", items["id"]?.typeText == "Long")
        assertFalse("The local e alias hides the Brikk relation", items["code"]?.typeText == "String?")
    }

    fun testJoinRelationPositionDoesNotCreateBrikkColumns() {
        relationSql("SELECT * FROM ${'$'}src() AS e JOIN i<caret> ON e.id = 1")
        val items = columnPresentations()
        assertFalse("JOIN expects a relation, not id", items.containsKey("id"))
        assertFalse("JOIN expects a relation, not code", items.containsKey("code"))
    }

    fun testKeywordPositionDoesNotCreateBrikkColumns() {
        relationSql("SELECT * FROM ${'$'}src() AS e <caret>")
        val items = columnPresentations()
        assertFalse("A clause keyword slot must not offer id", items.containsKey("id"))
        assertFalse("A clause keyword slot must not offer code", items.containsKey("code"))
    }

    fun testStringPositionDoesNotCreateBrikkColumns() {
        relationSql("SELECT '<caret>' FROM ${'$'}src() AS e")
        val items = columnPresentations()
        assertFalse("A string literal must not offer id", items.containsKey("id"))
        assertFalse("A string literal must not offer code", items.containsKey("code"))
    }

    fun testDecoratedNativeColumnKeepsInsertionAndKotlinNavigation() {
        relationSql("SELECT e.<caret> FROM ${'$'}src() AS e")
        val item = myFixture.completeBasic().orEmpty().single { it.lookupString == "code" }
        assertTrue("Keep the platform completion object", item.`object` is com.intellij.sql.psi.SqlResolveResult)
        assertTrue(com.intellij.codeInsight.lookup.LookupElementPresentation.renderElement(item).isTypeGrayed)
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(com.intellij.codeInsight.lookup.Lookup.NORMAL_SELECT_CHAR)
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        val sql = injected(PsiTreeUtil.findChildOfType(top, KtStringTemplateExpression::class.java)!!)
        assertEquals("SELECT e.code FROM src() AS e", sql.text)
        val ref = PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java).single { it.text == "e.code" }
        assertNotNull(ref.resolve())
        val targets = dev.sort.doris.sql.BrikkRelationNavigation().getGotoDeclarationTargets(ref, ref.textOffset, myFixture.editor)!!
        assertTrue(targets.single().text, targets.single().text.contains("val code: String?"))
    }

    fun testEqualNativeColumnNamesKeepTheirSourceLabels() {
        relationSql("SELECT i<caret>d FROM ${'$'}src() AS e JOIN ${'$'}other() AS o ON e.id = o.id")
        val items = myFixture.completeBasic().orEmpty().filter { it.lookupString == "id" }.map { item ->
            com.intellij.codeInsight.lookup.LookupElementPresentation().also(item::renderElement)
        }
        assertEquals(setOf("  brikk column (e)", "  brikk column (o)"), items.map { it.tailText }.toSet())
        assertTrue(items.all { it.typeText == "Long" })
    }

    fun testCompletionInsideAPipeExpressionShowsJavaKotlinTypeNames() {
        val jdk = com.intellij.openapi.projectRoots.JavaSdk.getInstance().createJdk("Brikk completion JDK", System.getProperty("java.home"), false)
        WriteCommandAction.runWriteCommandAction(project) {
            com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable)
        }
        com.intellij.openapi.roots.ModuleRootModificationUtil.setModuleSdk(module, jdk)
        val file = myFixture.configureByText("Instant.kt", """
            import dev.brikk.house.sql.runtime.*
            interface Event : Shape { val event_at: java.time.Instant; val payload: String? }
            @BrikkSql fun query(src: Rel<Event>) = Sql.doris("FROM ${'$'}src() |> EXTEND COALESCE(<caret>, event_at) AS projected")
        """.trimIndent())
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(file)
        injected(PsiTreeUtil.findChildOfType(top, KtStringTemplateExpression::class.java)!!)
        val items = columnPresentations()
        assertEquals("Instant", items.getValue("event_at").typeText)
        assertEquals("String?", items.getValue("payload").typeText)
        assertEquals("  brikk column (src)", items.getValue("event_at").tailText)
    }

    fun testProjectedPipeColumnsRetainTypesButComputedColumnsDoNotBorrowThem() {
        relationSql("FROM ${'$'}src() AS e |> EXTEND id + 1 AS id |> SELECT code AS renamed, id |> WHERE <caret>")
        val items = columnPresentations()
        assertEquals("String?", items.getValue("renamed").typeText)
        assertEquals("  brikk column (e)", items.getValue("renamed").tailText)
        assertEquals("unknown type (nullability unknown)", items.getValue("id").typeText)
        assertEquals("  PIPE column", items.getValue("id").tailText)
        assertFalse(items.containsKey("code"))
    }

    fun testPipePresentationRefreshesWhenOnlyTheKotlinTraitChanges() {
        relationSql("FROM ${'$'}src() |> WHERE co<caret>")
        assertEquals("String?", columnPresentations().getValue("code").typeText)
        com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
        val top = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        val document = PsiDocumentManager.getInstance(project).getDocument(top)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText(document.text.replace("val code: String?", "val code: Int"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        myFixture.openFileInEditor(top.virtualFile)
        myFixture.editor.caretModel.moveToOffset(document.text.indexOf("WHERE co") + "WHERE co".length)
        assertEquals("Int", columnPresentations().getValue("code").typeText)
    }

    fun testLiteralDollarsJsonPathsAndCommentsRemainUnchanged() {
        val sql = injected(configure("SELECT json_extract(payload, '${'$'}.customer_id'), '${'$'}{'$'}native' -- ${'$'}.comment\nFROM customers WHERE tenant = ${'$'}tenant"))
        assertEquals("SELECT json_extract(payload, '${'$'}.customer_id'), '${'$'}native' -- ${'$'}.comment\nFROM customers WHERE tenant = :tenant", sql.text)
    }

    fun testSourceMappingAfterRepeatedBindsAndUnicode() {
        val host = configure("SELECT '😀', tenant FROM customers WHERE tenant = ${'$'}tenant\nAND customer_id = ${'$'}{tenant}")
        val sql = injected(host)
        val manager = InjectedLanguageManager.getInstance(project)
        for (word in listOf("tenant FROM", "customer_id =")) {
            assertEquals(host.textRange.startOffset + host.text.indexOf(word), manager.injectedToHost(sql, sql.text.indexOf(word)))
        }
    }

    fun testParameterEditRefreshesVirtualBind() {
        val host = configure("SELECT tenant FROM customers WHERE tenant = ${'$'}tenant")
        assertTrue(injected(host).text.endsWith(":tenant"))
        val parameter = PsiTreeUtil.findChildrenOfType(myFixture.file, org.jetbrains.kotlin.psi.KtParameter::class.java)
            .single { it.name == "tenant" }
        myFixture.renameElement(parameter, "tenantKey")
        val updated = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single()
        assertTrue(updated.text.endsWith("${'$'}tenantKey\"\"\""))
        assertTrue(injected(updated).text.endsWith(":tenantKey"))
    }

    fun testRelTypeChangeRefreshesClassificationWithoutEditingTheTemplate() {
        val host = configure("FROM ${'$'}src() |> WHERE id > ${'$'}minId")
        assertTrue(injected(host).text.startsWith("FROM src()"))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.setText(document.text.replace("src: Rel<String>", "src: String"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val updated = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single()
        assertTrue(injected(updated).text.startsWith("FROM :src()"))
    }

    fun testTrimmedMultilineStringsRetainSourceMapping() {
        for ((trim, prefix) in listOf(".trimIndent()" to "", ".trimMargin()" to "|")) {
            val host = configure("\n    ${prefix}SELECT tenant\n    ${prefix}FROM customers\n    ${prefix}WHERE tenant = ${'$'}tenant\n", trim = trim)
            val sql = injected(host)
            assertEquals("SELECT tenant\nFROM customers\nWHERE tenant = :tenant", sql.text)
            val where = sql.text.indexOf("WHERE")
            assertEquals(host.textRange.startOffset + host.text.indexOf("WHERE"),
                InjectedLanguageManager.getInstance(project)
                    .injectedToHost(sql, com.intellij.openapi.util.TextRange(where, where + 5)).startOffset)
        }
    }

    fun testPlainNamedBindAndUnresolvedInterpolationAreParameters() {
        val host = configure("SELECT tenant FROM customers WHERE tenant = :tenant OR tenant = ${'$'}missing")
        val placeholder = context(host).entries.single { it.role == EntryRole.PENDING }.replacement
        val sql = injected(host)
        assertEquals("SELECT tenant FROM customers WHERE tenant = :tenant OR tenant = $placeholder", sql.text)
        assertEquals(2, PsiTreeUtil.findChildrenOfType(sql, SqlParameter::class.java).size)
    }

    fun testTemporarilyUnresolvedSymbolRetriesWithoutTemplateEdit() {
        val host = configure("SELECT ${'$'}later")
        val placeholder = context(host).entries.single { it.role == EntryRole.PENDING }.replacement
        val pending = injected(host)
        assertEquals("SELECT $placeholder", pending.text)
        assertTrue(BrikkDorisContext.hasUncertainTemplates(pending))
        val info = com.intellij.codeInsight.daemon.impl.HighlightInfo.newHighlightInfo(
            com.intellij.codeInsight.daemon.impl.HighlightInfoType.WARNING).range(0, 6)
            .descriptionAndTooltip("Condition is always 'true'").create()!!
        assertFalse(dev.sort.doris.sql.DorisHighlightInfoFilter().accept(info, pending))
        myFixture.addFileToProject("Later.kt", "val later: String = \"not sql\"")
        val resolved = injected(host)
        assertEquals("SELECT :later", resolved.text)
        assertFalse(BrikkDorisContext.hasUncertainTemplates(resolved))
        assertTrue(dev.sort.doris.sql.DorisHighlightInfoFilter().accept(info, resolved))
    }

    fun testRemovingAnnotationClearsScopedProfile() {
        val host = configure("SELECT ${'$'}tenant")
        assertTrue(BrikkDorisContext.isActive(injected(host)))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.setText(document.text.replace("@BrikkSql", ""))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val updated = PsiTreeUtil.findChildrenOfType(myFixture.file, KtStringTemplateExpression::class.java).single()
        val sql = injected(updated)
        assertFalse(BrikkDorisContext.isActive(sql))
        assertEquals("SELECT tenant", sql.text)
    }

    fun testEscapesDecodeThroughKotlinEscaperWithAuthoredRanges() {
        val authored = "SELECT '\\${'$'}literal', '${'$'}.path', '\\u03bb' FROM customers\\nWHERE tenant = ${'$'}tenant"
        val file = myFixture.configureByText("Escapes.kt", """
            import dev.brikk.house.sql.runtime.*
            @BrikkSql
            fun query(tenant: String) = Sql.doris("$authored")
        """.trimIndent())
        val host = PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java).single()
        val sql = injected(host)
        assertEquals("SELECT '\\${'$'}literal', '${'$'}.path', '\\u03bb' FROM customers\\nWHERE tenant = :tenant", sql.text)
        val errors = PsiTreeUtil.findChildrenOfType(sql, com.intellij.psi.PsiErrorElement::class.java)
        assertTrue(errors.joinToString { it.errorDescription }, errors.isEmpty())
        assertNotNull(PsiTreeUtil.findChildOfType(sql, com.intellij.sql.psi.SqlWhereClause::class.java))
        val annotator = dev.sort.doris.sql.DorisErrorAnnotator()
        val input = annotator.collectInformation(sql)!!
        assertEquals("SELECT '${'$'}literal', '${'$'}.path', 'λ' FROM customers\nWHERE tenant = :tenant",
            InjectedLanguageManager.getInstance(project).getUnescapedText(sql))
        assertNotNull(annotator.doAnnotate(input))
        assertTrue(dev.sort.doris.sql.dorisAnnotations(sql).isEmpty())
        val where = sql.text.indexOf("WHERE")
        assertEquals(host.textRange.startOffset + host.text.indexOf("WHERE"),
            InjectedLanguageManager.getInstance(project)
                .injectedToHost(sql, com.intellij.openapi.util.TextRange(where, where + 5)).startOffset)
    }

    fun testUnsupportedInterpolationHasHostDiagnosticAndNonEditableSqlPlaceholder() {
        val host = configure("SELECT ${'$'}{tenant.lowercase()}")
        val placeholder = context(host).entries.single { it.role == EntryRole.UNSUPPORTED }.replacement!!
        val sql = injected(host)
        val start = sql.text.indexOf(placeholder)
        val editable = InjectedLanguageManager.getInstance(project).intersectWithAllEditableFragments(sql,
            com.intellij.openapi.util.TextRange(start, sql.textLength))
        assertTrue(editable.toString(), editable.all { it.isEmpty })
        val highlights = myFixture.doHighlighting().mapNotNull { it.description }
        assertTrue(highlights.toString(), highlights.any { it.startsWith("Brikk-house: Only simple Kotlin references") })
    }

    fun testExecuteDoesNotDelegateBrikkInspectionText() {
        val sql = injected(configure("FROM ${'$'}src() |> WHERE id > ${'$'}minId"))
        var delegated = false
        val previous = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) { delegated = true }
        }
        val data = com.intellij.openapi.actionSystem.impl.SimpleDataContext.getSimpleContext(
            com.intellij.openapi.actionSystem.CommonDataKeys.PSI_FILE, sql,
            com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project))
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext("test", null, data)
        dev.sort.doris.pipes.DorisPipesRunQueryAction(1, previous).actionPerformed(event)
        assertFalse("Template text must not reach a stock Execute action", delegated)
    }

    fun testSameNamedAnnotationAndMethodDoNotActivateBrikk() {
        val annotation = injected(configure("SELECT ${'$'}tenant", "annotation class BrikkSql"))
        assertFalse(BrikkDorisContext.isActive(annotation))
        val method = injected(configure("SELECT ${'$'}tenant", """
            object OtherSql {
                fun doris(@org.intellij.lang.annotations.Language("DorisSQL") sql: String) = sql
            }
        """.trimIndent(), entryPoint = "OtherSql.doris"))
        assertFalse(BrikkDorisContext.isActive(method))
        assertEquals("SELECT tenant", method.text)
    }

    fun testLocalScalarIsNotEvaluatedAsSqlText() {
        val file = myFixture.configureByText("Local.kt", """
            import dev.brikk.house.sql.runtime.*
            @BrikkSql
            fun query(tenant: String): String {
                val value = "not sql"
                return Sql.doris("SELECT tenant FROM customers WHERE tenant = ${'$'}value")
            }
        """.trimIndent())
        val host = PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java).single { it.hasInterpolation() }
        assertEquals("SELECT tenant FROM customers WHERE tenant = :value", injected(host).text)
    }

    fun testDdlDatasourceInspectionsDistinguishColumnAndBind() {
        val ddl = myFixture.addFileToProject("schema.sql",
            "CREATE TABLE customers (tenant VARCHAR(64) NOT NULL, customer_id VARCHAR(64));")
        dev.brikk.house.sql.intellij.testing.setSqlDialectMapping(project, ddl.virtualFile, DorisSqlDialect.INSTANCE)
        val manager = com.intellij.sql.database.SqlDataSourceManager.getInstance(project)
        val source = manager.createEmpty().apply {
            name = "Brikk test DDL"
            definedDbms = dev.sort.doris.DorisDbms.DORIS
            isAutoSync = false
            setFiles(arrayOf(ddl.virtualFile))
        }
        manager.addDataSource(source)
        try {
            val sync = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { source.sync() }
            com.intellij.testFramework.PlatformTestUtil.waitForFuture(sync, 30_000)
            val ds = com.intellij.database.psi.DbPsiFacade.getInstance(project).findDataSource(source.uniqueId)!!
            com.intellij.sql.psi.impl.DataSourceProvider.EP_NAME.point.registerExtension(
                object : com.intellij.sql.psi.impl.DataSourceProvider {
                    override fun getDataSources(element: PsiFile): List<com.intellij.database.psi.DbDataSource> = listOf(ds)
                }, testRootDisposable)
            // The platform moved this inspection to the resolve package in 263.
            val resolveClass = try {
                Class.forName("com.intellij.sql.inspections.resolve.SqlResolveInspection")
            } catch (_: ClassNotFoundException) {
                Class.forName("com.intellij.sql.inspections.SqlResolveInspection")
            }
            val resolveInspection = resolveClass.getConstructor().newInstance() as com.intellij.codeInspection.LocalInspectionTool
            myFixture.enableInspections(com.intellij.sql.inspections.SqlConstantExpressionInspection(), resolveInspection)

            val sql = injected(configure("SELECT tenant FROM customers WHERE tenant = ${'$'}tenant"))
            val columns = PsiTreeUtil.findChildrenOfType(sql, SqlReferenceExpression::class.java)
                .filter { it.text == "tenant" && it.referenceElementType == com.intellij.sql.psi.SqlCompositeElementTypes.SQL_COLUMN_REFERENCE }
            assertEquals(2, columns.size)
            assertTrue("Physical tenant columns must resolve against DDL", columns.all { it.resolve() != null })
            val warnings = myFixture.doHighlighting().mapNotNull { it.description }
            assertFalse(warnings.joinToString(), warnings.any { it.contains("always 'true'") })
            assertFalse(warnings.joinToString(), warnings.any { it.contains("Unable to resolve column 'tenant'") })

            configure("SELECT nonexistent FROM customers WHERE tenant = tenant")
            val genuine = myFixture.doHighlighting().mapNotNull { it.description }
            assertTrue("Ordinary Doris inspections must remain active: $genuine", genuine.any { it.contains("Unable to resolve column 'nonexistent'") })

            // IDEA 261 runs constant-condition DFA only for its built-in MySQL/Postgres/MS Dbms
            // allowlist. Keep a positive control using the same datasource and real inspection.
            val mapped = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText("Control.sql",
                com.intellij.sql.dialects.mysql.MysqlDialect.INSTANCE, "SELECT tenant FROM customers WHERE tenant = tenant")
            assertEquals(com.intellij.sql.dialects.mysql.MysqlDialect.INSTANCE, mapped.language)
            val tautology = com.intellij.sql.inspections.SqlConstantExpressionInspection()
                .checkFile(mapped, com.intellij.codeInspection.InspectionManager.getInstance(project), true)
                .orEmpty().map { it.descriptionTemplate }
            assertTrue("The real tautology inspection must run in its supported dialect: $tautology",
                tautology.any { it.contains("always 'true'") })
        } finally {
            manager.removeDataSource(source)
        }
    }
}
