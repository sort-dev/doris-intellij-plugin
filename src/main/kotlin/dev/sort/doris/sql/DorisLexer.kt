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

    override fun lookAhead(baseLexer: Lexer) {
        if (isExceptColumnExclusion(baseLexer)) {
            maskExceptColumnList(baseLexer)
        } else {
            super.lookAhead(baseLexer)
        }
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

    private fun regionEqualsIgnoreCase(seq: CharSequence, start: Int, end: Int, word: String): Boolean {
        if (end - start != word.length) return false
        for (k in word.indices) if (seq[start + k].uppercaseChar() != word[k]) return false
        return true
    }
}
