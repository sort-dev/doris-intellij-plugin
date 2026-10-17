package dev.sort.doris.relation

/**
 * Known differences, not accepted expectations: the unchanged corpus remains the correctness oracle.
 * Ordinary SQL retains Doris's native MySQL resolver and table-type adapter for rename/find-usages
 * per owner decision 14; shared handlers resolve/navigate provider-managed references and otherwise
 * defer to native targets, diagnostics and completion use shared analysis plus native contributors,
 * and this harness's declaration-range probes still observe native PSI.
 * Every entry is keyed by case/axis; new differences and disappearing entries require review.
 * Remaining user impact: UNION ALL navigation can misleadingly choose the left origin and FULL JOIN
 * USING / column-count-mismatch navigation follows native semantics; the other entries are
 * native resolve-callback observations that shared navigation and completion no longer show.
 * Five real-editor samples have no extra column-resolution errors, but this is not a blanket
 * quietness claim: NATURAL gets an FE syntax rejection, and FULL JOIN USING highlighting exposed
 * a resolver-cache non-idempotence error awaiting library investigation.
 */
internal object DorisRelationKnownDifferences {
    data class Difference(val classification: String, val reason: String)

    val entries: Map<String, Difference> = buildMap {
        fun record(classification: String, reason: String, vararg keys: String) {
            require(classification in setOf("platform", "harness", "support-bug: pending library fix"))
            require(reason.isNotBlank())
            for (key in keys) {
                require(key.count { it == '/' } == 1)
                require(put(key, Difference(classification, reason)) == null) { "Duplicate ledger entry: $key" }
            }
        }
        record("support-bug: pending library fix",
            "The native resolve callback returns rhs.id for LEFT JOIN USING where shared analysis identifies src.id; shared navigation now reaches src.id.",
            "left-using-merged-star/resolve",
            "left-using-star-output-order/resolve",
        )
        record("platform",
            "Native MySQL reference/navigation semantics differ for ambiguity, grouping, set operations or USING/NATURAL outputs; shared analyzer observations are reported separately.",
            "full-using-key-non-null-non-null/navigation",
            "full-using-key-non-null-non-null/resolve",
            "full-using-key-non-null-nullable/navigation",
            "full-using-key-non-null-nullable/resolve",
            "full-using-key-nullable-non-null/navigation",
            "full-using-key-nullable-non-null/resolve",
            "full-using-key-nullable-nullable/navigation",
            "full-using-key-nullable-nullable/resolve",
            "join-ambiguous/resolve",
            "natural-star-output-order/resolve",
            "ordinary-group-alias/resolve",
            "self-join-same-slot/resolve",
            "set-column-count-mismatch/navigation",
            "set-column-count-mismatch/resolve",
            "set-nullability-names-except/resolve",
            "set-nullability-names-intersect/resolve",
            "set-nullability-names-union-all/resolve",
            "unknown-table-open-catalog/resolve",
            "unknown-table/resolve",
        )
        record("platform",
            "NATURAL JOIN's merged key resolves through the native adapter to rhs.id instead of the shared analyzer's src.id; navigation now follows shared analysis.",
            "natural-merged-star/resolve",
        )
        record("platform",
            "UNION ALL's computed output has no single origin, but native resolution and real Go to Declaration choose the left src.id, giving a misleading target.",
            "ordinary-set-union-all/navigation",
            "ordinary-set-union-all/resolve",
        )
        record("platform",
            "Doris's retained lenient PIPE PSI lacks native SqlReferenceExpression nodes; native resolve callbacks are unavailable even though shared references, scope and navigation remain available.",
            "aggregate-group-expression-alias/resolve",
            "aggregate-no-group/resolve",
            "call-known-output-contract/resolve",
            "called-slot-not-shadowed-by-cte/resolve",
            "join-after-drop-without-as/resolve",
            "parenthesized-pipe-input/resolve",
            "physical-mixed-pipe/resolve",
            "physical-pipe/resolve",
            "pipe-aggregate-group-order/resolve",
            "pipe-aggregate/resolve",
            "pipe-as-new-alias/resolve",
            "pipe-as/resolve",
            "pipe-distinct/resolve",
            "pipe-drop/resolve",
            "pipe-extend-computed/resolve",
            "pipe-extend-passthrough/resolve",
            "pipe-extend-unknown/resolve",
            "pipe-in-cte/resolve",
            "pipe-in-subquery/resolve",
            "pipe-join-anti/resolve",
            "pipe-join-cross/resolve",
            "pipe-join-full/resolve",
            "pipe-join-inner/resolve",
            "pipe-join-left/resolve",
            "pipe-join-right-anti/resolve",
            "pipe-join-right-semi/resolve",
            "pipe-join-right/resolve",
            "pipe-join-semi/resolve",
            "pipe-left-using-merged-star/resolve",
            "pipe-limit/resolve",
            "pipe-offset/resolve",
            "pipe-order-by/resolve",
            "pipe-pivot/resolve",
            "pipe-qualified-star/resolve",
            "pipe-rename/resolve",
            "pipe-select-computed/resolve",
            "pipe-select-rename-lineage/resolve",
            "pipe-set-except/resolve",
            "pipe-set-intersect/resolve",
            "pipe-set-union-all/resolve",
            "pipe-set-union-distinct/resolve",
            "pipe-set/resolve",
            "pipe-star-single/resolve",
            "pipe-unpivot/resolve",
            "pipe-where/resolve",
            "pipe-window/resolve",
            "pivot-surviving-key/resolve",
            "q13-keeps-distinct/resolve",
            "q13-keeps-limit/resolve",
            "q13-keeps-offset/resolve",
            "q13-keeps-order-by/resolve",
            "q13-keeps-where/resolve",
            "select-first-pipe/resolve",
            "unpivot-include-nulls/resolve",
        )
        record("platform",
            "The native contributor emits a guessed alias-shaped column despite no applicable case-sensitive zero-argument slot fact; shared analysis offers no slot columns.",
            "slot-arguments/completion",
            "slot-case-sensitive/completion",
            "slot-catalog-qualified/completion",
            "slot-qualified/completion",
        )
        record("platform",
            "No live Doris server: lowering, FE syntax and output columns are checked, but native server rows remain unverified.",
            "exec-extend-window-after-limit/coreExecution",
            "exec-full-join-after-where/coreExecution",
            "exec-inner-join-after-where/coreExecution",
            "exec-left-join-after-where/coreExecution",
            "exec-order-after-limit/coreExecution",
            "exec-right-join-after-where/coreExecution",
            "exec-set-input-alias/coreExecution",
        )
        record("harness",
            "Completion labels no longer carry SQL nullability or an 'unknown type' text (brikk-sql cc62bfd), so this observer reads nullability and computed display types from presentation it no longer gets; the analysis is unchanged.",
            "aggregate-group-expression-alias/completion",
            "bare-slot-is-physical/completion",
            "local-alias-shadow/completion",
            "ordinary-order-alias/completion",
            "ordinary-set-union-all/completion",
            "physical-mixed-pipe/completion",
            "physical-ordinary/completion",
            "physical-pipe/completion",
            "pipe-extend-computed/completion",
            "pipe-pivot/completion",
            "pipe-select-computed/completion",
            "pipe-set-union-all/completion",
            "pipe-set-union-distinct/completion",
            "pipe-set/completion",
            "pivot-surviving-key/completion",
            "set-nullability-names-union-all/completion",
            "unpivot-include-nulls/completion",
        )
        record("harness",
            "Computed identities are fixture labels rather than supplied ColumnSource identities; no stable computed identity is exposed to this observer.",
            "set-nullability-names-union-all/references",
            "set-nullability-names-union-all/scope",
        )
        record("harness",
            "Inferred literal/UNPIVOT-label SQL type information is stronger than the fixture's absent optional type metadata; raw expected and observed values stay unchanged.",
            "pipe-extend-unknown/scope",
            "pipe-unpivot/scope",
            "pipe-extend-unknown/completion",
            "pipe-unpivot/completion",
        )
    }

    fun verify(observed: Set<String>) {
        val unexpected = observed - entries.keys
        val disappeared = entries.keys - observed
        if (unexpected.isNotEmpty() || disappeared.isNotEmpty()) throw AssertionError(
            "Known-differences ledger changed. New: ${unexpected.sorted()}; disappeared: ${disappeared.sorted()}. " +
                "Review the difference, not the unchanged corpus expectation.",
        )
    }
}
