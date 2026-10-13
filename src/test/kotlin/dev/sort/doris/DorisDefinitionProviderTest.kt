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
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.PairConsumer
import org.apache.doris.sqlparser.DorisSqlParser

class DorisDefinitionProviderTest : BasePlatformTestCase() {
    private val provider = DorisDefinitionProvider()
    private fun query(obj: com.intellij.database.model.DasObject) = checkNotNull(provider.query(obj)).sql

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

        assertEquals("SHOW CREATE CATALOG `hive`", query(external))
        assertEquals("SHOW CREATE DATABASE `hive`.`sales`", query(externalSales))
        assertEquals("SHOW CREATE TABLE `internal`.`sales`.`orders`", query(internalTable))
        assertEquals("SHOW CREATE TABLE `hive`.`sales`.`orders`", query(externalTable))
        assertEquals("SHOW CREATE VIEW `internal`.`sales`.`orders_v`", query(internalView))
        assertEquals("SHOW CREATE TABLE `hive`.`sales`.`orders_v`", query(externalView))

        val parser = DorisSqlParser()
        listOf(external, externalSales, externalTable, externalView).forEach {
            parser.parseStatement(query(it))
        }
    }

    fun testEveryIdentifierComponentIsQuotedIndependently() {
        val model = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)
        val catalog = (model.root as MsRoot).databases.createOrGet("we`ird")
        val database = catalog.schemas.createOrGet("db`x")
        val table = database.tables.createOrGet("or`ders")
        assertEquals(
            "SHOW CREATE TABLE `we``ird`.`db``x`.`or``ders`",
            query(table),
        )
    }

    fun testFlatMysqlModelRetainsSchemaQualifiedForms() {
        val model = ModelFactory(NoopStorage()).createModel(Dbms.MYSQL)
        val schema = (model.root as MysqlRoot).schemas.createOrGet("sales")
        val table = schema.tables.createOrGet("orders")
        val view = schema.views.createOrGet("orders_v")
        assertEquals("SHOW CREATE DATABASE `sales`", query(schema))
        assertEquals("SHOW CREATE TABLE `sales`.`orders`", query(table))
        assertEquals("SHOW CREATE VIEW `sales`.`orders_v`", query(view))
    }

    fun testCancellationEscapesSourceConsumption() {
        val model = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)
        val table = (model.root as MsRoot).databases.createOrGet("internal")
            .schemas.createOrGet("sales").tables.createOrGet("orders")
        val cancellation = ProcessCanceledException()
        val consumed = StringBuilder()

        val rows = dev.sort.doris.testing.RemoteRowsFixture().apply { query = { throw cancellation } }
        try {
            provider.load(listOf(table), rows.connection, consumed)
            fail("expected ProcessCanceledException")
        } catch (thrown: ProcessCanceledException) {
            assertSame(cancellation, thrown)
        }
        assertTrue("cancellation must not be delivered as an object definition", consumed.isEmpty())
        assertEquals(1, rows.statementsClosed.get())
    }

    fun testSharedProviderReadsCreateColumnAndClosesResults() {
        val model = ModelFactory(NoopStorage()).createModel(DorisDbms.DORIS)
        val table = (model.root as MsRoot).databases.createOrGet("internal")
            .schemas.createOrGet("sales").tables.createOrGet("orders")
        val rows = dev.sort.doris.testing.RemoteRowsFixture().apply {
            query = { sql ->
                assertEquals("SHOW CREATE TABLE `internal`.`sales`.`orders`", sql)
                listOf("Table", "Create Table") to listOf(listOf("orders", "CREATE TABLE orders(id INT)"))
            }
        }
        val consumed = StringBuilder()
        provider.load(listOf(table), rows.connection, consumed)
        assertTrue(consumed.toString(), consumed.contains("CREATE TABLE orders(id INT)"))
        assertEquals(1, rows.resultsClosed.get())
        assertEquals(1, rows.statementsClosed.get())
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
