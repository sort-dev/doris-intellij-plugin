package dev.sort.doris.brikk

import com.intellij.facet.FacetManager
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.intellij.ContextResult
import dev.brikk.house.intellij.ShapeKnowledge
import dev.brikk.house.intellij.ShapeResult
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.config.KotlinCompilerVersion
import org.jetbrains.kotlin.idea.facet.KotlinFacetType
import org.jetbrains.kotlin.idea.fir.extensions.KotlinBundledFirCompilerPluginProvider
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.nio.file.Path
import java.util.Properties
import java.util.zip.ZipFile

/** No handwritten generated rows and no runtime schema inference. */
class BrikkDorisGeneratedShapeTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        assertEquals("Generated lane requires the exact reviewed IDE compiler", "2.4.20-ij262-34", KotlinCompilerVersion.VERSION)
        val jar = Path.of(System.getProperty("brikk.fixture.compiler.plugin").takeIf { !it.isNullOrBlank() }
            ?: error("Set -Ptest.brikk.compilerPlugin to the genuine reviewed IDE compiler-plugin JAR"))
        val stamp = ZipFile(jar.toFile()).use { zip ->
            Properties().apply { zip.getInputStream(zip.getEntry("META-INF/brikk-engine-kotlin-compiler-plugin.build")!!).use(::load) }
        }
        assertTrue("Artifact stamp must identify the reviewed compiler: $stamp", stamp.values.any { it.toString() == "2.4.20-ij262-34" })
        val ep = ExtensionPointName.create<KotlinBundledFirCompilerPluginProvider>("org.jetbrains.kotlin.bundledFirCompilerPluginProvider")
        ep.point.registerExtension(object : KotlinBundledFirCompilerPluginProvider {
            override fun provideBundledPluginJar(project: Project, userSuppliedPluginJar: Path): Path? = jar.takeIf { it == userSuppliedPluginJar }
        }, testRootDisposable)
        val jdk = JavaSdk.getInstance().createJdk("Brikk generated fixture", System.getProperty("java.home"), false)
        WriteCommandAction.runWriteCommandAction(project) { ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable) }
        ModuleRootModificationUtil.setModuleSdk(module, jdk)
        val stdlib = java.io.File(com.intellij.util.PathUtil.getJarPathForClass(Unit::class.java))
        PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        WriteCommandAction.runWriteCommandAction(project) {
            val manager = FacetManager.getInstance(module)
            val facet = manager.createFacet(KotlinFacetType.INSTANCE, "Kotlin", null)
            facet.configuration.settings.useProjectSettings = false
            facet.configuration.settings.compilerArguments = K2JVMCompilerArguments().apply { pluginClasspaths = arrayOf(jar.toString()) }
            manager.createModifiableModel().apply { addFacet(facet); commit() }
        }
        myFixture.addFileToProject("org/intellij/lang/annotations/Language.java", """
            package org.intellij.lang.annotations;
            public @interface Language { String value(); String prefix() default ""; String suffix() default ""; }
        """.trimIndent())
        myFixture.addFileToProject("Runtime.kt", """
            package dev.brikk.house.sql.runtime
            annotation class BrikkSql
            annotation class BrikkSqlDialect(val dialect: String)
            annotation class BrikkTrait
            interface Partial
            interface Shape : Partial
            class Rel<out T : Partial>
            object Sql {
                @BrikkSqlDialect("doris")
                fun doris(@org.intellij.lang.annotations.Language("DorisSQL") sql: String): Rel<Nothing> = error("compiler")
            }
        """.trimIndent())
    }

    fun testGenuineGeneratedInputIsVisibleInTheDorisConsumerOnColdOpen() {
        val file = myFixture.configureByText("Views.kt", """
            import dev.brikk.house.sql.runtime.*
            @BrikkSql fun source() = Sql.doris("SELECT 1 AS id, 'x' AS code")
            @BrikkSql fun query(rows: Rel<SourceOut>) = Sql.doris("SELECT id FROM ${'$'}rows()")
        """.trimIndent())
        val host = PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java).last()
        val result = BrikkDorisTemplate.inspect(host, includeShapes = true)
        assertTrue(result.toString(), result is ContextResult.Available)
        val input = (result as ContextResult.Available).context.relations.getValue("rows")
        assertTrue(input.toString(), input is ShapeResult.Available)
        val shape = (input as ShapeResult.Available).shape
        assertEquals(ShapeKnowledge.FULL, shape.knowledge)
        assertEquals(setOf("id", "code"), shape.columns.map { it.name }.toSet())
        val sql = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host)!!.first().first.containingFile
        assertTrue(BrikkDorisContext.isActive(sql))
        assertEquals("SELECT id FROM rows()", sql.text)
        val call = PsiTreeUtil.findChildOfType(sql, com.intellij.sql.psi.SqlFunctionCallExpression::class.java)!!
        val type = call.dasType as? com.intellij.sql.psi.SqlTableType
        assertNotNull("Genuine generated input must feed the SQL table type", type)
        assertEquals(setOf("id", "code"), (0 until type!!.columnCount).map { type.getColumnName(it) }.toSet())
        val ref = PsiTreeUtil.findChildrenOfType(sql, com.intellij.sql.psi.SqlReferenceExpression::class.java).single { it.text == "id" }
        assertNotNull(ref.resolve())
    }
}
