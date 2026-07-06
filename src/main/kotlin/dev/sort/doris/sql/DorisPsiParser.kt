/*
 * Portions of this file are adapted from StarRocks Support
 * (https://github.com/ycyz97/starrocks-datagrip-plugin), Copyright the StarRocks Support
 * contributors, licensed under the Apache License, Version 2.0. The statement-dispatch and
 * lenient-parsing approach (and helpers such as wordAt / statementContainsAny / lenient consume-to-
 * ';') derive from that project's StarRocksParser.kt and have been modified for Apache Doris syntax.
 * A copy of the Apache License 2.0 is at https://www.apache.org/licenses/LICENSE-2.0
 * See THIRD_PARTY_NOTICES.md.
 */
package dev.sort.doris.sql

import com.intellij.lang.PsiBuilder
import com.intellij.sql.dialects.mysql.MysqlParser
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_INSERT_STATEMENT
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_STATEMENT

/**
 * A lenient PSI parser for the DorisSQL language. It extends the platform's MySQL parser and only
 * overrides statement dispatch: when a statement leads with Doris-specific syntax that the MySQL
 * grammar mis-parses (INSERT OVERWRITE, CREATE ... DISTRIBUTED BY, materialized views, routine load,
 * SELECT * EXCEPT(...), ADMIN/SHOW variants, ...), it consumes tokens up to the next ';' and wraps
 * them in a single SQL_STATEMENT node. This keeps the console "run statement at caret" boundaries
 * correct — the one thing that cannot be fixed while delegating parsing wholesale.
 *
 * Everything the MySQL grammar CAN parse (plain SELECT/INSERT/UPDATE/DDL) still falls through to
 * super for full structure + completion. Adapted from the StarRocks DataGrip plugin's approach
 * (ycyz97/starrocks-datagrip-plugin), which works the same StarRocks/Doris-lineage syntax.
 *
 * Doris-accurate error reporting remains the fe-sql-parser annotator's job; this parser only fixes
 * statement scoping, so lenient statements intentionally carry no inner structure.
 */
class DorisPsiParser : MysqlParser(DorisSqlDialect.INSTANCE) {

    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean {
        if (isInsertOverwrite(builder)) {
            return parseInsertOverwrite(builder)
        }
        if (isDorisCreateTable(builder) || isCreateMaterializedView(builder) || isCreateView(builder) ||
            isCreateJob(builder)) {
            return parseLenientToQueryTail(builder, SQL_STATEMENT)
        }
        if (isDorisSpecificStatement(builder)) {
            return parseLenientStatement(builder, SQL_STATEMENT)
        }
        if (isQueryWithDorisOnlyClause(builder)) {
            return parseBoundedQuery(builder)
        }
        return super.parseSqlStatement(builder, level)
    }

    // --- dispatch predicates (bounded, non-consuming; all use mark/rollback) ---

    private fun isInsertOverwrite(builder: PsiBuilder): Boolean =
        wordAt(builder, 0) == "INSERT" && wordAt(builder, 1) == "OVERWRITE"

    private fun isCreateMaterializedView(builder: PsiBuilder): Boolean =
        wordAt(builder, 0) == "CREATE" && wordAt(builder, 1) == "MATERIALIZED" && wordAt(builder, 2) == "VIEW"

    /**
     * `CREATE [OR REPLACE] [options] VIEW ... AS <query>` (but not MATERIALIZED VIEW, handled above).
     * MySQL's view-body grammar is stricter than its query parser and chokes on modern Doris
     * constructs in the body (`REGEXP(...)`, `* EXCEPT(...)`, `FROM_SECOND(...)`, ...), which breaks
     * the statement boundary. Parsing the AS-tail with the real query parser instead keeps the block
     * whole with inner completion. The same query standalone already parses fine — only the CREATE
     * VIEW wrapper is affected.
     */
    private fun isCreateView(builder: PsiBuilder): Boolean {
        if (wordAt(builder, 0) != "CREATE") return false
        for (offset in 1..8) {
            when (wordAt(builder, offset)) {
                "VIEW" -> return wordAt(builder, offset - 1) != "MATERIALIZED"
                "AS", "SELECT", "WITH", null -> return false
            }
        }
        return false
    }

    /**
     * `CREATE JOB <name> ON SCHEDULE ... DO <statement>` (Doris scheduled job). MySQL doesn't know it,
     * so the DO-body statement (typically INSERT ... SELECT) gets parsed as its own statement and the
     * CREATE JOB prefix is orphaned — the run-block only covers the INSERT. Consume it as one
     * statement, parsing the trailing query for completion.
     */
    private fun isCreateJob(builder: PsiBuilder): Boolean =
        wordAt(builder, 0) == "CREATE" && wordAt(builder, 1) == "JOB"

    private fun isDorisCreateTable(builder: PsiBuilder): Boolean =
        wordAt(builder, 0) == "CREATE" &&
            createTableKeywordOffset(builder) != null &&
            statementContainsAny(builder, *DORIS_TABLE_CLAUSES)

    /**
     * SELECT/WITH queries containing a clause/expression the MySQL parser mis-parses — `* EXCEPT(col)`
     * column-exclusion, `QUALIFY`, a window function `OVER (...)`, or a reserved-word used as a
     * function (`IF(...)`, `REGEXP(...)`) which the MySQL grammar treats specially and which splits
     * the select list / statement boundary. All routed through [parseBoundedQuery], which parses for
     * real (structure + completion preserved) and only forces the boundary to ';'. Any resulting
     * parse-error element on the unknown construct is hidden by DorisHighlightErrorFilter.
     */
    private fun isQueryWithDorisOnlyClause(builder: PsiBuilder): Boolean =
        isQueryStart(builder) &&
            (containsWindowFunction(builder) ||
                containsExceptFollowedByParen(builder) ||
                containsKeywordFunctionCall(builder, "IF", "REGEXP") ||
                statementContainsAny(builder, "QUALIFY"))

    private fun isDorisSpecificStatement(builder: PsiBuilder): Boolean {
        return when (wordAt(builder, 0)) {
            "ADMIN", "BACKUP", "RESTORE", "RECOVER", "SYNC", "WARM" -> true
            "INSERT" -> wordAt(builder, 1) == "OVERWRITE"
            "REFRESH" -> true // REFRESH MATERIALIZED VIEW / TABLE / DATABASE / CATALOG — all Doris
            "PAUSE", "RESUME", "STOP" -> wordAt(builder, 1) in setOf("ROUTINE", "SYNC", "JOB")
            "CANCEL" -> true
            "CREATE" -> isDorisCreateStatement(builder)
            "ALTER" -> statementContainsAny(builder, "MATERIALIZED", "ROLLUP", "CATALOG", "RESOURCE",
                "DISTRIBUTION", "DISTRIBUTED", "BUCKETS", "PROPERTIES", "PARTITION", "WORKLOAD", "STORAGE")
            "DROP" -> wordAt(builder, 1) in setOf("MATERIALIZED", "RESOURCE", "CATALOG", "REPOSITORY",
                "WORKLOAD", "STORAGE", "ROUTINE", "ENCRYPTKEY", "SQL_BLOCK_RULE", "STAGE", "FILE")
            "SHOW" -> statementContainsAny(builder, "MATERIALIZED", "ROUTINE", "CATALOGS", "BACKENDS",
                "FRONTENDS", "PROC", "TABLET", "TABLETS", "PARTITIONS", "DYNAMIC", "STREAM", "WORKLOAD",
                "STORAGE", "STAGES", "DATA", "SYNC")
            "PARTITION", "DISTRIBUTED", "PROPERTIES", "ROLLUP", "DUPLICATE", "AGGREGATE", "UNIQUE" -> true
            else -> false
        }
    }

    private fun isDorisCreateStatement(builder: PsiBuilder): Boolean {
        val second = wordAt(builder, 1)
        val third = wordAt(builder, 2)
        if (second == "MATERIALIZED" && third == "VIEW") return true
        if (second == "ROUTINE" && third == "LOAD") return true
        // CREATE DATABASE ... PROPERTIES(...) (external/managed props) — MySQL splits at PROPERTIES.
        if (second == "DATABASE") return statementContainsAny(builder, "PROPERTIES")
        if (createTableKeywordOffset(builder) != null) {
            return statementContainsAny(builder, *DORIS_TABLE_CLAUSES)
        }
        return second in setOf("RESOURCE", "CATALOG", "REPOSITORY", "WORKLOAD", "STORAGE", "STAGE",
            "ENCRYPTKEY", "FILE", "SQL_BLOCK_RULE")
    }

    // --- lenient consumers ---

    private fun parseInsertOverwrite(builder: PsiBuilder): Boolean {
        val marker = builder.mark()
        consumeWord(builder, "INSERT")
        consumeWord(builder, "OVERWRITE")
        parseUntilQueryTail(builder)
        marker.done(SQL_INSERT_STATEMENT)
        return true
    }

    /** Consume the statement prefix leniently; when a SELECT/WITH tail appears, parse it for real. */
    private fun parseLenientToQueryTail(builder: PsiBuilder, done: com.intellij.psi.tree.IElementType): Boolean {
        val marker = builder.mark()
        parseUntilQueryTail(builder, "AS")
        marker.done(done)
        return true
    }

    private fun parseUntilQueryTail(builder: PsiBuilder, vararg introducers: String) {
        while (!builder.eof() && builder.tokenText != ";") {
            if (introducers.any { isCurrentWord(builder, it) }) {
                builder.advanceLexer()
                if (isQueryStart(builder)) parseQueryExpression(builder, 0)
                consumeToSemicolon(builder)
                return
            }
            if (isQueryStart(builder)) {
                parseQueryExpression(builder, 0)
                consumeToSemicolon(builder)
                return
            }
            builder.advanceLexer()
        }
    }

    private fun parseLenientStatement(builder: PsiBuilder, done: com.intellij.psi.tree.IElementType): Boolean {
        val marker = builder.mark()
        consumeToSemicolon(builder)
        marker.done(done)
        return true
    }

    /**
     * Parse a top-level query for real, but take over statement termination. Works around a platform
     * MySQL-parser quirk: a window function (`... OVER (...)`) placed on a new line after a select-list
     * comma makes the SELECT terminate early and splits the run-block (the same query on one line is
     * fine). Unlike [parseLenientStatement] this preserves inner structure/completion — parseQueryExpression
     * is the same query parser the working single-line case uses; we only force the boundary to the next ';'.
     */
    private fun parseBoundedQuery(builder: PsiBuilder): Boolean {
        val marker = builder.mark()
        parseQueryExpression(builder, 0)
        consumeToSemicolon(builder)
        marker.done(SQL_STATEMENT)
        return true
    }

    private fun consumeToSemicolon(builder: PsiBuilder) {
        while (!builder.eof() && builder.tokenText != ";") builder.advanceLexer()
    }

    // --- bounded look-ahead helpers (adapted from the StarRocks plugin) ---

    /** The uppercased Nth letter-leading token from the current position, or null. Non-consuming. */
    private fun wordAt(builder: PsiBuilder, offset: Int): String? {
        val marker = builder.mark()
        var current = 0
        var result: String? = null
        while (!builder.eof() && builder.tokenText != ";" && current <= offset) {
            val text = builder.tokenText
            if (text != null && text.firstOrNull()?.isLetter() == true) {
                if (current == offset) { result = text.uppercase(); break }
                current++
            }
            builder.advanceLexer()
        }
        marker.rollbackTo()
        return result
    }

    /** True if any of [words] (uppercase) appears before the next ';' within the look-ahead window. Non-consuming. */
    private fun statementContainsAny(builder: PsiBuilder, vararg words: String): Boolean {
        val expected = words.toHashSet()
        val marker = builder.mark()
        var scanned = 0
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text != null && expected.contains(text.uppercase())) { found = true; break }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /** True if an `OVER` token is followed by `(` before the next ';' — a window function. Non-consuming. */
    private fun containsWindowFunction(builder: PsiBuilder): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var sawOver = false
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text != null && text.isNotBlank()) {
                if (sawOver && text == "(") { found = true; break }
                sawOver = text.equals("OVER", ignoreCase = true)
            }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /**
     * True if any of [keywords] (uppercase) appears immediately followed by `(` before the next ';' —
     * a reserved word used as a function (`IF(...)`, `REGEXP(...)`) that the MySQL grammar handles
     * specially and mis-parses in a select list. Non-consuming.
     */
    private fun containsKeywordFunctionCall(builder: PsiBuilder, vararg keywords: String): Boolean {
        val expected = keywords.toHashSet()
        val marker = builder.mark()
        var scanned = 0
        var prev: String? = null
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text != null && text.isNotBlank()) {
                if (text == "(" && prev != null && expected.contains(prev)) { found = true; break }
                prev = text.uppercase()
            }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /** True if an `EXCEPT` token is immediately followed by `(` before the next ';'. Non-consuming. */
    private fun containsExceptFollowedByParen(builder: PsiBuilder): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var sawExcept = false
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text != null && text.isNotBlank()) {
                if (sawExcept && text == "(") { found = true; break }
                sawExcept = text.equals("EXCEPT", ignoreCase = true)
            }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    private fun createTableKeywordOffset(builder: PsiBuilder): Int? {
        if (wordAt(builder, 0) != "CREATE") return null
        for (offset in 1..3) {
            val word = wordAt(builder, offset) ?: return null
            if (word == "TABLE") return offset
            if (word !in CREATE_TABLE_MODIFIERS) return null
        }
        return null
    }

    private fun consumeWord(builder: PsiBuilder, expected: String): Boolean {
        if (!builder.tokenText.equals(expected, ignoreCase = true)) return false
        builder.advanceLexer()
        return true
    }

    private fun isCurrentWord(builder: PsiBuilder, expected: String): Boolean =
        builder.tokenText.equals(expected, ignoreCase = true)

    private fun isQueryStart(builder: PsiBuilder): Boolean =
        isCurrentWord(builder, "SELECT") || isCurrentWord(builder, "WITH")

    private companion object {
        const val MAX_LOOKAHEAD = 512
        val CREATE_TABLE_MODIFIERS = setOf("TEMPORARY", "EXTERNAL")
        val DORIS_TABLE_CLAUSES = arrayOf(
            "DISTRIBUTED", "BUCKETS", "PROPERTIES", "DUPLICATE", "AGGREGATE", "UNIQUE", "PRIMARY",
            "PARTITION", "ROLLUP", "ENGINE", "RANDOM", "AUTO"
        )
    }
}
