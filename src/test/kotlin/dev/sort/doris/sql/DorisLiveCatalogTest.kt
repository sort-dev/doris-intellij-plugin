package dev.sort.doris.sql

import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.CatalogSource
import dev.brikk.house.sql.intellij.catalog.HarvestTrigger
import dev.brikk.house.sql.intellij.catalog.LiveMergePolicy
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionPresentation

class DorisLiveCatalogTest : BasePlatformTestCase() {
    private lateinit var catalog: DorisFunctionCatalogFixture

    override fun setUp() {
        super.setUp()
        catalog = DorisFunctionCatalogFixture(testRootDisposable)
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("catalog.sql", "SELECT <caret>")
    }

    override fun tearDown() = RunAll(
        { catalog.errors.awaitScopes() },
        { catalog.errors.assertNone() },
        { setSqlDialectMapping(project, null, null) },
        { super.tearDown() },
    ).run()

    fun testNoHarvestKeepsFullDorisBaseline() {
        assertEquals(DorisCompletionProfile.baseline, DorisCompletionProfile.functions(myFixture.file))
        assertTrue(DorisCompletionProfile.serverOnlyFunctions(myFixture.file).isEmpty())
        assertNull(DorisCompletionProfile.builtinFunctionFilter(myFixture.file))
        assertEquals(HarvestTrigger.ONCE_PER_SESSION, DorisLiveCatalogProfile.trigger)
        assertEquals(LiveMergePolicy.FILTER_AND_EXTEND, DorisLiveCatalogProfile.mergePolicy)
        assertNull(DorisLiveCatalogProfile.persistenceId)
    }

    fun testServerSetDropsAbsentAndAddsExtraWithoutReplacingSignatures() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            catalog.harvest(recording.console.dataSource, listOf("abs", "ABS", null, " ", "SERVER_ONLY_FN"))
            val live = catalog.service.completion(myFixture.file)
            assertInstanceOf(live.selection.source, CatalogSource.Live::class.java)
            assertEquals(false, live.selection.inventory?.signatures)
            assertEquals(2, live.selection.inventory?.functions?.size)
            assertEquals(DorisCompletionProfile.baseline.single { it.name.equals("abs", true) }, live.functions.single { it.name.equals("abs", true) })
            assertFalse(live.functions.any { it.name.equals("acos", true) })
            assertEquals(listOf("SERVER_ONLY_FN"), live.serverOnlyFunctions.map { it.name })
            val filter = checkNotNull(DorisCompletionProfile.builtinFunctionFilter(myFixture.file))
            assertFalse(filter("REGEXP_LIKE"))
            for (name in listOf("ABS", "CAST", "EXTRACT", "SERVER_ONLY_FN")) assertTrue(name, filter(name))
        }
    }

    fun testUnknownDataSourceFallsBackToBaseline() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            catalog.harvest(recording.console.dataSource, listOf("ABS"))
            recording.replaceDataSource()
            assertEquals(DorisCompletionProfile.baseline, DorisCompletionProfile.functions(myFixture.file))
            assertTrue(DorisCompletionProfile.serverOnlyFunctions(myFixture.file).isEmpty())
        }
    }

    fun testEmptyHarvestIsNotRecorded() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            catalog.harvest(recording.console.dataSource, emptyList())
            assertEquals(CatalogSource.Bundled, catalog.service.selection(recording.console.dataSource).source)
            assertEquals(DorisCompletionProfile.baseline, DorisCompletionProfile.functions(myFixture.file))
        }
    }

    fun testHarvestOncePerIdentityAndChangedIdentityFallsBack() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val source = recording.console.dataSource
            catalog.harvest(source, listOf("ABS"))
            assertEquals(0, catalog.harvest(source, listOf("ACOS")).executions)
            source.url = "jdbc:offline:other-server"
            assertEquals(DorisCompletionProfile.baseline, DorisCompletionProfile.functions(myFixture.file))
            assertEquals(1, catalog.harvest(source, listOf("ACOS")).executions)
            assertTrue(DorisCompletionProfile.functions(myFixture.file).any { it.name.equals("acos", true) })
            assertFalse(DorisCompletionProfile.functions(myFixture.file).any { it.name.equals("abs", true) })
        }
    }

    fun testHarvestTimeoutCancelsStatementAndLeavesCompletionOnBaseline() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            val query = catalog.harvest(recording.console.dataSource, listOf("ABS"), stalled = true)
            assertTrue("The deadline cancels the active statement", query.cancelled.get())
            assertTrue("The statement is closed before connect returns", query.closed.get())
            assertTrue(query.timeout in 1..5)
            assertEquals(CatalogSource.Bundled, catalog.service.selection(recording.console.dataSource).source)
            assertEquals(DorisCompletionProfile.baseline, DorisCompletionProfile.functions(myFixture.file))
            val names = myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }.map { it.name.lowercase() }
            assertContainsElements(names, "abs", "acos", "cast")
        }
    }

    fun testHarvestRetainsIcebergAndGrammarFormsAndRejectsNonQueryableExtras() {
        RecordingConsole(project, myFixture.file.virtualFile, "doris").use { recording ->
            catalog.harvest(recording.console.dataSource, listOf("ABS", "BINLOG", "GROUP_COMMIT", "HTTP_STREAM", "CDC_STREAM"))
            val functions = DorisCompletionProfile.functions(myFixture.file)
            assertContainsElements(functions.map { it.name.lowercase() }, "iceberg_meta", "cast", "extract")
            assertSame(DorisCompletionProfile.snapshot(myFixture.file), DorisCompletionProfile.snapshot(myFixture.file))
            assertEquals(DorisTableFunctions.all, DorisCompletionProfile.snapshot(myFixture.file).tableFunctions)
            assertTrue(DorisCompletionProfile.serverOnlyFunctions(myFixture.file).isEmpty())
        }
    }
}
