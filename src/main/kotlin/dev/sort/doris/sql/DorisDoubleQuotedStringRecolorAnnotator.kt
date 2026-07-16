package dev.sort.doris.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement

/**
 * Paints double-quoted tokens with STRING attributes in Doris files (cosmetic layer of the
 * double-quote story; throwaway once the lexer remaps `"…"` to a real string token).
 *
 * In Doris, `"…"` is ALWAYS a string literal — never an identifier (backticks are the only
 * identifier quoting; the ANSI_QUOTES sql_mode is a server no-op). The MySQL substrate instead
 * reads it as an ANSI quoted identifier: [DorisHighlightInfoFilter] already suppresses the
 * resulting bogus errors, but the editor still COLORED the token as an identifier — a
 * confidently-wrong purple `'query' = "select …"`. This annotator lays string color back over
 * exactly those tokens, the same silent-annotation trick [DorisMaskedSpanRecolorAnnotator] uses
 * for masked spans.
 *
 * Guards: leaf tokens only (composite wrappers share the same text — one paint, not three), and
 * never [PsiComment]s (a DorisLexer masked span is a pseudo-comment whose text can begin and end
 * with a quote — recoloring the whole span as one string would undo the masked-span recolor).
 */
class DorisDoubleQuotedStringRecolorAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null || element is PsiComment) return
        if (!element.containingFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
        val text = element.text
        if (text.length < 2 || text[0] != '"' || text[text.length - 1] != '"') return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(element.textRange)
            .textAttributes(DefaultLanguageHighlighterColors.STRING)
            .create()
    }
}
