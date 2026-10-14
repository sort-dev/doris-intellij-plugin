package dev.sort.doris.catalog

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.DasTable
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlCreateStatement
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.intellij.catalog.NamespaceTarget
import dev.brikk.house.sql.intellij.catalog.pathResolver
import dev.sort.doris.DorisCatalogs
import java.util.concurrent.ConcurrentHashMap

/**
 * A qualified table reference into a namespace the model lists but never loaded (an external
 * catalog, say) asks for the deepest listed part of its path, one level per request, as completion
 * of the path does; a table listed without columns asks for its columns. Annotates nothing: the
 * requests go out when the highlighting pass ends ([DorisReferenceIntrospections]), and the
 * introspection service drops repeats and highlights the file again when a refresh ends.
 */
class DorisReferenceIntrospectAnnotator : Annotator {
    companion object {
        const val PROPERTY: String = "doris.referenceIntrospection"
    }

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val reference = element as? SqlReferenceExpression ?: return
        if (!DorisCatalogs.enabled || !DorisCatalogProfile.autoIntrospect || reference.parent is SqlReferenceExpression) return
        // Tests of highlighting alone switch the requests off (`-Ddoris.referenceIntrospection=false`).
        if (System.getProperty(PROPERTY) == "false") return
        if (reference.referenceElementType.targetKind != ObjectKind.TABLE) return
        // A DDL statement's own names (CREATE TABLE t LIKE s, say) are declarations: resolving them from
        // here recurses through the statement's implicit context. Queries inside DDL are still read.
        if (PsiTreeUtil.getParentOfType(reference, SqlCreateStatement::class.java) != null &&
            PsiTreeUtil.getParentOfType(reference, SqlQueryExpression::class.java) == null) return
        val names = generateSequence(reference) { it.qualifierExpression as? SqlReferenceExpression }.map { it.name }.toList().asReversed()
        if (names.size < 2 || names.any { it.isNullOrEmpty() }) return
        val file = reference.containingFile ?: return
        val path = names.filterNotNull()
        val dataSource = DorisCatalogProfile.dataSources.resolve(file).dataSource ?: return
        val roots = DbPsiFacade.getInstance(file.project).findDataSource(dataSource.uniqueId)?.model?.modelRoots?.toList() ?: return
        val current = DorisCatalogProfile.dataSources.consoleNamespace(file)
        val resolver = DorisCatalogProfile.pathResolver
        val roots0 = DbPsiFacade.getInstance(file.project).findDataSource(
            DorisCatalogProfile.dataSources.resolve(file).dataSource?.uniqueId ?: return)?.model?.modelRoots?.toList() ?: return
        if (DorisCatalogProfile.pathResolver.resolve(roots0, path.take(1), DorisCatalogProfile.dataSources.consoleNamespace(file)) == null) {
            // From an empty or cleared model the first name is unknown: list the namespaces after this pass.
            file.project.service<DorisReferenceIntrospections>().later(file, path.dropLast(1))
            return
        }
        val target = when (val resolved = reference.resolve()) {
            null -> (path.size - 1 downTo 1).firstNotNullOfOrNull { resolver.resolve(roots, path.take(it), current) }
                ?.takeIf { it.node.kind in DorisCatalogProfile.levels && resolver.isChildless(it.node) }
            is DasTable -> if (DasUtil.getColumns(resolved).isEmpty) resolver.resolve(roots, path, current) else null
            else -> null
        } ?: return
        file.project.service<DorisReferenceIntrospections>().add(file, dataSource, target)
    }
}

/** Requests what [DorisReferenceIntrospectAnnotator] found, after the highlighting pass that found it. */
@Service(Service.Level.PROJECT)
class DorisReferenceIntrospections(private val project: Project) {
    private class Wanted(val file: SmartPsiElementPointer<PsiFile>, val dataSource: LocalDataSource, val target: NamespaceTarget)

    private val wanted = ConcurrentHashMap<List<String>, Wanted>()
    private val listings = ConcurrentHashMap<List<String>, SmartPsiElementPointer<PsiFile>>()

    /** Lists the namespaces [path] needs once the highlighting pass ends ([DorisNamespaceListing.ensure]). */
    fun later(file: PsiFile, path: List<String>) {
        listings.putIfAbsent(listOf(file.viewProvider.virtualFile.url) + path, SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file))
    }

    init {
        project.messageBus.connect().subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, object : DaemonCodeAnalyzer.DaemonListener {
            override fun daemonFinished(fileEditors: Collection<FileEditor>) = drain()
        })
    }

    fun add(file: PsiFile, dataSource: LocalDataSource, target: NamespaceTarget) {
        val key = listOf(dataSource.uniqueId) + target.path
        if (wanted.containsKey(key)) return
        wanted[key] = Wanted(SmartPointerManager.getInstance(project).createSmartPsiElementPointer(file), dataSource, target)
    }

    private fun drain() {
        if (wanted.isEmpty() && listings.isEmpty()) return
        ApplicationManager.getApplication().invokeLater({
            for (key in listings.keys.toList()) {
                val file = listings.remove(key)?.element ?: continue
                project.service<DorisNamespaceListing>().ensure(file, key.drop(1))
            }
            val requests = project.service<DorisAutoIntrospectService>()
            for (key in wanted.keys.toList()) {
                val next = wanted.remove(key) ?: continue
                next.file.element?.let { requests.request(it, next.dataSource, next.target) }
            }
        }, project.disposed)
    }
}
