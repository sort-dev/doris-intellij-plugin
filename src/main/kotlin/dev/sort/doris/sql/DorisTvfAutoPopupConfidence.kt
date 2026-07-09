package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.ThreeState

/**
 * Makes completion AUTO-POPUP fire inside the quoted property strings of a registered Doris
 * table-valued-function call (dogfood 2026-07-08 P3: typing inside `S3('uri' = '...', '<caret>')`
 * offered nothing).
 *
 * Why it was dead: Doris TVF property keys/values are STRING literals (`'key' = 'value'`), and the
 * platform's `SkipAutopopupInStrings` confidence (registered for every language, order="last")
 * suppresses completion auto-popup inside any string-literal token. Explicit Ctrl+Space always
 * worked — [DorisCompletionContributor]'s TvfArgumentProvider fires for both quote styles — but
 * nobody invokes completion explicitly mid-string, so in practice key completion "didn't offer".
 *
 * The confidence chain returns on the FIRST non-UNSURE answer, so this DorisSQL-scoped extension
 * (order="first" in plugin.xml) answers NO ("do not skip") exactly when the caret is inside the
 * argument parens of a registered TVF call — the one place we complete inside strings — and stays
 * UNSURE everywhere else, leaving ordinary string-literal behavior to the platform default.
 */
class DorisTvfAutoPopupConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (!psiFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return ThreeState.UNSURE
        return if (DorisTableFunctions.callWithCaretInArgs(contextElement, offset) != null) {
            ThreeState.NO
        } else {
            ThreeState.UNSURE
        }
    }
}
