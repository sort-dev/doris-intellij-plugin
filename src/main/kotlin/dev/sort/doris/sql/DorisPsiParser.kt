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
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_STATEMENT
import dev.sort.doris.sql.replay.CstReplayer

/**
 * A lenient PSI parser for the DorisSQL language. It extends the platform's MySQL parser and only
 * overrides statement dispatch: when a statement leads with Doris-specific syntax that the MySQL
 * grammar mis-parses (CREATE ... DISTRIBUTED BY, materialized views, routine load, CREATE JOB,
 * ADMIN/SHOW/REFRESH/WARM UP variants, QUALIFY, ...), it consumes tokens up to the next ';' and
 * wraps them in a single SQL_STATEMENT node, parsing any trailing SELECT/WITH for real. This keeps
 * "run statement at caret" boundaries correct for statements the grammar cannot represent.
 *
 * Everything else falls through to super for full structure + completion. Token-level Doris-isms
 * (`* EXCEPT(...)`, INSERT OVERWRITE headers, Doris-only CAST targets, `REGEXP(...)`) are handled
 * upstream in [DorisLexer], and the dialect's builtin-function map is MySQL's (see
 * [DorisSqlDialect.createTokensHelper]) — both are prerequisites for the grammar to work at all.
 * Adapted from the StarRocks DataGrip plugin's approach (ycyz97/starrocks-datagrip-plugin).
 *
 * Doris-accurate error reporting remains the fe-sql-parser annotator's job; lenient statements
 * intentionally carry no inner structure beyond the parsed query tail.
 */
class DorisPsiParser : MysqlParser(DorisSqlDialect.INSTANCE) {

    override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean {
        // Route B (RESEARCH-when-hell-freezes-over-parser.md), ON BY DEFAULT since 0.5.0
        // (-Ddoris.replay.poc=false to disable): replay the authoritative Doris ANTLR CST onto the
        // platform token stream, producing REAL typed PSI for the query family (SELECT / WITH / QUALIFY)
        // and Doris statement leads (CREATE [MATERIALIZED] VIEW, Doris CREATE TABLE, REFRESH / WARM UP /
        // SWITCH). On ANY ANTLR error, boundary misalignment, or greed mismatch the replayer rolls back
        // and consumes nothing, so we fall through to the unchanged lenient/delegation logic below —
        // behaviour with the flag disabled is byte-for-byte identical to pre-0.5.0.
        // DORIS PIPES: a GoogleSQL pipe program (`FROM t |> WHERE ... |> ...`)
        // has no MySQL shape at all — unhandled, the grammar shreds it into fragments, so the
        // statement bounding box / statement-under-caret / gutter anchors all break (observed in
        // dogfood round 2). One statement node to the ';' restores all of those; inner structure
        // stays deliberately absent (engine-side validation via DorisErrorAnnotator).
        if (dev.sort.doris.pipes.DorisPipes.enabled && containsPipeMarker(builder)) {
            return parseLenientStatement(builder, SQL_STATEMENT)
        }
        if (DorisReplay.enabled && wantsReplay(builder)) {
            if (CstReplayer(builder, this).tryReplayStatement()) return true
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

    /**
     * True iff the statement at the cursor is one the Route B replayer attempts. A superset gate: the
     * replayer itself is the real arbiter (it rolls back cleanly on anything it cannot shape), so this
     * only needs to avoid waking it for statements it never handles. Query leads (SELECT / WITH, incl.
     * QUALIFY queries) plus the Doris statement leads with a CST->PSI mapping today.
     */
    private fun wantsReplay(builder: PsiBuilder): Boolean {
        if (wordAt(builder, 0) in REPLAY_QUERY_LEADS) return true
        if (wordAt(builder, 0) in REPLAY_STATEMENT_LEADS) return true
        return isCreateView(builder) || isCreateMaterializedView(builder) || isDorisCreateTable(builder) ||
            isCreateJob(builder)
    }

    // --- dispatch predicates (bounded, non-consuming; all use mark/rollback) ---

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
     * SELECT/WITH queries containing `QUALIFY` — the one query clause the MySQL grammar genuinely
     * cannot parse. Routed through [parseBoundedQuery]: real parse (structure + completion), boundary
     * forced to ';', the QUALIFY parse error hidden by DorisHighlightErrorFilter.
     *
     * Historical triggers now removed: window functions / IF() broke only because the dialect's
     * builtin-function map was empty (fixed in DorisSqlDialect.createTokensHelper); `* EXCEPT(...)`
     * and `REGEXP(...)` are handled upstream in DorisLexer, so the parser never sees them.
     */
    private fun isQueryWithDorisOnlyClause(builder: PsiBuilder): Boolean =
        isQueryStart(builder) && statementContainsAny(builder, "QUALIFY")

    private fun isDorisSpecificStatement(builder: PsiBuilder): Boolean {
        return when (wordAt(builder, 0)) {
            "ADMIN", "BACKUP", "RESTORE", "RECOVER", "SYNC", "WARM", "SWITCH" -> true
            "EXPORT" -> true // EXPORT TABLE ... TO "s3://..." WITH S3/HDFS/BROKER — pure Doris
            "REFRESH" -> true // REFRESH MATERIALIZED VIEW / TABLE / DATABASE / CATALOG — all Doris
            // Doris compute/workload-group USE (`USE @etl`, `USE db@etl`): MySQL reads the '@...' as
            // a user-variable reference inside its USE statement, which then red-flags as an
            // unresolvable variable. Plain `USE db` / `USE cat.db` keeps MySQL's typed USE statement
            // (console schema switching depends on it); only the '@' forms go lenient.
            "USE" -> statementContainsTokenPrefix(builder, '@')
            "PAUSE", "RESUME", "STOP" -> wordAt(builder, 1) in setOf("ROUTINE", "SYNC", "JOB")
            "CANCEL" -> true
            // Doris privilege spellings (SELECT_PRIV, LOAD_PRIV, USAGE_PRIV, ...) are identifiers to
            // MySQL's GRANT grammar, which then errors at the following ON. Role-only grants
            // (`GRANT 'role' TO 'user'@'%'`) are valid MySQL and keep their typed statement.
            "GRANT", "REVOKE" -> statementContainsTokenSuffix(builder, "_PRIV")
            // Doris `TRUNCATE TABLE t PARTITION (p, ...)` / `PARTITIONS (...)`: MySQL TRUNCATE takes
            // no partition clause. Plain TRUNCATE TABLE keeps MySQL's typed statement (mysql-core/41).
            "TRUNCATE" -> statementContainsAny(builder, "PARTITION", "PARTITIONS")
            // Doris `DELETE FROM t PARTITIONS (p1, p2)`: the plural keyword is Doris-only. The
            // singular parenthesised `PARTITION (p)` form is valid MySQL and stays typed.
            "DELETE" -> statementContainsAny(builder, "PARTITIONS")
            "CREATE" -> isDorisCreateStatement(builder)
            "ALTER" -> statementContainsAny(builder, "MATERIALIZED", "ROLLUP", "CATALOG", "RESOURCE",
                "DISTRIBUTION", "DISTRIBUTED", "BUCKETS", "PROPERTIES", "PARTITION", "WORKLOAD", "STORAGE") ||
                // Doris `ALTER TABLE t SET ("key" = "value")` property bag: MySQL has no ALTER ... SET
                // clause with a parenthesised bag. Gate on SET immediately followed by '(' so MySQL's
                // `ALTER ... ALTER COLUMN c SET DEFAULT x` keeps its typed statement.
                statementContainsWordThen(builder, "SET", "(")
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

    /**
     * DORIS PIPES: true when the statement at the cursor carries the `|>` pipe operator before the
     * next ';'. Token-based (a string literal is ONE token whose text includes its quotes), so a
     * `'|>'` inside a literal never matches; the lexer may deliver `|>` as one token or as `|`,`>`.
     * Non-consuming.
     */
    private fun containsPipeMarker(builder: PsiBuilder): Boolean {
        val marker = builder.mark()
        var previous: String? = null
        var scanned = 0
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (text == "|>" || (previous == "|" && text == ">")) { found = true; break }
            previous = text
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
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

    /** True if any token before the next ';' STARTS with [prefix] within the look-ahead window. Non-consuming. */
    private fun statementContainsTokenPrefix(builder: PsiBuilder, prefix: Char): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            if (builder.tokenText?.firstOrNull() == prefix) { found = true; break }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /** True if any token before the next ';' ENDS with [suffix] (case-insensitive) within the window. Non-consuming. */
    private fun statementContainsTokenSuffix(builder: PsiBuilder, suffix: String): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var found = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            if (builder.tokenText?.endsWith(suffix, ignoreCase = true) == true) { found = true; break }
            builder.advanceLexer()
            scanned++
        }
        marker.rollbackTo()
        return found
    }

    /** True if token [word] appears with [next] as the IMMEDIATELY following token (whitespace skipped by the builder). Non-consuming. */
    private fun statementContainsWordThen(builder: PsiBuilder, word: String, next: String): Boolean {
        val marker = builder.mark()
        var scanned = 0
        var found = false
        var prevWasWord = false
        while (!builder.eof() && builder.tokenText != ";" && scanned < MAX_LOOKAHEAD) {
            val text = builder.tokenText
            if (prevWasWord && text == next) { found = true; break }
            prevWasWord = text.equals(word, ignoreCase = true)
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

    private fun isCurrentWord(builder: PsiBuilder, expected: String): Boolean =
        builder.tokenText.equals(expected, ignoreCase = true)

    private fun isQueryStart(builder: PsiBuilder): Boolean =
        isCurrentWord(builder, "SELECT") ||
            // WITH starts a CTE query — except Doris `CREATE TABLE ... LIKE src WITH ROLLUP (...)`,
            // whose WITH would otherwise be parsed as a CTE by parseUntilQueryTail and error out
            // ("AS expected"). `WITH ROLLUP` is never a valid query lead, so exclude it outright.
            (isCurrentWord(builder, "WITH") && wordAt(builder, 1) != "ROLLUP")

    private companion object {
        const val MAX_LOOKAHEAD = 512
        // Statement leads the Route B replayer attempts: a query (SELECT / WITH cte / a parenthesised
        // `(SELECT ...)`, whose first letter-word is still SELECT). wordAt skips the leading '('.
        val REPLAY_QUERY_LEADS = setOf("SELECT", "WITH")
        // Statement leads the replayer types (structure/inner refs materialised). WARM(UP), REFRESH,
        // SWITCH — plus UPDATE / DELETE (Part 2 long-tail: single-table UPDATE and DELETE [USING]
        // replay byte-identical to the platform shapes; every other variant declines inside
        // CstReplayer and falls through unchanged). CREATE families are gated in wantsReplay.
        val REPLAY_STATEMENT_LEADS = setOf("REFRESH", "WARM", "SWITCH", "UPDATE", "DELETE")
        val CREATE_TABLE_MODIFIERS = setOf("TEMPORARY", "EXTERNAL")
        // Only clauses that are DISTINCTIVELY Doris. PRIMARY/UNIQUE/PARTITION/ENGINE/RANDOM/AUTO are
        // all valid MySQL table syntax — including them sent plain MySQL CREATE TABLE (PRIMARY KEY,
        // ENGINE=InnoDB, PARTITION BY ...) down the lenient path, losing its typed PSI (caught by the
        // golden corpus: mysql-core/38-create-table-plain diverged between dialects).
        val DORIS_TABLE_CLAUSES = arrayOf(
            "DISTRIBUTED", "BUCKETS", "PROPERTIES", "DUPLICATE", "AGGREGATE", "ROLLUP"
        )
    }
}
