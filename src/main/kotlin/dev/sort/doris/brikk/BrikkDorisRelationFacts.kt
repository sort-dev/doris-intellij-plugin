package dev.sort.doris.brikk

import com.intellij.psi.PsiFile
import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkRelationFacts
import dev.brikk.house.intellij.BrikkRelationFactsResult
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.RelationColumnLabels
import dev.brikk.house.sql.intellij.relation.RelationFactsResult

class BrikkDorisRelationFacts : ExternalRelationFacts {
    private val helper = BrikkRelationFacts(BrikkDialect.DORIS)
    private val helperLabels = BrikkRelationFacts.LABELS
    private val adapter = ExternalRelationFacts.of(
        labels = RelationColumnLabels(declared = helperLabels.declared, contract = helperLabels.contract,
            declaredKind = if (helperLabels.brikkColumnStyle) SqlLookupKind.BRIKK_COLUMN else SqlLookupKind.RELATION_COLUMN),
        navigationTarget = helper::navigationTarget,
    ) { file -> when (val result = helper.facts(file)) {
        is BrikkRelationFactsResult.Available -> RelationFactsResult.Available(result.facts, result.revision)
        is BrikkRelationFactsResult.Pending -> RelationFactsResult.Pending(result.reason)
        null -> null
    } }
    override val labels get() = adapter.labels
    override fun facts(file: PsiFile) = adapter.facts(file)
    override fun navigationTarget(file: PsiFile, provenance: String) = adapter.navigationTarget(file, provenance)
}
