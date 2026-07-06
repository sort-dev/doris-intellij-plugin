package dev.sort.doris.sql

import com.intellij.openapi.diagnostic.logger
import org.apache.doris.nereids.DorisParser
import org.apache.doris.sqlparser.DorisSqlParser

/**
 * Doris data-type names, derived at runtime from the bundled fe-sql-parser grammar rather than
 * hand-copied — so the list stays in sync with whatever fe-sql-parser version the plugin bundles.
 *
 * The `dataType` rule in DorisParser.g4 is a flat alternation over the type keywords
 * (`ARRAY`, `MAP`, `STRUCT`, `VARIANT`, `AGG_STATE`, and every `primitiveColType`), so the
 * ATN FIRST-token set of that rule *is* the complete set of type names. Falls back to a static
 * list only if the grammar's ATN shape ever changes out from under us.
 */
object DorisDataTypes {
    private val LOG = logger<DorisDataTypes>()

    val NAMES: List<String> by lazy { deriveFromGrammar() ?: FALLBACK }

    private fun deriveFromGrammar(): List<String>? {
        return try {
            val facade = DorisSqlParser()
            val parser = facade.newParser(facade.newLexer(""))
            val atn = parser.atn
            val vocabulary = parser.vocabulary
            val start = atn.ruleToStartState[DorisParser.RULE_dataType]
            val names = LinkedHashSet<String>()
            for (tokenType in atn.nextTokens(start).toList()) {
                val symbolic = vocabulary.getSymbolicName(tokenType) ?: continue
                if (symbolic == "ALL") continue // grammar catch-all, not a real type name
                names.add(symbolic)
            }
            if (names.isEmpty()) null else names.toList()
        } catch (t: Throwable) {
            LOG.warn("Could not derive Doris types from the grammar ATN; using static fallback", t)
            null
        }
    }

    private val FALLBACK = listOf(
        "TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "LARGEINT", "BOOLEAN", "FLOAT", "DOUBLE",
        "DECIMAL", "DECIMALV2", "DECIMALV3", "DATE", "DATEV1", "DATEV2", "DATETIME", "DATETIMEV1",
        "DATETIMEV2", "TIME", "TIMESTAMPTZ", "CHAR", "VARCHAR", "STRING", "TEXT", "JSON", "JSONB",
        "VARIANT", "BITMAP", "HLL", "QUANTILE_STATE", "AGG_STATE", "IPV4", "IPV6", "VARBINARY",
        "ARRAY", "MAP", "STRUCT"
    )
}
