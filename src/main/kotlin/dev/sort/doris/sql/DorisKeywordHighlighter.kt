package dev.sort.doris.sql

import com.intellij.lexer.DelegateLexer
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlTokens
import java.util.Locale

/**
 * Wraps DataGrip's SQL syntax highlighter and upgrades any bare identifier whose text is a
 * Doris keyword to keyword color. We delegate lexing to the SQL92 lexer, which only knows
 * standard SQL keywords, so Doris-only keywords (SHOW, DISTRIBUTED, BUCKETS, PROPERTIES, ...)
 * otherwise come through as plain identifiers. Every non-keyword token keeps its original SQL
 * coloring via the wrapped highlighter.
 *
 * This is lexical (context-free): non-reserved keywords such as NAME/VALUE/TYPE are colored
 * even when used as identifiers. Context-sensitive keyword handling arrives with the
 * fe-sql-parser layer (see PLAN.md).
 */
class DorisKeywordHighlighter(private val base: SyntaxHighlighter) : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = DorisKeywordLexer(base.highlightingLexer)

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> {
        return if (tokenType === DORIS_KEYWORD) KEYWORD_KEYS else base.getTokenHighlights(tokenType)
    }

    /** Delegates all lexing to the wrapped SQL lexer, re-tagging Doris keywords for coloring only. */
    private class DorisKeywordLexer(delegate: Lexer) : DelegateLexer(delegate) {
        override fun getTokenType(): IElementType? {
            val type = super.getTokenType() ?: return null
            if (type === SqlTokens.SQL_IDENT && isDorisKeyword(tokenText)) {
                return DORIS_KEYWORD
            }
            return type
        }
    }

    companion object {
        // Highlighting-only synthetic token; never enters the PSI tree (this lexer is not the parser).
        private val DORIS_KEYWORD: IElementType = IElementType("DORIS_KEYWORD", DorisSqlDialect.INSTANCE)

        private val KEYWORD_KEYS: Array<TextAttributesKey> = arrayOf(
            TextAttributesKey.createTextAttributesKey("DORIS_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
        )

        // All 565 Doris keywords (reserved + non-reserved), upper-cased for case-insensitive match.
        private val KEYWORDS: Set<String> = HashSet<String>(1024).apply {
            for (kw in DorisReservedKeywords.RESERVED_KEYWORDS) add(kw.uppercase(Locale.ROOT))
            for (kw in DorisOptionalKeywords.OPTIONAL_KEYWORDS) add(kw.uppercase(Locale.ROOT))
        }

        private fun isDorisKeyword(text: CharSequence): Boolean =
            KEYWORDS.contains(text.toString().uppercase(Locale.ROOT))
    }
}
