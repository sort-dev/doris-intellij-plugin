package com.brikk.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext

/**
 * Offers Doris built-in function names (from the docs/registry-generated list, DorisFunctions.NAMES)
 * in code completion. DataGrip resolves/completes functions against the introspected data-source
 * model, ignoring the dialect's builtin-function registry, so Doris built-ins must be contributed
 * explicitly here — the pattern the StarRocks and TDengine dialect plugins use.
 */
class DorisCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), FunctionProvider)
    }

    private object FunctionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            if (!parameters.originalFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            // Doris function names are case-insensitive, so match regardless of the IDE's
            // "Match case" setting (otherwise typing AB... won't complete a lowercase 'abs').
            val sink = result.caseInsensitive()
            for (name in DorisFunctions.NAMES) {
                sink.addElement(
                    LookupElementBuilder.create(name.lowercase())
                        .withIcon(AllIcons.Nodes.Function)
                        .withTypeText("Doris function", true)
                        .withInsertHandler(CALL_PARENS)
                )
            }
        }
    }

    private companion object {
        // Insert "()" after the function name and place the caret between them (unless one is already there).
        private val CALL_PARENS = InsertHandler<LookupElement> { context, _ ->
            val offset = context.tailOffset
            val doc = context.document
            val alreadyHasParen = offset < doc.textLength && doc.charsSequence[offset] == '('
            if (!alreadyHasParen) {
                doc.insertString(offset, "()")
            }
            context.editor.caretModel.moveToOffset(offset + 1)
        }
    }
}
