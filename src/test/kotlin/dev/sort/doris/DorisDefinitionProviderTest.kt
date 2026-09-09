package dev.sort.doris

import com.intellij.database.Dbms
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.dialects.mysql.model.MysqlRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicSourceAware
import com.intellij.database.model.properties.CompositeText
import com.intellij.database.util.ObjectNamePart
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.apache.doris.sqlparser.DorisSqlParser

class DorisDefinitionProviderTest : BasePlatformTestCase() {
    private val provider = DorisDefinitionProvider()

    fun testCatalogModePreservesFullObjectIdentity() {
        val model = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)
        val root = model.root as MsRoot
        val internal = root.databases.createOrGet("internal")
        val external = root.databases.createOrGet("hive")
        val internalSales = internal.schemas.createOrGet("sales")
        val externalSales = external.schemas.createOrGet("sales")
        val internalTable = internalSales.tables.createOrGet("orders")
        val externalTable = externalSales.tables.createOrGet("orders")
        val internalView = internalSales.views.createOrGet("orders_v")
        val externalView = externalSales.views.createOrGet("orders_v")

        assertEquals("SHOW CREATE CATALOG `hive`", provider.buildShowCreateSql(external))
        assertEquals("SHOW CREATE DATABASE `hive`.`sales`", provider.buildShowCreateSql(externalSales))
        assertEquals("SHOW CREATE TABLE `internal`.`sales`.`orders`", provider.buildShowCreateSql(internalTable))
        assertEquals("SHOW CREATE TABLE `hive`.`sales`.`orders`", provider.buildShowCreateSql(externalTable))
        assertEquals("SHOW CREATE VIEW `internal`.`sales`.`orders_v`", provider.buildShowCreateSql(internalView))
        assertEquals("SHOW CREATE TABLE `hive`.`sales`.`orders_v`", provider.buildShowCreateSql(externalView))

        val parser = DorisSqlParser()
        listOf(external, externalSales, externalTable, externalView).forEach {
            parser.parseStatement(provider.buildShowCreateSql(it))
        }
    }

    fun testEveryIdentifierComponentIsQuotedIndependently() {
        val model = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)
        val catalog = (model.root as MsRoot).databases.createOrGet("we`ird")
        val database = catalog.schemas.createOrGet("db`x")
        val table = database.tables.createOrGet("or`ders")
        assertEquals(
            "SHOW CREATE TABLE `we``ird`.`db``x`.`or``ders`",
            provider.buildShowCreateSql(table),
        )
    }

    fun testFlatMysqlModelRetainsSchemaQualifiedForms() {
        val model = ModelFactory(NoopStorage()).createModel(Dbms.MYSQL)
        val schema = (model.root as MysqlRoot).schemas.createOrGet("sales")
        val table = schema.tables.createOrGet("orders")
        val view = schema.views.createOrGet("orders_v")
        assertEquals("SHOW CREATE DATABASE `sales`", provider.buildShowCreateSql(schema))
        assertEquals("SHOW CREATE TABLE `sales`.`orders`", provider.buildShowCreateSql(table))
        assertEquals("SHOW CREATE VIEW `sales`.`orders_v`", provider.buildShowCreateSql(view))
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
