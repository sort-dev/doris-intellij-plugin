package dev.sort.doris.sql

import com.intellij.openapi.diagnostic.logger

/**
 * Doris built-in function names, loaded from the generated resource produced by
 * tools/generate_doris_functions.py (sourced from the Doris docs — not hand-copied).
 * Upper-cased for case-insensitive matching.
 */
object DorisFunctions {
    private val LOG = logger<DorisFunctions>()
    private const val RESOURCE = "/dev/sort/doris/functions/doris-functions.txt"

    val NAMES: Set<String> by lazy { load() }

    private fun load(): Set<String> {
        val stream = DorisFunctions::class.java.getResourceAsStream(RESOURCE)
        if (stream == null) {
            LOG.warn("Missing Doris function resource: $RESOURCE")
            return emptySet()
        }
        return stream.bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.uppercase() }
                .toCollection(LinkedHashSet())
        }
    }
}
