package dev.sort.doris.sql

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.sort.doris.pipes.DorisPipeStageKeywords
import dev.sort.doris.pipes.DorisPipes
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser

class DorisPipeStageKeywordAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element as? PsiFile ?: return
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE) || !DorisPipes.isEnabled(file.project)) return
        val text = file.text
        val tokens = DorisSqlParser().newLexer(text).allTokens.filter { it.channel == Token.DEFAULT_CHANNEL }
        for (index in 0 until tokens.lastIndex) {
            val pipe = tokens[index]
            val greater = tokens[index + 1]
            if (pipe.type != DorisLexer.PIPE || greater.type != DorisLexer.GT || greater.text != ">" ||
                pipe.stopIndex + 1 != greater.startIndex
            ) {
                continue
            }
            val stageTokens = tokens.drop(index + 2)
            val phrase = PHRASES.firstOrNull { words ->
                words.indices.all { offset -> stageTokens.getOrNull(offset)?.text?.equals(words[offset], true) == true }
            } ?: continue
            for ((offset, word) in phrase.withIndex()) {
                if (DorisKeywordHighlighter.isDorisKeyword(word)) continue
                val token = stageTokens[offset]
                val start = text.offsetByCodePoints(0, token.startIndex)
                val end = text.offsetByCodePoints(start, token.stopIndex + 1 - token.startIndex)
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(start, end))
                    .textAttributes(DefaultLanguageHighlighterColors.KEYWORD)
                    .create()
            }
        }
    }

    private companion object {
        private val PHRASES = DorisPipeStageKeywords.phrases
            .map { it.split(' ') }
            .sortedByDescending(List<String>::size)
    }
}
