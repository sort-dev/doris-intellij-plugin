package dev.sort.doris.catalog

import com.intellij.database.actions.ddl.CreateObjectAction
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mysqlbase.generator.MysqlBaseScriptGenerator
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicSourceAware
import com.intellij.database.model.meta.BasicMetaId
import com.intellij.database.model.properties.CompositeText
import com.intellij.database.script.generator.ScriptCategory
import com.intellij.database.script.generator.ScriptGenerators
import com.intellij.database.script.generator.ScriptingSingleModelTaskBuilder
import com.intellij.database.util.ObjectNamePart
import com.intellij.database.util.DatabaseDefinitionHelper
import com.intellij.database.view.DropQueryGenerator
import com.intellij.database.view.RenameQueryGenerator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms

/** B4 containment: catalog nodes must never reach SQL Server's script producers. */
class DorisScriptGeneratorTest : BasePlatformTestCase() {
    private var previous: String? = null

    override fun setUp() {
        super.setUp()
        previous = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
    }

    override fun tearDown() {
        try {
            if (previous == null) System.clearProperty(DorisCatalogs.PROPERTY)
            else System.setProperty(DorisCatalogs.PROPERTY, previous!!)
        } finally {
            super.tearDown()
        }
    }

    fun testCatalogModeDisablesEveryScriptCapabilityAndPlatformAction() {
        val model = newModel()
        val elements = modelElements(model)
        val generator = ScriptGenerators.byModel(model)
        assertTrue(generator is DorisScriptGenerator)
        for (element in elements) {
            val capabilities = generator.capabilities(element)
            val version = model.root.serverVersion
            val allCapabilities: List<com.intellij.database.script.generator.ScriptingCapabilities.VersionedCapability<Boolean>> = listOf(
                capabilities.create, capabilities.createAlone, capabilities.drop, capabilities.rename,
                capabilities.comment, capabilities.alterComment, capabilities.createOrdered,
                capabilities.alterOrder, capabilities.alterAnything, capabilities.truncate,
                capabilities.refresh, capabilities.recompile,
            )
            for (capability in allCapabilities) assertFalse("${element.kind} exposes a DDL capability", capability.get(version))
            for (category in ScriptCategory.entries) assertFalse("${element.kind}: $category", capabilities.get(category).get(version))
            assertFalse(capabilities.canCreateWith(BasicMetaId.create("child")).get(version))
            assertFalse(capabilities.canAlter(BasicMetaId.create("property")).get(version))
            assertFalse(capabilities.isConditional(BasicMetaId.create("conditional")))
            assertTrue(capabilities.edgeVersions.none())
            assertFalse(CreateObjectAction.isSupported(element, true))
            assertFalse(DropQueryGenerator.canDeleteAnything(model, listOf(element)))
            if (element is com.intellij.database.model.basic.BasicNamedElement) {
                assertFalse(RenameQueryGenerator.canRename(model, element, version))
            }
        }
    }

    fun testDirectDropScriptCallsFailExplicitlyAtEveryObjectLevel() {
        val model = newModel()
        val generator = ScriptGenerators.byModel(model)
        for (element in modelElements(model)) {
            // DROP_COMPLETE applies to every tested object level; capability tests above cover
            // every category without constructing platform-invalid task/category combinations.
            val task = ScriptingSingleModelTaskBuilder(model, ScriptCategory.DROP_COMPLETE).apply {
                addElements(listOf(element))
            }.build()
            val error = try {
                generator.makeScript(project, task)
                fail("Expected catalog-mode script generation to fail")
                error("unreachable")
            } catch (error: UnsupportedOperationException) {
                error
            }
            assertTrue(error.message, error.message!!.contains("Doris catalog-mode"))
            assertTrue(error.message, error.message!!.contains(ScriptCategory.DROP_COMPLETE.displayName))
            assertTrue(error.message, error.message!!.contains("cannot preserve Doris DDL semantics"))
            assertNull(generator.availableOptions(
                ScriptingSingleModelTaskBuilder(model, ScriptCategory.CREATE_COMPLETE).apply {
                    addElements(listOf(element))
                }.build(),
            ))
            if (element is BasicSourceAware) assertNull(generator.reviseSource(project, element))
        }
    }

    fun testDocumentationDefinitionReturnsNoSourceWithoutThrowing() {
        val model = newModel()
        val table = modelElements(model).filterIsInstance<com.intellij.database.model.basic.BasicTable>().single()
        val definition = DatabaseDefinitionHelper.generateDefinitionUsingScriptingService(project, table, null)
        assertEquals("-- No source text available\n", definition)

        val task = ScriptingSingleModelTaskBuilder(model, ScriptCategory.CREATE_DEFINITION).apply {
            addElements(listOf(table))
        }.build()
        val result = ScriptGenerators.byModel(model).makeScript(project, task)
        assertEmpty(result.getScriptStatements())
        assertEmpty(result.getScriptStatementsTexts())
        assertEquals("", result.getScriptText())
        assertEquals("", result.getScript().text)
    }

    fun testFlatModeStillUsesTheMysqlGenerator() {
        System.setProperty(DorisCatalogs.PROPERTY, "false")
        val generator = DorisScriptGenerator(DorisDbms.DORIS)
        assertTrue(generator.implementation is MysqlBaseScriptGenerator)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        val catalogGenerator = DorisScriptGenerator(DorisDbms.DORIS)
        assertFalse(catalogGenerator.implementation is MysqlBaseScriptGenerator)
    }

    private fun newModel(): BasicModModel = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)

    private fun modelElements(model: BasicModModel): List<BasicElement> {
        val root = model.root as MsRoot
        val catalog = root.databases.createOrGet("external`catalog")
        val database = catalog.schemas.createOrGet("database`name")
        val table = database.tables.createOrGet("table`name")
        val column = table.columns.createOrGet("column`name")
        val view = database.views.createOrGet("view`name")
        val index = table.indices.createOrGet("index`name")
        return listOf(catalog, database, table, column, view, index)
    }

    private class NoopStorage : ModelTextStorage {
        override fun handleRename(element: BasicElement, oldName: ObjectNamePart) = Unit
        override fun save(element: BasicSourceAware, text: CompositeText?) = Unit
        override fun queueDelete(element: BasicElement) = Unit
        override fun load(element: BasicSourceAware): CompositeText? = null
        override fun getVersion(element: BasicElement): Long? = null
        override fun setVersion(element: BasicElement, version: Long?) = Unit
        override fun writeSession(model: BasicModModel, runnable: Runnable) = runnable.run()
        override fun flushQueues() = Unit
        override fun clear() = Unit
    }
}
