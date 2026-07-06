package dev.sort.doris.sql

import com.intellij.lexer.Lexer
import com.intellij.lexer.LookAheadLexer
import com.intellij.sql.dialects.mysql.MysqlLexer
import com.intellij.sql.psi.SqlTokens

/**
 * Lexer for the DorisSQL *parser* that neutralizes Doris `SELECT * EXCEPT(col, ...)` column-exclusion.
 *
 * Why here and not in the parser: the platform MySQL parser is GrammarKit-generated and dispatches to
 * static rule functions — it never calls the overridable base `SqlParser` instance methods (verified:
 * overriding parseIdentifierOrAsteriskInner / parseValueExpression / parseReferenceExpression has zero
 * effect, and a PSI dump shows `EXCEPT` parsed as the set operator regardless). So `* EXCEPT(cols)` is
 * mis-parsed into a broken UNION which poisons the whole query's type — nothing downstream resolves.
 *
 * The only layer that reliably feeds the generated grammar is the token source. Here we mask the whole
 * `EXCEPT ( col, ... )` span as a block comment: the parser then sees a plain `SELECT * FROM ...`, the
 * `*` resolves to all columns, and completion/validation flow normally.
 *
 * Scope/limits:
 * - Only the column-exclusion form is masked — `EXCEPT ( <identifier> …`. The `EXCEPT` set operator
 *   (`… EXCEPT SELECT …` / `EXCEPT (SELECT …)`) is left untouched.
 * - This lexer feeds the PARSER only. Editor syntax coloring uses a separate highlighter lexer, so
 *   `EXCEPT(...)` still colors normally in the editor.
 * - Trade-off: excepted columns are still offered in completion (they aren't modeled as excluded).
 *   Column-accurate exclusion would require the parser to emit SQL_SELECT_EXCEPT_CLAUSE, which the
 *   generated MySQL grammar cannot be made to do without replacing the parser.
 */
class DorisLexer : LookAheadLexer(MysqlLexer()) {

    // Cast-context tracking (parser feeds tokens strictly forward, so simple fields are safe):
    // parenDepth counts all parens; castAsDepths holds, for each open CAST/TRY_CAST, the paren depth
    // at which its `AS <type>` will appear.
    private var parenDepth = 0
    private val castAsDepths = ArrayDeque<Int>()

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        parenDepth = 0
        castAsDepths.clear()
        super.start(buffer, startOffset, endOffset, initialState)
    }

    override fun lookAhead(baseLexer: Lexer) {
        when {
            isExceptColumnExclusion(baseLexer) -> maskExceptColumnList(baseLexer)
            isInsertOverwrite(baseLexer) -> maskInsertOverwriteHeader(baseLexer)
            isPartitionStar(baseLexer) -> maskThroughRightParen(baseLexer)
            isCastFunctionWord(baseLexer) -> {
                castAsDepths.addLast(parenDepth + 1)
                super.lookAhead(baseLexer)
            }
            baseLexer.tokenText == "(" -> { parenDepth++; super.lookAhead(baseLexer) }
            baseLexer.tokenText == ")" -> {
                parenDepth--
                while (castAsDepths.isNotEmpty() && castAsDepths.last() > parenDepth) castAsDepths.removeLast()
                super.lookAhead(baseLexer)
            }
            isCastAs(baseLexer) -> handleCastAs(baseLexer)
            else -> super.lookAhead(baseLexer)
        }
    }

    /**
     * Doris cast targets the MySQL grammar rejects. The generated `valid_cast_type_element` rule
     * accepts only MySQL's fixed cast-type tokens; Doris types (STRING, LARGEINT, VARIANT, BITMAP,
     * ARRAY<...>, MAP<...,...>, ...) fail with "<valid cast type element> expected" — a hidden parse
     * error that mangles the tree and breaks resolution (e.g. "cannot resolve '*'"). TRY_CAST is not
     * a MySQL special form at all, so its `AS` always breaks. When the `AS <type>` of a CAST/TRY_CAST
     * is not MySQL-valid we mask that tail as a comment: the call parses cleanly as a one-arg
     * function (type unknown, but the tree — and everything resolving through it — stays intact).
     * MySQL-valid targets (CHAR, JSON, SIGNED, DATETIME, ...) are left alone and keep full typing.
     */
    private fun isCastFunctionWord(base: Lexer): Boolean {
        if (base.tokenType == null) return false
        val seq = base.bufferSequence
        val isCast = regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "CAST") ||
            regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "TRY_CAST") ||
            regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "CONVERT")
        if (!isCast) return false
        val i = skipWhitespace(seq, base.tokenEnd)
        return i < seq.length && seq[i] == '('
    }

    private fun isCastAs(base: Lexer): Boolean {
        if (castAsDepths.isEmpty() || castAsDepths.last() != parenDepth) return false
        if (base.tokenType == null) return false
        val seq = base.bufferSequence
        return regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "AS")
    }

    private fun handleCastAs(base: Lexer) {
        castAsDepths.removeLast() // one AS per cast context
        val seq = base.bufferSequence
        val typeStart = skipWhitespace(seq, base.tokenEnd)
        val typeWord = readWord(seq, typeStart).uppercase()
        if (typeWord in MYSQL_CAST_TYPES) {
            super.lookAhead(base) // MySQL-valid target: emit AS normally, grammar handles the rest
            return
        }
        // Mask `AS <type[<...>][(...)]>` as one comment.
        var end = base.tokenEnd
        base.advance() // past AS
        // type word (plus optional keyword pair like DOUBLE PRECISION won't reach here — masked types only)
        while (base.tokenType != null && base.tokenText.isBlank()) { end = base.tokenEnd; base.advance() }
        if (base.tokenType != null) { end = base.tokenEnd; base.advance() } // the type word itself
        // optional generic args ARRAY<...> / MAP<...,...> (balanced) and precision (...)
        end = consumeBalancedIfNext(base, seq, end, '<', '>')
        end = consumeBalancedIfNext(base, seq, end, '(', ')')
        addToken(end, SqlTokens.SQL_BLOCK_COMMENT)
    }

    /** If the next non-ws char is [open], consume through its balanced [close]; returns new end. */
    private fun consumeBalancedIfNext(base: Lexer, seq: CharSequence, endSoFar: Int, open: Char, close: Char): Int {
        var end = endSoFar
        val i = skipWhitespace(seq, end)
        if (i >= seq.length || seq[i] != open) return end
        var depth = 0
        while (base.tokenType != null) {
            val t = base.tokenText
            if (t.isNotBlank()) {
                // tokens may be multi-char; count occurrences within the token text
                for (c in t) {
                    if (c == open) depth++
                    else if (c == close) depth--
                }
            }
            end = base.tokenEnd
            base.advance()
            if (depth <= 0 && end > i) break
        }
        return end
    }

    /**
     * Doris `INSERT OVERWRITE TABLE t ...`: mask `OVERWRITE` (and the following `TABLE`) so the MySQL
     * grammar parses the statement as a plain `INSERT t ...` (INTO is optional in MySQL) — yielding a
     * REAL insert PSI with a resolvable target table, instead of the lenient junk-token header that
     * degraded completion scoping inside the source query.
     */
    private fun isInsertOverwrite(base: Lexer): Boolean {
        if (base.tokenType == null) return false
        val seq = base.bufferSequence
        if (!regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "INSERT")) return false
        val i = skipWhitespace(seq, base.tokenEnd)
        return regionIsWordIgnoreCase(seq, i, "OVERWRITE")
    }

    private fun maskInsertOverwriteHeader(base: Lexer) {
        advanceLexer(base) // INSERT, as-is
        while (base.tokenType != null && base.tokenText.isBlank()) advanceLexer(base)
        // OVERWRITE -> comment
        addToken(base.tokenEnd, SqlTokens.SQL_BLOCK_COMMENT)
        base.advance()
        while (base.tokenType != null && base.tokenText.isBlank()) advanceLexer(base)
        if (base.tokenType != null && base.tokenText.equals("TABLE", ignoreCase = true)) {
            addToken(base.tokenEnd, SqlTokens.SQL_BLOCK_COMMENT) // TABLE -> comment
            base.advance()
        }
    }

    /** Doris auto-partition overwrite `PARTITION(*)` — `*` is not a valid MySQL partition name; mask
     *  the whole group. Named `PARTITION (p1, p2)` is valid MySQL and left untouched. */
    private fun isPartitionStar(base: Lexer): Boolean {
        if (base.tokenType == null) return false
        val seq = base.bufferSequence
        if (!regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "PARTITION")) return false
        var i = skipWhitespace(seq, base.tokenEnd)
        if (i >= seq.length || seq[i] != '(') return false
        i = skipWhitespace(seq, i + 1)
        if (i >= seq.length || seq[i] != '*') return false
        i = skipWhitespace(seq, i + 1)
        return i < seq.length && seq[i] == ')'
    }

    private fun maskThroughRightParen(base: Lexer) {
        var end = base.tokenEnd
        while (base.tokenType != null) {
            val done = base.tokenText == ")"
            end = base.tokenEnd
            base.advance()
            if (done) break
        }
        addToken(end, SqlTokens.SQL_BLOCK_COMMENT)
    }

    private fun regionIsWordIgnoreCase(seq: CharSequence, start: Int, word: String): Boolean {
        if (start + word.length > seq.length) return false
        for (k in word.indices) if (seq[start + k].uppercaseChar() != word[k]) return false
        val after = start + word.length
        return after >= seq.length || !(seq[after].isLetterOrDigit() || seq[after] == '_')
    }

    /** True iff the current token is `EXCEPT` followed by `( <identifier that is not a query>` — the
     *  Doris column-exclusion form, not the set operator. Peeks the raw buffer; does not advance. */
    private fun isExceptColumnExclusion(base: Lexer): Boolean {
        if (base.tokenType == null) return false
        val seq = base.bufferSequence
        if (!regionEqualsIgnoreCase(seq, base.tokenStart, base.tokenEnd, "EXCEPT")) return false
        var i = skipWhitespace(seq, base.tokenEnd)
        if (i >= seq.length || seq[i] != '(') return false
        i = skipWhitespace(seq, i + 1)
        if (i >= seq.length) return false
        val c = seq[i]
        if (!(c.isLetter() || c == '_' || c == '`')) return false
        val word = readWord(seq, i).uppercase()
        // a set-op right-hand side would start with one of these; a column list starts with a column name
        return word != "SELECT" && word != "WITH" && word != "VALUES" && word != "TABLE"
    }

    /** base is positioned at `EXCEPT`; consume through the matching `)` and emit the whole span as one comment. */
    private fun maskExceptColumnList(base: Lexer) {
        base.advance() // past EXCEPT
        while (base.tokenType != null && base.tokenText != "(") base.advance() // to '('
        var depth = 0
        var end = base.tokenEnd
        while (base.tokenType != null) {
            when (base.tokenText) {
                "(" -> depth++
                ")" -> depth--
            }
            end = base.tokenEnd
            base.advance()
            if (depth == 0) break
        }
        addToken(end, SqlTokens.SQL_BLOCK_COMMENT)
    }

    private fun skipWhitespace(seq: CharSequence, from: Int): Int {
        var i = from
        while (i < seq.length && seq[i].isWhitespace()) i++
        return i
    }

    private fun readWord(seq: CharSequence, from: Int): String {
        var i = from
        while (i < seq.length && (seq[i].isLetterOrDigit() || seq[i] == '_')) i++
        return seq.subSequence(from, i).toString()
    }

    private companion object {
        /** Cast targets DataGrip's MySQL grammar accepts (valid_cast_type_element). Anything else is masked. */
        val MYSQL_CAST_TYPES = setOf(
            "BINARY", "CHAR", "NCHAR", "NATIONAL", "DATE", "DATETIME", "TIME", "YEAR",
            "DECIMAL", "DEC", "DOUBLE", "FLOAT", "REAL", "SIGNED", "UNSIGNED", "JSON"
        )
    }

    private fun regionEqualsIgnoreCase(seq: CharSequence, start: Int, end: Int, word: String): Boolean {
        if (end - start != word.length) return false
        for (k in word.indices) if (seq[start + k].uppercaseChar() != word[k]) return false
        return true
    }
}
