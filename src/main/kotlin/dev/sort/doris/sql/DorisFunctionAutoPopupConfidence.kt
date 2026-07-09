package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState
import java.util.Locale

/**
 * Tames [DorisCompletionContributor]'s ~900-name function list so it stops breaking ordinary typing
 * (dogfood 2026-07-08 P1). The list is contributed at EVERY psi position, so before this gate the
 * autopopup fired functions everywhere — typing `1` in `GROUP BY 1` offered `sha1`/`log10`, and
 * typing `AS ` offered `asin`, which the next space accepted.
 *
 * Design is an ALLOWLIST, not a blocklist: our lenient/masked parse can't reliably enumerate every
 * position where a function is WRONG (a blocklist leaks — new bad spots keep breaking typing), but a
 * few positively-detected EXPRESSION positions cover where a function is plausibly RIGHT. A missed
 * autopopup is a shrug (Ctrl+Space still offers the full list, unaffected by confidence); a false
 * autopopup breaks typing. So functions default to explicit-invoke-only and autopopup only in
 * confirmed expression positions. The allowlist itself lives in [DorisExpressionPosition] and is
 * shared with the contributor's autopopup guard.
 *
 * This confidence handles the one case that is safe to suppress GLOBALLY (for all contributors, not
 * just ours): a digit-led prefix. `CompletionConfidence.shouldSkipAutopopup` returning YES kills the
 * autopopup for the whole position, so it must not fire where the platform's own table/column
 * autopopup is wanted — after a digit nothing at all completes (identifiers never start with a
 * digit), so YES there is harmless. Everywhere else this stays UNSURE and the per-contributor
 * autopopup gate in [DorisCompletionContributor] does the work, leaving platform autopopup intact.
 *
 * Registered order="first" (like [DorisTvfAutoPopupConfidence]); the chain returns on the first
 * non-UNSURE answer.
 */
class DorisFunctionAutoPopupConfidence : CompletionConfidence() {

    override fun shouldSkipAutopopup(contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        if (!psiFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return ThreeState.UNSURE
        // Inside a registered TVF's argument parens, defer entirely to DorisTvfAutoPopupConfidence.
        if (DorisTableFunctions.callWithCaretInArgs(contextElement, offset) != null) return ThreeState.UNSURE
        // A digit-led prefix (typing the `1` in `GROUP BY 1`) can never begin a function name, a
        // column, or a table — safe to skip the autopopup globally.
        return if (DorisExpressionPosition.prefixIsDigitLed(psiFile, offset)) ThreeState.YES else ThreeState.UNSURE
    }
}

/**
 * Cheap, lenient-parse-tolerant detection of the completion prefix and whether the caret sits in a
 * position where a Doris function is a plausible AUTOPOPUP candidate (an expression position). Used
 * by both [DorisFunctionAutoPopupConfidence] (global digit skip) and [DorisCompletionContributor]'s
 * FunctionProvider (withhold the function list from autopopup outside expression positions).
 *
 * "Expression position" is decided from the preceding non-whitespace/comment leaf only — a token
 * that a value expression can legitimately follow. This is deliberately an allowlist: unrecognized
 * genuinely-expression positions just fall through to "Ctrl+Space to get functions there", which is
 * acceptable. Known limits are documented on [isFunctionAutopopupPosition].
 */
object DorisExpressionPosition {

    /** Operators/punctuation a value expression can directly follow. `*` is deliberately excluded
     *  (it collides with `SELECT * ...`, where a function autopopup would be noise). */
    private val EXPR_OPERATORS: Set<String> = setOf(
        "(", ",",
        "=", "<", ">", "<=", ">=", "<>", "!=", "<=>",
        "+", "-", "/", "%", "||", "&", "|", "^", ":=",
    )

    /** Keywords a value expression can directly follow. Excludes BY/FROM/AS/GROUP/ORDER/JOIN etc.,
     *  which introduce a name, a relation, or nothing — not a function. */
    private val EXPR_KEYWORDS: Set<String> = setOf(
        "SELECT", "WHERE", "HAVING", "ON", "AND", "OR", "NOT", "IN", "LIKE",
        "BETWEEN", "THEN", "ELSE", "WHEN", "CASE", "IS", "XOR", "DIV", "MOD", "DISTINCT",
    )

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /** The identifier/number run ending at [offset], read straight from the document text (works
     *  whether or not the completion dummy identifier has been inserted). */
    fun prefixEndingAt(psiFile: PsiFile, offset: Int): String {
        val text = psiFile.viewProvider.contents
        val end = offset.coerceIn(0, text.length)
        var start = end
        while (start > 0 && isWordChar(text[start - 1])) start--
        return text.subSequence(start, end).toString()
    }

    /** True when the caret prefix begins with a digit — function names never do, so a substring
     *  matcher offering `sha1` for prefix `1` is over-reach; also nothing else completes here. */
    fun prefixIsDigitLed(psiFile: PsiFile, offset: Int): Boolean {
        val prefix = prefixEndingAt(psiFile, offset)
        return prefix.isNotEmpty() && prefix[0].isDigit()
    }

    /**
     * Whether a Doris function should be offered on AUTOPOPUP at [offset] in [psiFile].
     *
     * True only for positively-detected expression positions (the token immediately left of the
     * caret is an open paren, a comma, a value operator, or a keyword a value can follow). False by
     * default — including after a keyword like AS/BY/FROM, a complete identifier, a string, or a
     * closing paren. (A digit-led prefix is filtered earlier and never reaches here.)
     *
     * Works off the document offset (not a PSI leaf) so it is identical whether the caller is the
     * contributor — whose `parameters.position` is the inserted completion dummy — or a test/confidence
     * probe with no dummy. The in-progress word under the caret is stepped over; the token to its
     * left is what decides.
     *
     * Limits (all resolve to "Ctrl+Space still works there", never to broken typing):
     *  - Text-based keyword/operator match: a column literally named `and`/`or` used unquoted would
     *    read as an expression intro. Negligible in practice and only ever over-ALLOWS an autopopup.
     *  - Genuinely-expression positions with an unusual preceding token fall through to false.
     */
    fun isFunctionAutopopupPosition(psiFile: PsiFile, offset: Int): Boolean {
        val prev = precedingMeaningfulToken(psiFile, offset) ?: return false
        val text = prev.text ?: return false
        if (text in EXPR_OPERATORS) return true
        return text.uppercase(Locale.ROOT) in EXPR_KEYWORDS
    }

    /** The token immediately left of the caret, skipping the in-progress word run, whitespace, and
     *  comments. Offset-based so it does not depend on whether a completion dummy was inserted. */
    private fun precedingMeaningfulToken(psiFile: PsiFile, offset: Int): PsiElement? {
        val prefixLen = prefixEndingAt(psiFile, offset).length
        val probe = offset - prefixLen // start of the in-progress word (== offset when none)
        if (probe <= 0) return null
        var leaf: PsiElement? = psiFile.findElementAt(probe - 1)
        while (leaf != null) {
            if (leaf !is PsiWhiteSpace && leaf !is PsiComment && leaf.textLength > 0) return leaf
            leaf = PsiTreeUtil.prevLeaf(leaf, /* skipEmptyElements = */ true)
        }
        return null
    }
}
