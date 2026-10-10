package dev.sort.doris.sql

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.sql.symbols.virtual.DasVirtualSymbol
import dev.sort.doris.brikk.BrikkDorisContext

/** Platform virtual SQL column symbols retain a source context but expose no navigation target. */
class BrikkRelationNavigation : GotoDeclarationHandler {
    override fun getGotoDeclarationTargets(sourceElement: PsiElement?, offset: Int, editor: Editor): Array<PsiElement>? {
        val ref = PsiTreeUtil.getNonStrictParentOfType(sourceElement, SqlReferenceExpression::class.java) ?: return null
        if (!BrikkDorisContext.isActive(ref.containingFile)) return null
        val targets = ref.multiResolve(false).mapNotNull { result ->
            val symbol = (result as? SqlResolveResult)?.targetSymbol as? DasVirtualSymbol ?: return@mapNotNull null
            val context = symbol.contextElement
            // Only helper-provided Kotlin property/parameter locations. SQL aliases stay SQL-owned.
            context?.takeIf { it.containingFile?.language?.id == "kotlin" }?.navigationElement
        }.distinct()
        return targets.takeIf { it.isNotEmpty() }?.toTypedArray()
    }
}
