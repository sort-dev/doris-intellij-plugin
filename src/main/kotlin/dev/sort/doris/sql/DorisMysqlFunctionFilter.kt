package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.openapi.diagnostic.Logger
import com.intellij.sql.dialects.BuiltinFunction

/**
 * Removes the platform's MySQL-only builtin functions (e.g. `regexp_like`, `st_*`, `mbr*`, the
 * GTID / lock / crypto families) from DorisSQL completion.
 *
 * Those come from MySQL's `functions.xml`, which [DorisSqlDialect.createTokensHelper] loads for the
 * parser's special-forms (CAST … AS, TRIM … FROM); the platform then offers *all* of them in
 * completion, bypassing [DorisCompletionContributor]. This contributor runs FIRST (`order="first"`
 * in plugin.xml) and re-emits every downstream result except the MySQL builtins Doris doesn't have.
 *
 * Safe by construction:
 *  - only a lookup whose `object` is a [BuiltinFunction] can ever be dropped — a column/table/alias
 *    lookup (a `DasColumn`/… object) is never a `BuiltinFunction`, so a user column named `x`, `y`,
 *    `point`, `match`, `values`, … is untouched even though those are MySQL builtins;
 *  - suppression only fires when we have a server harvest for the file's data source
 *    ([DorisBuiltinCatalog.suppresses]); offline the platform list is left exactly as-is;
 *  - a name Doris recognizes as a function/alias or a grammar builtin ([DorisFunctions.isKnown]) or
 *    that the connected FE actually serves is always kept;
 *  - if the lookup object ever isn't a `BuiltinFunction` on some platform build, this simply no-ops
 *    (nothing dropped) rather than mis-filtering.
 */
class DorisMysqlFunctionFilter : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return
        val dataSourceId = DorisBuiltinCatalog.dataSourceIdFor(file)
        val hasData = DorisBuiltinCatalog.hasData(dataSourceId)
        // DIAGNOSTIC (wip): log entry state so a bake reveals whether the harvest populated.
        LOG.info("DorisMysqlFunctionFilter: ds=$dataSourceId hasData=$hasData")

        // Always run the loop during the diagnostic phase so we can log the object type of the
        // probe functions even when hasData is false (net completions unchanged — everything passes
        // unless it meets the real drop condition below).
        result.runRemainingContributors(parameters) { completionResult ->
            val lookup = completionResult.lookupElement
            val name = lookup.lookupString
            val obj = lookup.`object`
            if (name.uppercase() in PROBE) {
                LOG.info(
                    "  probe '$name' object=${obj?.javaClass?.name} isBuiltinFunction=${obj is BuiltinFunction} " +
                        "known=${DorisFunctions.isKnown(name)} " +
                        "suppresses=${DorisBuiltinCatalog.suppresses(name.uppercase(), dataSourceId, DorisFunctions.isKnown(name))}",
                )
            }
            val drop = hasData && obj is BuiltinFunction &&
                DorisBuiltinCatalog.suppresses(name.uppercase(), dataSourceId, DorisFunctions.isKnown(name))
            if (!drop) result.passResult(completionResult)
        }
    }

    private companion object {
        val LOG = Logger.getInstance(DorisMysqlFunctionFilter::class.java)
        /** Names to trace in the log to learn their lookup-object type + fate. */
        val PROBE = setOf("REGEXP_LIKE", "ST_AREA", "ST_POINT", "MBRCONTAINS", "MBR_CONTAINS")
    }
}
