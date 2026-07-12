package dev.sort.doris.sql

import dev.brikk.house.sql.metadata.DORIS_FUNCTION_CATALOG

/**
 * Doris built-in function names, sourced from **brikk-sql-metadata**'s [DORIS_FUNCTION_CATALOG]
 * (`dev.brikk.house:brikk-sql-metadata-jvm`) — Doris's real runtime function registry, upper-cased
 * for case-insensitive matching. Includes primary names and aliases.
 *
 * This replaces the previous plugin-local generated resource (`doris-functions.txt` +
 * `tools/generate_doris_functions.py`); the generator now lives upstream in brikk-house, so this
 * plugin consumes the shared catalog instead of maintaining its own copy. The catalog also carries
 * per-function `kind`, `overloads`, `isTableFunction`, and a (currently unpopulated) `sinceVersion`
 * hook we can adopt later for version-gated completion.
 */
object DorisFunctions {

    /** All Doris built-in function names and aliases, upper-cased. */
    val NAMES: Set<String> by lazy {
        DORIS_FUNCTION_CATALOG.functions
            .flatMap { def -> listOf(def.name) + def.aliases }
            .mapTo(LinkedHashSet()) { it.uppercase() }
    }
}
