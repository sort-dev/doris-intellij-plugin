package dev.sort.doris.sql

import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.completion.TablePositions
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.metadata.FunctionPresentation

class DorisTablePositionCompletionTest : BasePlatformTestCase() {
    private lateinit var catalog: DorisFunctionCatalogFixture
    private var counter = 0

    override fun setUp() {
        super.setUp()
        catalog = DorisFunctionCatalogFixture(testRootDisposable)
    }

    override fun tearDown() {
        RunAll(
            { catalog.errors.awaitScopes() },
            { catalog.errors.assertNone() },
            { setSqlDialectMapping(project, null, null) },
            { super.tearDown() },
        ).run()
    }

    private fun functions(sql: String): List<FunctionPresentation> {
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE)
        myFixture.configureByText("table-position-${counter++}.sql", sql)
        return myFixture.completeBasic().orEmpty().mapNotNull { it.`object` as? FunctionPresentation }
    }

    fun testFromOffersTableFunctionsAndGeneratorsButNotScalarFunctions() {
        assertEquals(TablePositions.of("doris"), DorisCompletionProfile.tablePositions)
        val entries = functions("SELECT * FROM ex<caret>")
        assertTrue(entries.toString(), entries.any { it.name.equals("explode", true) })
        assertTrue(entries.all { it.kind in setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING) })
        assertTrue(functions("SELECT * FROM s<caret>").any { it.name.equals("s3", true) })
    }

    fun testLateralViewOffersGeneratorsButNotScalarFunctions() {
        val entries = functions("SELECT * FROM events LATERAL VIEW ex<caret>")
        assertTrue(entries.toString(), entries.any { it.name.equals("explode", true) })
        assertTrue(entries.all { it.kind in setOf(FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING) })
        assertFalse(entries.any { it.name.equals("exp", true) || it.name.equals("extract", true) })
    }

    fun testSelectOffersGeneratorsAndScalarFunctionsButNotFromOnlyFunctions() {
        val entries = functions("SELECT ex<caret>")
        assertTrue(entries.toString(), entries.any { it.name.equals("explode", true) })
        assertTrue(entries.any { it.name.equals("exp", true) })
        assertFalse(entries.any { it.kind == FunctionKind.TABLE_VALUED })
        assertFalse(functions("SELECT s<caret>").any { it.name.equals("s3", true) })
    }
}
