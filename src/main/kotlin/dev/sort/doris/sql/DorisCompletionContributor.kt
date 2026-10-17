package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import dev.brikk.house.sql.intellij.catalog.pathResolver
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.catalog.DorisCatalogProfile
import dev.sort.doris.catalog.DorisAutoIntrospectService
import dev.sort.doris.pipes.DorisPipes
import icons.DatabaseIcons

/** Catalog path completion in PIPE heads; column scope belongs to the shared analyzer. */
class DorisCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PipePathProvider)
    }

    private object PipePathProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
            val file = parameters.originalFile
            if (!DorisPipes.isEditingEnabled(file) || !file.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            val offset = parameters.offset.coerceAtMost(file.textLength)
            val chunk = DorisPipes.chunkAt(file.text, offset) ?: return
            if (!DorisPipes.looksLikePipeChunk(chunk.text)) return
            val rel = (offset - chunk.startOffset).coerceIn(0, chunk.text.length)
            val sink = result.caseInsensitive()
            requestMissingColumns(file, chunk.text, rel)
            recoverEditorOperation {
                val sourceText = chunk.text.substring(0, rel)
                var visibleText = DorisPipes.codeBefore(chunk.text, rel)
                if (sourceText.endsWith('`') && visibleText.isNotEmpty()) visibleText = visibleText.dropLast(1) + '`'
                val match = Regex("""\b(?:FROM|JOIN)\s+([\w`.]*)$""", RegexOption.IGNORE_CASE).find(visibleText) ?: return
                val parents = match.groupValues[1].split('.').map { it.trim('`') }.dropLast(1)
                val local = dorisDataSourceResolver.resolve(file).dataSource ?: return
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId) ?: return
                val roots = dataSource.model.modelRoots.toList()
                val namespace = dorisDataSourceResolver.consoleNamespace(file)
                fun offer(names: Iterable<String>, type: String, icon: javax.swing.Icon) {
                    for (name in names) sink.addElement(PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name).withIcon(icon).withTypeText(type, true), 96.0))
                }
                if (parents.isEmpty()) {
                    offer(roots.map { it.name }, if (DorisCatalogs.enabled) "catalog" else "schema", AllIcons.Nodes.Folder)
                    namespace.firstOrNull()?.let { DorisCatalogProfile.pathResolver.resolve(roots, listOf(it)) }?.let {
                        offer(DorisCatalogProfile.childrenOf(it.node).map { child -> child.name },
                            if (DorisCatalogs.enabled) "schema" else "table",
                            if (DorisCatalogs.enabled) AllIcons.Nodes.Package else DatabaseIcons.Table)
                    }
                } else DorisCatalogProfile.pathResolver.resolve(roots, parents, namespace)?.let {
                    offer(DorisCatalogProfile.childrenOf(it.node).map { child -> child.name }, "in ${it.node.name}", DatabaseIcons.Table)
                }
            }
        }

        /** Loading the attached model stays plugin-owned; completion scope stays shared. */
        private fun requestMissingColumns(file: com.intellij.psi.PsiFile, text: String, offset: Int) {
            recoverEditorOperation {
                val position = dev.brikk.house.sql.scope.RelationScope.complete(text, "doris",
                    dev.brikk.house.sql.scope.RelationFacts(), offset).position
                if (position != dev.brikk.house.sql.scope.PositionKind.COLUMN) return
                val parts = Regex("""\bFROM\s+([A-Za-z_`][\w`]*(?:\.[A-Za-z_`][\w`]*){0,2})""", RegexOption.IGNORE_CASE)
                    .find(DorisPipes.codeBefore(text, text.length))?.groupValues?.get(1)?.split('.')?.map { it.trim('`') } ?: return
                val local = dorisDataSourceResolver.resolve(file).dataSource ?: return
                val source = com.intellij.database.psi.DbPsiFacade.getInstance(file.project).findDataSource(local.uniqueId) ?: return
                val roots = source.model.modelRoots.toList()
                val resolver = DorisCatalogProfile.pathResolver
                val namespace = dorisDataSourceResolver.consoleNamespace(file)
                val target = (parts.size downTo 1).firstNotNullOfOrNull { depth -> resolver.resolve(roots, parts.take(depth), namespace) } ?: return
                val table = target.node as? com.intellij.database.model.DasTable
                if (table != null && com.intellij.database.util.DasUtil.getColumns(table).toList().isNotEmpty()) return
                if (table != null || resolver.isChildless(target.node)) file.project.service<DorisAutoIntrospectService>().request(file, local, target)
            }
        }
    }
}
