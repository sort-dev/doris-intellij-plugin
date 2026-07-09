package dev.sort.doris.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement

/**
 * Re-colors the DorisLexer's MASKED spans so they don't render as comments.
 *
 * Root cause (dogfood 2026-07-08, P3 "CAST targets colored like comments"): the platform's
 * [com.intellij.sql.editor.SqlSyntaxHighlighter] builds its highlighting lexer from
 * `LanguageParserDefinitions.forLanguage(dialect)` — i.e. OUR [DorisParserDefinition.createLexer]
 * = [DorisLexer]. So the parser-side masking (`AS LARGEINT`, `EXCEPT(col, ...)`, `OVERWRITE`,
 * `PARTITION(*)` -> one SQL_BLOCK_COMMENT token) is ALSO what the editor colors: any token in
 * `SqlTokens.COMMENT_TOKENS` gets SQL_COMMENT attributes, and the masked span renders comment-gray.
 * (The old "the editor highlighter uses its own MysqlLexer" assumption was wrong on this platform.)
 *
 * Fixing the COLOR at the lexer would mean masking to a non-comment token type, which changes the
 * PSI leaf type — the parser only skips tokens in the comment set, PSI walkers only skip
 * [PsiComment]s, and every golden tree pins `PsiComment(SQL_BLOCK_COMMENT)`. Not provably neutral.
 * Instead this annotator re-colors the masked RANGES only (TextAttributes; no PSI, no parser, no
 * golden changes): each word inside a masked span gets keyword/identifier color, numbers/strings
 * their literal colors, punctuation plain text — matching what the plain highlighter would do.
 *
 * A masked span is recognizable with zero false positives: it is a [PsiComment] whose text does
 * not start with a real SQL comment introducer (slash-star, `--`, `#`) — the lexer only ever emits
 * such "comments" for masked SQL, and genuine comments always carry their introducer in the text.
 */
class DorisMaskedSpanRecolorAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is PsiComment) return
        if (!element.containingFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
        val text = element.text
        if (text.isEmpty() || isRealComment(text)) return

        val base = element.textRange.startOffset
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val word = text.substring(start, i)
                    val key = if (DorisKeywordHighlighter.isDorisKeyword(word)) {
                        DefaultLanguageHighlighterColors.KEYWORD
                    } else {
                        DefaultLanguageHighlighterColors.IDENTIFIER
                    }
                    recolor(holder, base, start, i, key)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                    recolor(holder, base, start, i, DefaultLanguageHighlighterColors.NUMBER)
                }
                c == '\'' || c == '"' -> {
                    val start = i
                    i++
                    while (i < text.length && text[i] != c) i++
                    if (i < text.length) i++ // closing quote
                    recolor(holder, base, start, i, DefaultLanguageHighlighterColors.STRING)
                }
                c == '`' -> {
                    val start = i
                    i++
                    while (i < text.length && text[i] != '`') i++
                    if (i < text.length) i++
                    recolor(holder, base, start, i, DefaultLanguageHighlighterColors.IDENTIFIER)
                }
                else -> {
                    val start = i
                    i++
                    recolor(holder, base, start, i, HighlighterColors.TEXT)
                }
            }
        }
    }

    private fun recolor(holder: AnnotationHolder, base: Int, start: Int, end: Int, key: TextAttributesKey) {
        if (end <= start) return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(TextRange(base + start, base + end))
            .textAttributes(key)
            .create()
    }

    private companion object {
        /** Genuine SQL comments always start with their introducer; masked spans start with SQL. */
        private fun isRealComment(text: String): Boolean =
            text.startsWith("/*") || text.startsWith("--") || text.startsWith("#")
    }
}
