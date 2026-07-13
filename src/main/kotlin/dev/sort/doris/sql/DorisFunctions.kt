package dev.sort.doris.sql

import dev.brikk.house.sql.metadata.DORIS_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionKind

/**
 * Doris built-in functions, sourced from **brikk-sql-metadata**'s [DORIS_FUNCTION_CATALOG]
 * (`dev.brikk.house:brikk-sql-metadata-jvm`) — Doris's real runtime function registry.
 *
 * This replaces the previous plugin-local generated resource (`doris-functions.txt` +
 * `tools/generate_doris_functions.py`); the generator now lives upstream in brikk-house, so this
 * plugin consumes the shared catalog. Beyond names, the catalog carries each function's `kind`,
 * which we surface as [Kind] for completion presentation (scalar / aggregate / window / table icons
 * and labels). It also holds `overloads` and a (currently unpopulated) `sinceVersion` hook we can
 * adopt later for signature help and version-gated completion.
 */
object DorisFunctions {

    /** Normalized function role, used to pick the completion icon + label. */
    enum class Kind { SCALAR, AGGREGATE, WINDOW, TABLE }

    /** Upper-cased function name **and** alias → its [Kind] (first definition wins on collision). */
    val BY_NAME: Map<String, Kind> by lazy {
        buildMap {
            for (def in DORIS_FUNCTION_CATALOG.functions) {
                val kind = def.kind.normalized()
                putIfAbsent(def.name.uppercase(), kind)
                for (alias in def.aliases) putIfAbsent(alias.uppercase(), kind)
            }
        }
    }

    /** All Doris built-in function names and aliases, upper-cased. */
    val NAMES: Set<String> get() = BY_NAME.keys

    private fun FunctionKind.normalized(): Kind = when (this) {
        FunctionKind.AGGREGATE -> Kind.AGGREGATE
        FunctionKind.WINDOW -> Kind.WINDOW
        FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING -> Kind.TABLE
        FunctionKind.SCALAR -> Kind.SCALAR
    }
}
