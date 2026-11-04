package dev.sort.doris.sql

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.DORIS_METADATA

/** Offline docs for built-in calls; database objects and qualified UDFs keep their own docs. */
class DorisFunctionDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(
        editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int,
    ): PsiElement? {
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return null
        val leaf = contextElement ?: file.findElementAt(targetOffset) ?: return null
        val ref = PsiTreeUtil.getParentOfType(leaf, SqlReferenceExpression::class.java, false) ?: return null
        val call = ref.parent as? SqlFunctionCallExpression ?: return null
        if (call.nameElement != ref || ref.qualifierExpression != null) return null
        // Only the function name, never a literal/column within the argument list.
        if (!ref.textRange.containsOffset(targetOffset)) return null
        val name = ref.name
        val function = DORIS_METADATA.default.functions[name] ?: return null
        return FunctionDocsElement(function, ref)
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val function = (element as? FunctionDocsElement)?.function ?: return null
        if (function.name.equals("date_trunc", true)) return DATE_TRUNC_DOC
        return buildString {
            append("<div class='definition'><pre>")
            val signatures = function.overloads.map { overload ->
                val args = overload.argTypes.mapIndexed { index, type ->
                    val name = overload.argNames?.getOrNull(index)
                    val argument = if (name.isNullOrBlank()) type else "$name: $type"
                    argument + if (overload.variadic && index == overload.argTypes.lastIndex) "..." else ""
                }.joinToString(", ")
                "${function.name.uppercase()}($args): ${overload.returnType}"
            }.distinct()
            append(escape(signatures.ifEmpty { listOf(function.name.uppercase()) }.joinToString("\n")))
            append("</pre></div><div class='content'>Apache Doris 内置函数")
            function.profile?.notes?.takeIf { it.isNotBlank() }?.let {
                append("<p>").append(escape(it)).append("</p>")
            }
            append("</div>")
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? FunctionDocsElement)?.function?.name?.uppercase()?.let { "$it — Apache Doris" }

    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? =
        (element as? FunctionDocsElement)?.function?.takeIf { it.name.equals("date_trunc", true) }
            ?.let { listOf(DATE_TRUNC_URL) }

    private class FunctionDocsElement(val function: FunctionDef, private val anchor: PsiElement) : FakePsiElement() {
        override fun getParent() = anchor
        override fun getName() = function.name
        override fun getPresentableText() = "${function.name.uppercase()} — Apache Doris"
        override fun equals(other: Any?) = other is FunctionDocsElement && other.function == function && other.anchor == anchor
        override fun hashCode() = 31 * function.hashCode() + anchor.hashCode()
    }

    private companion object {
        fun escape(text: String) = StringUtil.escapeXmlEntities(text)
        const val DATE_TRUNC_URL = "https://doris.apache.org/docs/4.x/sql-manual/sql-functions/scalar-functions/date-time-functions/date-trunc/"
        val DATE_TRUNC_DOC by lazy {
            checkNotNull(DorisFunctionDocumentationProvider::class.java.getResource("/docs/functions/date_trunc.html"))
                .readText()
        }
    }
}
