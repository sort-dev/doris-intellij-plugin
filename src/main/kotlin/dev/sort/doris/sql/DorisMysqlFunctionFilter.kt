package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.sql.dialects.BuiltinFunction

/**
 * Removes the platform's MySQL-only builtin functions (e.g. `regexp_like`, `st_area`, `mbrcontains`,
 * the GTID / lock / crypto families) from DorisSQL completion.
 *
 * Those come from MySQL's `functions.xml`, which [DorisSqlDialect.createTokensHelper] loads for the
 * parser's special-forms (CAST … AS, TRIM … FROM); the platform then offers *all* of them in
 * completion, bypassing [DorisCompletionContributor]. This contributor runs FIRST (`order="first"`
 * in plugin.xml) and re-emits every downstream result except the MySQL builtins Doris doesn't have.
 *
 * Safe by construction:
 *  - only a lookup whose `object` is a [BuiltinFunction.Prototype] can ever be dropped — that is the
 *    object DataGrip attaches to a builtin-function completion (one per overload; verified live in
 *    the 2026.1 completion log). A column/table/alias lookup is a `DasColumn`/`String`/…, never a
 *    `Prototype`, so a user column named `x`, `y`, `point`, `match`, `values`, … is untouched even
 *    though those are MySQL builtins;
 *  - suppression only fires when we have a server harvest for the file's data source
 *    ([DorisBuiltinCatalog.suppresses]); offline the platform list is left exactly as-is;
 *  - a name Doris recognizes as a function/alias or a grammar builtin ([DorisFunctions.isKnown]) or
 *    that the connected FE actually serves is always kept (so `st_point` stays, `st_area` — absent
 *    from this server's builtin list — goes).
 */
class DorisMysqlFunctionFilter : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return
        val dataSourceId = DorisBuiltinCatalog.dataSourceIdFor(file)
        // Offline / no harvest: leave every contributor's output untouched (design: offline as-is).
        if (!DorisBuiltinCatalog.hasData(dataSourceId)) return

        result.runRemainingContributors(parameters) { completionResult ->
            val lookup = completionResult.lookupElement
            val drop = lookup.`object` is BuiltinFunction.Prototype &&
                DorisBuiltinCatalog.suppresses(
                    lookup.lookupString.uppercase(),
                    dataSourceId,
                    DorisFunctions.isKnown(lookup.lookupString),
                )
            if (!drop) result.passResult(completionResult)
        }
    }
}
