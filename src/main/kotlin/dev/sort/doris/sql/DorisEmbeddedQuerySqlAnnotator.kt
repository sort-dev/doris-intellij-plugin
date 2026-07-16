package dev.sort.doris.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlLiteralExpression
import com.intellij.sql.psi.SqlTokens

/**
 * Basic (purely lexical) SQL coloring INSIDE the string value of `query(... , 'query' = '…')` —
 * the remote-query TVF whose payload is itself SQL. Keywords, numbers, and nested string
 * literals get their colors so a multi-line embedded query reads as SQL, not as one green blob.
 * Deliberately dumb: no PSI, no resolution, no injection — just the word scanner the masked-span
 * recolor uses, scoped to this ONE property of this ONE function.
 */
class DorisEmbeddedQuerySqlAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null || element.node.elementType != SqlTokens.SQL_STRING_TOKEN) return
        if (!element.containingFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
        val text = element.text
        if (text.length < 3 || text.last() != text.first()) return
        val literal = element.parent as? SqlLiteralExpression ?: return
        val pair = PsiTreeUtil.getParentOfType(literal, SqlBinaryExpression::class.java) ?: return
        if (pair.rOperand !== literal) return
        val key = pair.lOperand?.text?.trim()?.let {
            if (it.length >= 2 && it.first() in "'\"`" && it.last() == it.first()) it.substring(1, it.length - 1) else it
        }
        if (!"query".equals(key, ignoreCase = true)) return
        val call = PsiTreeUtil.getParentOfType(pair, SqlFunctionCallExpression::class.java) ?: return
        if (!"query".equals(call.nameElement?.name, ignoreCase = true)) return

        val base = element.textRange.startOffset
        var i = 1 // inside the quotes
        val end = text.length - 1
        fun paint(start: Int, stop: Int, key: TextAttributesKey) {
            if (stop > start) holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(base + start, base + stop)).textAttributes(key).create()
        }
        while (i < end) {
            val c = text[i]
            when {
                c == '\\' -> i += 2 // escape: leave both chars string-colored
                c.isLetter() || c == '_' -> {
                    val s = i
                    while (i < end && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val key = if (DorisKeywordHighlighter.isDorisKeyword(text.substring(s, i))) {
                        DefaultLanguageHighlighterColors.KEYWORD
                    } else DefaultLanguageHighlighterColors.IDENTIFIER
                    paint(s, i, key)
                }
                c.isDigit() -> {
                    val s = i
                    while (i < end && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                    paint(s, i, DefaultLanguageHighlighterColors.NUMBER)
                }
                c == '\'' || c == '"' -> { // nested literal: keep string color, skip past it
                    i++
                    while (i < end && text[i] != c) { if (text[i] == '\\') i++; i++ }
                    if (i < end) i++
                }
                else -> i++
            }
        }
    }
}
