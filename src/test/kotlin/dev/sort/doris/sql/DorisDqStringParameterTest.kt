package dev.sort.doris.sql

import com.intellij.database.settings.DatabaseSettings
import com.intellij.database.settings.UserPatterns
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.impl.DebugUtil
import com.intellij.sql.dialects.EvaluationHelper
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlLiteralExpression
import com.intellij.sql.psi.SqlTokens
import com.intellij.sql.psi.impl.lexer.SqlPreprocessingLexer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * DataGrip's `${name}` user parameters are also lexed inside string literals. That preprocessor
 * finds a SQL_STRING_TOKEN's content by its first `'`, so a Doris `"…"` string with a `'` after a
 * parameter produced descending token offsets and crashed the parser.
 */
class DorisDqStringParameterTest : BasePlatformTestCase() {
    private lateinit var saved: UserPatterns

    override fun setUp() {
        super.setUp()
        val settings = DatabaseSettings.getSettings()
        saved = settings.userPatterns
        settings.userPatterns = UserPatterns().apply {
            inScripts = true
            inLiterals = true
            processStrings = true
            patterns = mutableListOf(UserPatterns.ParameterPattern("\\$\\{([^\\{\\}]*)\\}", "", "\${name}").enabled(true, true))
        }
        settings.patternCache.clear()
    }

    override fun tearDown() {
        try {
            DatabaseSettings.getSettings().userPatterns = saved
            DatabaseSettings.getSettings().patternCache.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testParametersInsideDoubleQuotedStringsKeepTokenOrder() {
        for (sql in CASES + MISMATCHED_QUOTE) {
            val lexer = SqlPreprocessingLexer.withPreprocessingIfNeeded(
                DorisLexer(), DorisSqlDialect.INSTANCE, null, DatabaseSettings.getSettings(),
            )
            assertNotSame("the parameter preprocessor must be active", DorisLexer::class.java, lexer.javaClass)
            lexer.start(sql, 0, sql.length, 0)
            var offset = 0
            var parameters = 0
            while (lexer.tokenType != null) {
                assertEquals("$sql: tokens must be contiguous", offset, lexer.tokenStart)
                assertTrue("$sql: token ends before it starts at $offset", lexer.tokenEnd >= lexer.tokenStart)
                if (lexer.tokenType == SqlTokens.SQL_CUSTOM_PARAM_LQUOTE) parameters++
                offset = lexer.tokenEnd
                lexer.advance()
            }
            assertEquals(sql, sql.length, offset)
            assertEquals("$sql: the parameter is still recognised", 1, parameters)
        }
    }

    fun testParameterizedDoubleQuotedStringsParseAsLiterals() {
        for (replay in listOf("false", "true")) {
            System.setProperty(DorisReplay.PROPERTY, replay)
            try {
                for (sql in CASES) {
                    // Without replay, the MySQL grammar rejects Doris's FORMAT AS / PROPERTIES export options.
                    if (replay == "false" && "OUTFILE" in sql) continue
                    val file = PsiFileFactory.getInstance(project)
                        .createFileFromText("parameters.sql", DorisSqlDialect.INSTANCE, sql, false, true)!!
                    assertEmpty("replay=$replay $sql", PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
                    // Replay keeps export PROPERTIES values as tokens, without literal elements.
                    if ("OUTFILE" in sql) continue
                    val literal = PsiTreeUtil.findChildrenOfType(file, SqlLiteralExpression::class.java)
                        .singleOrNull { "\${p}" in it.text }
                    assertNotNull("replay=$replay $sql\n${DebugUtil.psiToString(file, true)}", literal)
                    literal!!
                    assertTrue("$sql: ${literal.text}", literal.text.startsWith("\"") && literal.text.endsWith("\""))
                }
            } finally {
                System.setProperty(DorisReplay.PROPERTY, "false")
            }
        }
    }

    fun testConsoleFindsParametersToPromptFor() {
        val helper = EvaluationHelper.EP.forLanguage(DorisSqlDialect.INSTANCE)
        for (replay in listOf("false", "true")) {
            System.setProperty(DorisReplay.PROPERTY, replay)
            try {
                for (sql in CASES + PROMPT_ONLY_CASES) {
                    val file = PsiFileFactory.getInstance(project)
                        .createFileFromText("parameters.sql", DorisSqlDialect.INSTANCE, sql, false, true)!!
                    val found = helper.parameters(null, DorisSqlDialect.INSTANCE, SyntaxTraverser.psiTraverser(file))
                        .toList().distinct().map { it.text }
                    assertEquals("replay=$replay $sql\n${DebugUtil.psiToString(file, true)}", listOf("\${p}"), found)
                }
            } finally {
                System.setProperty(DorisReplay.PROPERTY, "false")
            }
        }
    }

    private companion object {
        val PROMPT_ONLY_CASES = listOf(
            "select '\${p}' from t",
            "select * from t where a = 1 INTO OUTFILE \"s3://b/x_\" FORMAT AS PARQUET PROPERTIES (\n" +
                "    \"s3.secret_key\" = \"\${p}\",\n    \"use_path_style\" = \"true\"\n)",
            "EXPORT TABLE t TO \"s3://b/x_\" PROPERTIES (\"k\" = \"\${p}\")",
            "select * from t INTO OUTFILE 's3://b/x_' FORMAT AS PARQUET PROPERTIES ( 'a' = '\${p}' )",
            "CREATE CATALOG c PROPERTIES ( 'type' = 'hms', 'a' = '\${p}' )",
            "CREATE TABLE t (id INT) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES ( 'a' = '\${p}' )",
            "ALTER TABLE t SET ( 'a' = '\${p}' )",
        )
        // A `"` string closed by `'`, so the token runs to the next line's `"` (the reported crash).
        const val MISMATCHED_QUOTE = "select * from t INTO OUTFILE \"s3://b/x_\" FORMAT AS PARQUET PROPERTIES (\n" +
            "    \"k\" = \"\${p}',\n    \"u\" = \"true\"\n);"
        val CASES = listOf(
            "select \"\${p}'s\" from t",
            "select \"it's \${p}\" from t",
            "select \"\${p}\" from t",
            "select * from t INTO OUTFILE \"s3://b/x_\" FORMAT AS PARQUET PROPERTIES (\"k\" = \"\${p}\", \"u\" = \"true\")",
        )
    }
}
