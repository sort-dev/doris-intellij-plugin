package dev.sort.doris.sql

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.settings.UserPatterns
import com.intellij.sql.psi.SqlTokens
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.lexAll
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

class DorisMaskedParameterExecutionTest : BasePlatformTestCase() {
    private lateinit var previousPatterns: UserPatterns

    override fun setUp() {
        super.setUp()
        val settings = DatabaseSettings.getSettings()
        previousPatterns = settings.userPatterns
        settings.userPatterns = UserPatterns().apply {
            inScripts = true
            inLiterals = true
            processStrings = true
            patterns = listOf(UserPatterns.ParameterPattern("\\$\\{(\\w+)\\}").enabled(true, true))
        }
        settings.patternCache.clear()
    }

    override fun tearDown() {
        try {
            val settings = DatabaseSettings.getSettings()
            settings.userPatterns = previousPatterns
            settings.patternCache.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testMaskedSqlParametersSubmitTheWholeStatementThroughConsolePreprocessing() {
        val statements = listOf(
            "SELECT CAST(:id AS STRING) AS value FROM offline_rows WHERE id > :id ORDER BY id LIMIT 7",
            "SELECT CAST('\${id}' AS STRING) AS value FROM offline_rows WHERE id = :id ORDER BY id LIMIT 7",
            "SELECT * EXCEPT(secret_col) FROM offline_rows WHERE id = :id ORDER BY id LIMIT 7",
            "INSERT OVERWRITE TABLE archived TEMPORARY PARTITION(p1) " +
                "SELECT id FROM offline_rows WHERE id = :id ORDER BY id LIMIT 7",
        )
        for ((index, sql) in statements.withIndex()) {
            assertTrue(sql, DorisLexer().lexAll(sql).any { it.type == SqlTokens.SQL_BLOCK_COMMENT })
            val file = myFixture.configureByText("masked-parameters$index.sql", sql)
            setSqlDialectMapping(project, file.virtualFile, DorisSqlDialect.INSTANCE, testRootDisposable)
            RecordingConsole(project, file.virtualFile, "doris").use { recording ->
                val console = recording.console
                ShowSqlParametersPanelAction.getStorage(console).putValue("id", "17")
                val model = console.scriptModel
                assertTrue(console.beforeExecuteQueries(model))
                console.executeQueries(myFixture.editor, model, DatabaseSettings.ExecOption())
                val request = recording.requests.single() as DataRequest.QueryRequest
                assertEquals(sql.replace(":id", "17").replace("\${id}", "17"), request.query)
                assertSame(console, request.owner)
                assertEquals(sql, myFixture.editor.document.text)
            }
        }
    }
}
