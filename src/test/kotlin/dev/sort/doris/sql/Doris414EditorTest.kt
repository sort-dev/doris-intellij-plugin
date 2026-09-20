package dev.sort.doris.sql

import com.intellij.database.console.JdbcConsoleProvider
import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.sort.doris.setSqlDialectMapping
import org.apache.doris.sqlparser.DorisSqlParser

/** Exercise the shipped grammar and the platform's caret execution chooser together. */
class Doris414EditorTest : BasePlatformTestCase() {
    fun testReleaseSyntaxKeepsCompleteExecutionRanges() {
        val oldReplay = System.getProperty(DorisReplay.PROPERTY)
        val failures = mutableListOf<String>()
        try {
            System.clearProperty(DorisReplay.PROPERTY)
            for (sql in listOf(
                "UPDATE t SET c = DEFAULT(c)",
                "INSERT INTO t (id, c) VALUES (1, DEFAULT(c)), (2, DEFAULT)",
                "ALTER TABLE t ADD COLUMN s.b INT NULL",
                "ALTER TABLE t ADD COLUMN arr.element.b INT NULL AFTER a",
                "ALTER TABLE t MODIFY COLUMN s.b BIGINT",
                "ALTER TABLE t MODIFY COLUMN s.b COMMENT ''",
                "ADMIN COMPACT TABLET 12345 WHERE TYPE = 'CUMULATIVE'",
                "SHOW COMPUTE GROUPS",
                "SELECT CAST('2024-01-15 12:00:00 +00:00' AS TIMESTAMPTZ(6))",
                "CREATE TABLE t (ts TIMESTAMPTZ(6))",
                "SELECT PARSE_TO_VARIANT('{\"k\":1}'), TRY_PARSE_TO_VARIANT('{')",
                "SELECT * FROM VECTOR_SEARCH('table'='lance.db.t', 'column'='embedding', 'query_vector'='[0,0]')",
            )) {
                DorisSqlParser().parseStatement(sql)
                val prefix = "SELECT 0;\n"
                val text = "$prefix$sql;\nSELECT 99;"
                val errors = DorisErrorAnnotator().doAnnotate(DorisErrorAnnotator.Input(text, false)).errors
                if (errors.isNotEmpty()) failures.add("Diagnostics for $sql: $errors")
                val original = myFixture.configureByText("doris414.sql", text)
                setSqlDialectMapping(project, original.virtualFile, DorisSqlDialect.INSTANCE)
                val file = PsiManager.getInstance(project).findFile(original.virtualFile)!!
                assertSame(DorisSqlDialect.INSTANCE, file.language)
                val expected = listOf(TextRange(prefix.length, prefix.length + sql.length) to sql)
                for (caret in listOf(prefix.length + 1, prefix.length + sql.length / 2, prefix.length + sql.length - 1)) {
                    myFixture.editor.caretModel.moveToOffset(caret)
                    val option = DatabaseSettings.ExecOption().apply { execInside = DatabaseSettings.EXECUTE_INSIDE_LARGEST }
                    val element = JdbcConsoleProvider.elementAt(file, null, myFixture.editor)!!
                    val info = JdbcConsoleProvider.findScriptModel(file, element, myFixture.editor, option)!!
                    var actual: List<Pair<TextRange, String>>? = null
                    JdbcConsoleProvider.chooseStatements(info, null, false) { selected ->
                        actual = selected.statements().map {
                            it.range().shiftRight(it.rangeOffset().toInt()) to it.query()
                        }.toList()
                    }
                    if (expected != actual) failures.add("caret=$caret: $sql\nselected=$actual")
                }
            }
        } finally {
            if (oldReplay == null) System.clearProperty(DorisReplay.PROPERTY)
            else System.setProperty(DorisReplay.PROPERTY, oldReplay)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
