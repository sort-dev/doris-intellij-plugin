package dev.sort.doris.sql

import dev.brikk.house.sql.metadata.DORIS_FUNCTION_CATALOG
import dev.brikk.house.sql.metadata.FunctionDef
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

    /**
     * Completion-presentation facts for one function name/alias, projected from brikk-sql's
     * `FunctionDef`. [params] and [returnType] come from the representative (first) overload and give
     * the MySQL-style signature: [params] is the comma-joined argument **types** (brikk carries types
     * + arity + return type + null-propagation, but NOT parameter names), [returnType] the result
     * type. [overloadCount] flags a "+N" hint when a function has more overloads than the one shown.
     * [params]/[returnType] are null for dynamic-signature functions (all table-valued and a few
     * whose class computes types at plan time) — those complete as a bare name, exactly as before.
     */
    data class Info(
        val kind: Kind,
        val params: String?,
        val returnType: String?,
        val overloadCount: Int,
    )

    /** Upper-cased function name **and** alias → its [Info] (first definition wins on collision). */
    val INFO_BY_NAME: Map<String, Info> by lazy {
        buildMap {
            for (def in DORIS_FUNCTION_CATALOG.functions) {
                val info = def.toInfo()
                putIfAbsent(def.name.uppercase(), info)
                for (alias in def.aliases) putIfAbsent(alias.uppercase(), info)
            }
        }
    }

    /** Back-compat view: upper-cased name/alias → [Kind] only. */
    val BY_NAME: Map<String, Kind> by lazy { INFO_BY_NAME.mapValues { it.value.kind } }

    /** All Doris built-in function names and aliases, upper-cased. */
    val NAMES: Set<String> get() = INFO_BY_NAME.keys

    private fun FunctionDef.toInfo(): Info {
        val rep = overloads.firstOrNull()
        val params = rep?.let { o ->
            buildString {
                append(o.argTypes.joinToString(", "))
                if (o.variadic) {
                    if (o.argTypes.isNotEmpty()) append(", ")
                    append("…")
                }
            }
        }
        return Info(kind.normalized(), params, rep?.returnType, overloads.size)
    }

    private fun FunctionKind.normalized(): Kind = when (this) {
        FunctionKind.AGGREGATE -> Kind.AGGREGATE
        FunctionKind.WINDOW -> Kind.WINDOW
        FunctionKind.TABLE_VALUED, FunctionKind.TABLE_GENERATING -> Kind.TABLE
        FunctionKind.SCALAR -> Kind.SCALAR
    }
}
