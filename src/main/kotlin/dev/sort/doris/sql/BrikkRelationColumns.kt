package dev.sort.doris.sql

import com.intellij.database.types.DasTypeSystemBase
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.impl.light.LightElement
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.impl.SqlTableTypeBase
import dev.brikk.house.intellij.ColumnContract
import dev.brikk.house.intellij.ContextResult
import dev.brikk.house.intellij.ShapeResult
import dev.sort.doris.brikk.BrikkDorisContext

/** Statement-local Kotlin relation inputs. Never added to a physical datasource or builtin registry. */
object BrikkRelationColumns {
    fun contracts(file: PsiFile): Map<String, ShapeResult> =
        (BrikkDorisContext.relations(file) as? ContextResult.Available)?.context?.relations.orEmpty()

    fun slot(call: SqlFunctionCallExpression): ShapeResult? {
        val name = slotName(call) ?: return null
        return contracts(call.containingFile)[name]
    }

    internal fun slotName(call: SqlFunctionCallExpression): String? {
        if (call.parent?.node?.elementType != SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION) return null
        if (!call.parameterList?.expressionList.isNullOrEmpty() || call.nameElement?.qualifierExpression != null) return null
        return call.nameElement?.name
    }

    fun tableType(call: SqlFunctionCallExpression): SqlTableType? {
        val result = slot(call) ?: return null
        val shape = (result as? ShapeResult.Available)?.shape ?: return SqlTableTypeBase.EMPTY_TABLE
        var table: SqlTableType = SqlTableTypeBase.EMPTY_TABLE
        for (column in shape.columns) {
            // Kotlin contracts do not prove an exact native SQL type or SQL quote identity.
            val one = SqlTableTypeBase.createType(
                ColumnElement(call, column, shape.navigation?.location).navigationElement, DasTypeSystemBase.UNKNOWN, column.name,
                false, false, true, null, null,
            )
            table = table.add(one)
        }
        return table
    }

    fun openColumnContext(ref: SqlReferenceExpression): Boolean {
        val query = PsiTreeUtil.getParentOfType(ref, SqlQueryExpression::class.java) ?: return false
        val table = query.tableExpression ?: return false
        val qualifier = ref.qualifierExpression?.text
        return PsiTreeUtil.findChildrenOfType(table, SqlFunctionCallExpression::class.java).any { call ->
            if (PsiTreeUtil.getParentOfType(call, SqlQueryExpression::class.java) !== query) return@any false
            val contract = slot(call) ?: return@any false
            val alias = (call.parent?.parent as? SqlAsExpression)?.name
            if (qualifier != null && !qualifier.equals(alias ?: call.nameElement?.name, true)) return@any false
            contract !is ShapeResult.Available || contract.shape.knowledge == dev.brikk.house.intellij.ShapeKnowledge.PARTIAL
        }
    }

    fun pipeScope(file: PsiFile, text: String, offset: Int): List<String>? {
        val contracts = contracts(file)
        if (contracts.isEmpty()) return null
        val inputs = contracts.mapNotNull { (name, result) ->
            val shape = (result as? ShapeResult.Available)?.shape ?: return@mapNotNull null
            name to dev.brikk.house.sql.shape.Shape(shape.columns.map { column ->
                dev.brikk.house.sql.shape.ColumnShape(column.name, "UNKNOWN", when (column.nullability) {
                    dev.brikk.house.intellij.ColumnNullability.NON_NULL -> false
                    dev.brikk.house.intellij.ColumnNullability.NULLABLE -> true
                    dev.brikk.house.intellij.ColumnNullability.UNKNOWN -> null
                })
            })
        }.toMap()
        // The splitter labels stage 1 FROM as the base input. No global registry of slot names.
        val fragment = dev.sort.doris.pipes.DorisPipes.chunkAt(text, offset) ?: return null
        val declaredSlots = dev.brikk.house.sql.shape.SqlFragment(fragment.text, "doris").tableSlots.toSet()
        val slot = declaredSlots.firstOrNull()
        val base = inputs[slot]?.names()
        return dev.sort.doris.pipes.DorisPipesEngine.stageScopeAt(fragment.text, offset - fragment.startOffset, null, base,
            inputs.filterKeys { it in declaredSlots })
    }

    internal fun pipeDisplay(file: PsiFile, text: String, offset: Int): Map<String, BrikkColumnDisplay>? {
        val chunk = dev.sort.doris.pipes.DorisPipes.chunkAt(text, offset) ?: return null
        return dev.sort.doris.pipes.runPipeCatching {
            BrikkPipeColumnDisplay.at(chunk.text, offset - chunk.startOffset, contracts(file))
        }.getOrNull()
    }

    /** Platform symbols keep this as a smart-pointer context. Never return injected completion-copy PSI. */
    private class ColumnElement(
        private val call: SqlFunctionCallExpression,
        private val column: ColumnContract,
        private val relation: dev.brikk.house.intellij.SourceLocation?,
    ) : LightElement(call.manager, DorisSqlDialect.INSTANCE), PsiNamedElement {
        override fun getName(): String = column.name
        override fun setName(name: String): PsiElement = throw com.intellij.util.IncorrectOperationException("Rename the authored Kotlin property")
        override fun getContainingFile(): PsiFile = call.containingFile
        override fun getNavigationElement(): PsiElement = locate(column.navigation?.location) ?: locate(relation)
            ?: InjectedLanguageManager.getInstance(project).getInjectionHost(call) ?: call

        private fun locate(location: dev.brikk.house.intellij.SourceLocation?): PsiElement? {
            location ?: return null
            // Light-fixture VFS URLs need not be globally registered; the host file is authoritative.
            val host = InjectedLanguageManager.getInstance(project).getTopLevelFile(call)
            val file = host?.takeIf { it.virtualFile?.url == location.fileUrl }
                ?: VirtualFileManager.getInstance().findFileByUrl(location.fileUrl)?.let { PsiManager.getInstance(project).findFile(it) }
                ?: return null
            var element = file.findElementAt(location.start) ?: return null
            while (element.textRange.startOffset != location.start || element.textRange.endOffset != location.end) {
                element = element.parent ?: return null
            }
            return element
        }
        override fun toString(): String = "Brikk relation contract column ${column.name}"
    }
}
