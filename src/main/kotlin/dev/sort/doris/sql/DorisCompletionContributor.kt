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
import dev.sort.doris.catalog.DorisAutoIntrospectService
import dev.sort.doris.catalog.DorisCatalogProfile
import icons.DatabaseIcons

class DorisCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PipeColumnProvider)
    }

    /**
     * DORIS PIPES: heuristic COLUMN completion inside a pipe statement — the lenient pipe token
     * run has no query PSI, so the platform offers no columns at all. Offered: (1) the columns of
     * the stage-1 `FROM` table, resolved against the introspected model of the file's console
     * data source; (2) aliases introduced by `AS <name>` in stages BEFORE the caret. The real
     * per-stage shape walker (engine lineage) is the P2 item; this covers the common cases today.
     */
    private object PipeColumnProvider : CompletionProvider<CompletionParameters>() {
        private val FROM_TABLE = Regex("""\bFROM\s+([A-Za-z_`][\w`]*(?:\.[A-Za-z_`][\w`]*){0,2})""", RegexOption.IGNORE_CASE)
        private val AS_ALIAS = Regex("""\bAS\s+`?([A-Za-z_]\w*)`?""", RegexOption.IGNORE_CASE)

        /** `|> AS e` — names the PIPED relation itself. */
        private val PIPE_AS = Regex("""\|>\s*AS\s+`?([A-Za-z_]\w*)`?""", RegexOption.IGNORE_CASE)

        /** `JOIN path [AS] alias` — alias must not be a clause keyword. */
        private val JOIN_REL = Regex(
            """\bJOIN\s+([\w`.]+)(?:\s+(?:AS\s+)?(?!ON\b|USING\b|WHERE\b|LEFT\b|RIGHT\b|CROSS\b|INNER\b|FULL\b|JOIN\b|GROUP\b|ORDER\b|LIMIT\b)`?([A-Za-z_]\w*)`?)?""",
            RegexOption.IGNORE_CASE,
        )

        /** Columns of an arbitrary table path, resolved like the FROM base (no banner side-effects). */
        private fun lookupColumns(file: com.intellij.psi.PsiFile, parts: List<String>): List<String>? =
            recoverEditorOperation {
                val local = dorisDataSourceResolver.resolve(file).dataSource ?: return@recoverEditorOperation null
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.uniqueId == local.uniqueId }
                    ?: return@recoverEditorOperation null
                val roots = dataSource.model.modelRoots.toList()
                val ns = dorisDataSourceResolver.consoleNamespace(file)
                val table = DorisCatalogProfile.pathResolver.resolve(roots, parts, ns)?.node
                    as? com.intellij.database.model.DasTable ?: return@recoverEditorOperation null
                com.intellij.database.util.DasUtil.getColumns(table).map { it.name }.toList()
                    .takeIf { it.isNotEmpty() }
            }.getOrNull()

        /** Alias-qualified completion (`e.<caret>`): piped-relation alias or a JOIN relation. */
        private fun offerQualified(
            sink: CompletionResultSet,
            file: com.intellij.psi.PsiFile,
            chunkText: String,
            rel: Int,
            qual: String,
            fromQualified: String?,
            fromColumns: List<String>?,
        ): Boolean {
            val visibleText = dev.sort.doris.pipes.DorisPipes.codeBefore(chunkText, rel)
            // `|> AS e` — the piped relation: its columns are the stage scope at the caret.
            if (PIPE_AS.findAll(visibleText).any { it.groupValues[1].equals(qual, true) }) {
                val scope = dev.sort.doris.pipes.DorisPipesEngine
                    .stageScopeAt(chunkText, rel, fromQualified, fromColumns) ?: fromColumns ?: return false
                for (name in scope) {
                    sink.addElement(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(name)
                                .withIcon(com.intellij.icons.AllIcons.Nodes.Field)
                                .withTypeText("via $qual", true),
                            96.0,
                        ),
                    )
                }
                return true
            }
            // JOIN relations: alias match, or the bare table name as implicit qualifier.
            for (m in JOIN_REL.findAll(visibleText)) {
                val path = m.groupValues[1].split('.').map { it.trim('`') }
                val alias = m.groupValues[2].takeIf { it.isNotBlank() }
                val matches = (alias?.equals(qual, true) == true) || path.last().equals(qual, true)
                if (!matches) continue
                val cols = lookupColumns(file, path) ?: return false
                for (name in cols) {
                    sink.addElement(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(name)
                                .withIcon(com.intellij.icons.AllIcons.Nodes.Field)
                                .withTypeText("${path.last()} column", true),
                            96.0,
                        ),
                    )
                }
                return true
            }
            return false
        }

        /** FROM-stage path completion: catalogs, then schemas, then tables along the typed path. */
        private fun offerFromPath(
            sink: CompletionResultSet,
            file: com.intellij.psi.PsiFile,
            chunkText: String,
            rel: Int,
        ): Boolean {
            recoverEditorOperation {
                val sourceText = chunkText.substring(0, rel)
                var visibleText = dev.sort.doris.pipes.DorisPipes.codeBefore(chunkText, rel)
                if (sourceText.endsWith('`') && visibleText.isNotEmpty()) {
                    visibleText = visibleText.dropLast(1) + '`'
                }
                val m = Regex("""\b(?:FROM|JOIN)\s+([\w`.]*)$""", RegexOption.IGNORE_CASE)
                    .find(visibleText) ?: return false
                val parents = m.groupValues[1].split('.').map { it.trim('`') }.dropLast(1)
                val local = dorisDataSourceResolver.resolve(file).dataSource ?: return true
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.uniqueId == local.uniqueId }
                    ?: return true
                val roots = dataSource.model.modelRoots.toList()
                val nsFirst = dorisDataSourceResolver.consoleNamespace(file).firstOrNull()
                fun offer(names: Iterable<String>, type: String, icon: javax.swing.Icon) {
                    for (n in names) sink.addElement(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(n).withIcon(icon).withTypeText(type, true),
                            96.0,
                        ),
                    )
                }
                if (parents.isEmpty()) {
                    offer(roots.map { it.name }, if (DorisCatalogs.enabled) "catalog" else "schema", AllIcons.Nodes.Folder)
                    nsFirst?.let { DorisCatalogProfile.pathResolver.resolve(roots, listOf(it)) }
                        ?.let {
                            offer(DorisCatalogProfile.childrenOf(it.node).map { c -> c.name },
                                if (DorisCatalogs.enabled) "schema" else "table",
                                if (DorisCatalogs.enabled) AllIcons.Nodes.Package else DatabaseIcons.Table)
                        }
                } else {
                    val target = DorisCatalogProfile.pathResolver.resolve(roots, parents, dorisDataSourceResolver.consoleNamespace(file))
                    target?.let { offer(DorisCatalogProfile.childrenOf(it.node).map { c -> c.name }, "in ${it.node.name}", DatabaseIcons.Table) }
                }
            }
            return true
        }

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            if (!dev.sort.doris.pipes.DorisPipes.isEditingEnabled(parameters.originalFile)) return
            val file = parameters.originalFile
            if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            val text = file.text
            val offset = parameters.offset.coerceAtMost(text.length)
            val chunk = dev.sort.doris.pipes.DorisPipes.chunkAt(text, offset) ?: return
            if (!dev.sort.doris.pipes.DorisPipes.looksLikePipeChunk(chunk.text)) return

            val sink = result.caseInsensitive()
            val rel = (offset - chunk.startOffset).coerceIn(0, chunk.text.length)

            if (dev.sort.doris.brikk.BrikkDorisContext.isActive(file)) {
                val displays = BrikkRelationColumns.pipeDisplay(file, text, offset)
                val scope = displays?.keys?.toList() ?: dev.sort.doris.pipes.runPipeCatching { BrikkRelationColumns.pipeScope(file, text, offset) }.getOrNull()
                if (scope != null && !Regex("""[\w`]+\.\w*$""").containsMatchIn(chunk.text.substring(0, rel)) &&
                    !Regex("""\b(?:FROM|JOIN)\s+[\w`]*$""", RegexOption.IGNORE_CASE)
                        .containsMatchIn(dev.sort.doris.pipes.DorisPipes.codeBefore(chunk.text, rel))) {
                    for (name in scope) {
                        val display = displays?.get(name) ?: BrikkColumnDisplay(name, null,
                            dev.brikk.house.intellij.ColumnNullability.UNKNOWN, null)
                        sink.addElement(display.lookup())
                    }
                    return
                }
            }

            // Table-path position (FROM head or any JOIN stage): offer path segments, not columns.
            if (offerFromPath(sink, file, chunk.text, rel)) return

            // Base relation: the FROM table's das columns (introspected model — the DbDataSource
            // wrapper, NOT the LocalDataSource, whose table list is silently empty; round 6).
            val code = dev.sort.doris.pipes.DorisPipes.codeBefore(chunk.text, chunk.text.length)
            val qualified = FROM_TABLE.find(code)?.groupValues?.get(1)?.let { q ->
                q.split('.').joinToString(".") { it.trim('`') }
            }
            val dasColumns: List<String>? = recoverEditorOperation {
                val parts = qualified?.split('.') ?: return@recoverEditorOperation null
                val local = dorisDataSourceResolver.resolve(file).dataSource
                    ?: return@recoverEditorOperation null.also { dev.sort.doris.pipes.DorisPipes.info("columns: no data source for file") }
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.uniqueId == local.uniqueId }
                    ?: return@recoverEditorOperation null.also { dev.sort.doris.pipes.DorisPipes.info("columns: no DbDataSource for ${local.uniqueId}") }
                val roots = dataSource.model.modelRoots.toList()
                val resolver = DorisCatalogProfile.pathResolver
                val current = dorisDataSourceResolver.consoleNamespace(file)
                val target = (parts.size downTo 1).firstNotNullOfOrNull { depth ->
                    resolver.resolve(roots, parts.take(depth), current)
                } ?: return@recoverEditorOperation null
                val table = target.node as? com.intellij.database.model.DasTable
                val columns = table?.let { com.intellij.database.util.DasUtil.getColumns(it).map { column -> column.name }.toList() }.orEmpty()
                if (columns.isEmpty()) {
                    if (table != null || resolver.isChildless(target.node)) {
                        file.project.service<DorisAutoIntrospectService>().request(file, local, target)
                    }
                    return@recoverEditorOperation null
                }
                columns
            }.getOrNull()

            // Alias-qualified position (e.<caret>): resolve against the aliased relation.
            val qualMatch = Regex("""([A-Za-z_]\w*)\.\w*$""").find(chunk.text.substring(0, rel))
            if (qualMatch != null &&
                offerQualified(sink, file, chunk.text, rel, qualMatch.groupValues[1], qualified, dasColumns)
            ) {
                return
            }

            // Preferred: the engine's per-stage scope (brikk-sql 0.6.0 stageShapes, cached per
            // chunk) — exactly the columns in scope at the caret's stage, das-fed so the base
            // relation resolves to real names. Fixes the "alias offered before in scope" over-offer.
            val scope = dev.sort.doris.pipes.DorisPipesEngine.stageScopeAt(chunk.text, rel, qualified, dasColumns)
            if (scope != null) {
                for (name in scope) {
                    sink.addElement(
                        PrioritizedLookupElement.withPriority(
                            LookupElementBuilder.create(name)
                                .withIcon(com.intellij.icons.AllIcons.Nodes.Field)
                                .withTypeText("stage scope", true),
                            95.0,
                        ),
                    )
                }
                return
            }

            // Fallback (engine scope unavailable): aliases before the caret + raw das columns.
            for (m in AS_ALIAS.findAll(dev.sort.doris.pipes.DorisPipes.codeBefore(chunk.text, rel))) {
                sink.addElement(
                    PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(m.groupValues[1])
                            .withIcon(com.intellij.icons.AllIcons.Nodes.Field)
                            .withTypeText("pipe stage alias", true),
                        95.0,
                    ),
                )
            }
            for (name in dasColumns.orEmpty()) {
                sink.addElement(
                    PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(name)
                            .withIcon(com.intellij.icons.AllIcons.Nodes.Field)
                            .withTypeText("table column", true),
                        94.0,
                    ),
                )
            }
        }
    }

}
