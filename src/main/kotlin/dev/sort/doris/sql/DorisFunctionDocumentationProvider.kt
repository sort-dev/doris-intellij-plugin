package dev.sort.doris.sql

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlTableExpression
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.FunctionDef
import java.util.Locale

/** Offline Chinese docs for built-in calls, including the platform's special SQL grammar. */
class DorisFunctionDocumentationProvider : AbstractDocumentationProvider() {
    override fun getCustomDocumentationElement(
        editor: Editor, file: PsiFile, contextElement: PsiElement?, targetOffset: Int,
    ): PsiElement? {
        if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return null
        val leaf = file.findElementAt(targetOffset) ?: contextElement ?: return null
        val name = leaf.text.trim('`')
        if (!name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))) return null
        // A call must have a following '('; this also admits EXTRACT/CAST/DATE_ADD,
        // whose PSI does not necessarily use SqlFunctionCallExpression.
        val previous = significantLeaf(leaf, forward = false)
        if (previous?.text == ".") return null
        if (significantLeaf(leaf, forward = true)?.text != "(") return null
        val reference = PsiTreeUtil.getParentOfType(leaf, SqlReferenceExpression::class.java, false)
        if (reference != null) {
            val call = reference.parent as? SqlFunctionCallExpression ?: return null
            if (call.nameElement != reference || reference.qualifierExpression != null) return null
        } else {
            val expression = PsiTreeUtil.getParentOfType(leaf, SqlExpression::class.java, false) ?: return null
            if (expression is SqlTableExpression || expression.textRange.startOffset != leaf.textRange.startOffset) return null
        }
        val function = DORIS_METADATA.default.functions[name]
        val page = DorisChineseFunctionDocs.page(name)
            ?: function?.let { DorisChineseFunctionDocs.page(it.name) }
        if (page == null && function == null) return null
        return FunctionDocsElement(name, function, page, leaf)
    }

    private fun significantLeaf(element: PsiElement, forward: Boolean): PsiElement? {
        var next = if (forward) PsiTreeUtil.nextLeaf(element) else PsiTreeUtil.prevLeaf(element)
        while (next is PsiWhiteSpace || next is PsiComment) {
            next = if (forward) PsiTreeUtil.nextLeaf(next) else PsiTreeUtil.prevLeaf(next)
        }
        return next
    }

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val target = element as? FunctionDocsElement ?: return null
        target.page?.let { return DorisChineseFunctionDocs.html(it) }
        val function = target.function ?: return null
        return buildString {
            append("<div class='definition'><pre>")
            val signatures = function.overloads.map { overload ->
                val args = overload.argTypes.mapIndexed { index, type ->
                    val name = overload.argNames?.getOrNull(index)
                    val argument = if (name.isNullOrBlank()) "参数${index + 1}: $type" else "$name: $type"
                    argument + if (overload.variadic && index == overload.argTypes.lastIndex) "..." else ""
                }.joinToString(", ")
                "${function.name.uppercase(Locale.ROOT)}($args) → ${overload.returnType}"
            }.distinct()
            append(escape(signatures.ifEmpty { listOf(target.name) }.joinToString("\n")))
            append("</pre></div><div class='content'><p>Apache Doris 内置函数</p>")
            append("<p>官方 Doris 4.x 中文函数文档未收录此名称的独立说明。以上参数及返回类型来自插件内置元数据，")
            append("不代表服务端已验证支持。未提供的语义、参数名称和示例不作推测。</p>")
            if (function.overloads.isEmpty()) append("<p>当前元数据未提供参数签名，请以所连接服务端为准。</p>")
            append("</div>")
        }
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? =
        (element as? FunctionDocsElement)?.name?.let { "$it — Apache Doris" }

    override fun getUrlFor(element: PsiElement?, originalElement: PsiElement?): List<String>? =
        (element as? FunctionDocsElement)?.page?.let { listOf(DorisChineseFunctionDocs.url(it)) }

    private class FunctionDocsElement(
        private val requestedName: String, val function: FunctionDef?, val page: String?, private val anchor: PsiElement,
    ) : FakePsiElement() {
        override fun getParent() = anchor
        override fun getName() = requestedName.uppercase(Locale.ROOT)
        override fun getPresentableText() = "$name — Apache Doris"
        override fun equals(other: Any?) = other is FunctionDocsElement && other.name == name && other.anchor == anchor
        override fun hashCode() = 31 * name.hashCode() + anchor.hashCode()
    }

    private companion object {
        fun escape(text: String) = StringUtil.escapeXmlEntities(text)
    }
}
