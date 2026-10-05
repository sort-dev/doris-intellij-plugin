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
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/** Run with -Ptest.brikkKotlin=true. Exercises the actual Kotlin injection extension chain. */
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
        val sql = injected(host)
        assertEquals("SELECT :__brikk_unsupported, :__brikk_unsupported FROM customers", sql.text)
        assertTrue(BrikkDorisTemplate.classify(host.entries.mapNotNull { it.expression }.first()).problem!!.contains("literal initializer"))
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
        val sql = injected(configure("SELECT tenant FROM customers WHERE tenant = :tenant OR tenant = ${'$'}missing"))
        assertEquals("SELECT tenant FROM customers WHERE tenant = :tenant OR tenant = :__brikk_pending", sql.text)
        assertEquals(2, PsiTreeUtil.findChildrenOfType(sql, SqlParameter::class.java).size)
    }

    fun testTemporarilyUnresolvedSymbolRetriesWithoutTemplateEdit() {
        val host = configure("SELECT ${'$'}later")
        val pending = injected(host)
        assertEquals("SELECT :__brikk_pending", pending.text)
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
        assertEquals("SELECT '${'$'}literal', '${'$'}.path', 'λ' FROM customers\nWHERE tenant = :tenant", input.text)
        assertTrue(annotator.doAnnotate(input).errors.toString(), annotator.doAnnotate(input).errors.isEmpty())
        val where = sql.text.indexOf("WHERE")
        assertEquals(host.textRange.startOffset + host.text.indexOf("WHERE"),
            InjectedLanguageManager.getInstance(project)
                .injectedToHost(sql, com.intellij.openapi.util.TextRange(where, where + 5)).startOffset)
    }

    fun testUnsupportedInterpolationHasHostDiagnosticAndNonEditableSqlPlaceholder() {
        val host = configure("SELECT ${'$'}{tenant.lowercase()}")
        val sql = injected(host)
        val start = sql.text.indexOf(":__brikk_unsupported")
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
        com.intellij.sql.dialects.SqlDialectMappings.getInstance(project).setMapping(ddl.virtualFile, DorisSqlDialect.INSTANCE)
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
