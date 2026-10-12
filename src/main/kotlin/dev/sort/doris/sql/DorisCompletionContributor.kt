package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import icons.DatabaseIcons
import com.intellij.util.ProcessingContext
import dev.brikk.house.sql.intellij.recoverEditorOperation

class DorisCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PipeColumnProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), QualifiedPathIntrospectProvider)
    }

    /**
     * Auto-introspection trigger for ANY Doris statement (not just pipes — user round 20 ask):
     * completion invoked right after a qualified path (`hive_ovh.outbox.<caret>`) that resolves to
     * an enumerated-but-childless namespace kicks the TARGETED refresh + progress banner. Offers
     * nothing itself — the platform (or the pipe providers) supply names once the model has them.
     * Engine-free: candidate for the base plugin (0.7.0) independent of Doris Pipes.
     */
    private object QualifiedPathIntrospectProvider : CompletionProvider<CompletionParameters>() {
        private val PATH_BEFORE_CARET = Regex("""([A-Za-z_`][\w`]*(?:\.[A-Za-z_`][\w`]*)*)\.\s*\w*$""")

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            // Engine-free base feature: runs with or without the transpiler plugin installed.
            val file = parameters.originalFile
            if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            recoverEditorOperation {
                val text = file.text
                val offset = parameters.offset.coerceAtMost(text.length)
                val tail = text.substring((offset - 160).coerceAtLeast(0), offset)
                val parts = PATH_BEFORE_CARET.find(tail)?.groupValues?.get(1)
                    ?.split('.')?.map { it.trim('`') } ?: return
                val local = dorisDataSourceResolver.resolve(file).dataSource ?: return
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.uniqueId == local.uniqueId }
                    ?: return
                val roots = dataSource.model.modelRoots.toList()
                val nsFirst = dorisDataSourceResolver.consoleNamespace(file).firstOrNull()
                val tkind = com.intellij.database.model.ObjectKind.TABLE
                fun children(o: com.intellij.database.model.DasObject) =
                    (o.getDasChildren(tkind).toList() + o.getDasChildren(null).toList()).distinct()
                var node: com.intellij.database.model.DasObject? =
                    roots.firstOrNull { it.name.equals(parts[0], true) }
                        ?: roots.firstOrNull { it.name.equals(nsFirst ?: "", true) }
                            ?.let { cat -> children(cat).firstOrNull { it.name.equals(parts[0], true) } }
                for (p in parts.drop(1)) {
                    node = node?.let { n -> children(n).firstOrNull { it.name.equals(p, true) } }
                }
                val target = node ?: return
                if (children(target).isNotEmpty()) return // normal completion will handle it
                val fqn = parts.joinToString(".")
                dev.sort.doris.pipes.DorisPipesAutoIntrospect.request(
                    file.project, local, parts.dropLast(1).lastOrNull() ?: nsFirst, parts.last(), target)
                dev.sort.doris.pipes.DorisPipesNotificationProvider.reportMiss(
                    file.project, file.viewProvider.virtualFile,
                    "Doris: introspecting '$fqn'\u2026 invoke completion again when it finishes " +
                        "(if nothing appears, introspect it in the Database view).")
            }
        }
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
                val want = when (parts.size) {
                    3 -> Triple(parts[0], parts[1], parts[2])
                    2 -> Triple(ns.getOrNull(0), parts[0], parts[1])
                    else -> Triple(ns.getOrNull(0), ns.getOrNull(1), parts[0])
                }
                val tkind = com.intellij.database.model.ObjectKind.TABLE
                fun children(o: com.intellij.database.model.DasObject) =
                    (o.getDasChildren(tkind).toList() + o.getDasChildren(null).toList()).distinct()
                var node: com.intellij.database.model.DasObject? =
                    roots.firstOrNull { it.name.equals(want.first ?: "", true) }
                var schema = node?.let { n -> children(n).firstOrNull { it.name.equals(want.second ?: "", true) } }
                if (schema == null && parts.size == 2) {
                    // catalog.table? no — try first segment as catalog root, second as schema-less table later
                    schema = roots.firstOrNull { it.name.equals(parts[0], true) }
                        ?.let { n -> children(n).firstOrNull { it.name.equals(parts[1], true) } }
                }
                val table = (schema?.let { sn -> children(sn).firstOrNull { it.name.equals(want.third, true) } }
                    ?: schema) as? com.intellij.database.model.DasTable ?: return@recoverEditorOperation null
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
                val tkind = com.intellij.database.model.ObjectKind.TABLE
                fun children(o: com.intellij.database.model.DasObject) =
                    (o.getDasChildren(tkind).toList() + o.getDasChildren(null).toList()).distinct()
                if (parents.isEmpty()) {
                    offer(roots.map { it.name }, "catalog", AllIcons.Nodes.Folder)
                    roots.firstOrNull { it.name.equals(nsFirst ?: "", true) }
                        ?.let { offer(children(it).map { c -> c.name }, "schema", AllIcons.Nodes.Package) }
                } else {
                    var node: com.intellij.database.model.DasObject? =
                        roots.firstOrNull { it.name.equals(parents[0], true) }
                            ?: roots.firstOrNull { it.name.equals(nsFirst ?: "", true) }
                                ?.let { cat -> children(cat).firstOrNull { it.name.equals(parents[0], true) } }
                    for (p in parents.drop(1)) {
                        node = node?.let { n -> children(n).firstOrNull { it.name.equals(p, true) } }
                    }
                    val kids = node?.let { children(it) }.orEmpty()
                    if (node != null && kids.isEmpty()) {
                        // The user is PATH-TYPING into an enumerated-but-childless namespace — the
                        // exact moment auto-introspection should kick (dogfood round 19: the trigger
                        // only lived on the column path, so `schema.` did nothing at all).
                        val fqn = parents.joinToString(".")
                        dev.sort.doris.pipes.DorisPipesAutoIntrospect.request(
                            file.project, local, parents.dropLast(1).lastOrNull() ?: nsFirst,
                            parents.last(), node)
                        dev.sort.doris.pipes.DorisPipesNotificationProvider.reportMiss(
                            file.project, file.viewProvider.virtualFile,
                            "Doris Pipes: introspecting '$fqn'\u2026 invoke completion again when it " +
                                "finishes (if nothing appears, introspect it in the Database view).")
                    } else {
                        node?.let { offer(kids.map { c -> c.name }, "in ${it.name}", DatabaseIcons.Table) }
                    }
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
                // DETERMINISTIC (user call-out): the console KNOWS its context — qualify the
                // FROM reference against the live namespace instead of name-hunting the tree.
                // currentNamespace is catalog(.schema) in our multi-catalog model; a 2-part name
                // is schema.table under the current catalog, a bare name uses the current schema.
                val nsNames = dorisDataSourceResolver.consoleNamespace(file)
                val curCatalog = nsNames.getOrNull(0)
                val curSchema = nsNames.getOrNull(1)
                val want = when (parts.size) {
                    3 -> Triple(parts[0], parts[1], parts[2])
                    2 -> Triple(curCatalog, parts[0], parts[1])
                    else -> Triple(curCatalog, curSchema, parts[0])
                }
                // Walk the model BY PATH (root -> catalog -> schema -> table): the flat
                // DasUtil.getTables traversal skips the internal catalog subtree in our
                // two-level model (log evidence: only external-catalog tables enumerated).
                val model = dataSource.model
                fun childNamed(o: com.intellij.database.model.DasObject, name: String?) =
                    name?.let { n -> o.getDasChildren(null).firstOrNull { it.name.equals(n, true) } }
                val roots = model.modelRoots.toList()
                val catalogNode = want.first?.let { c -> roots.firstOrNull { it.name.equals(c, true) } }
                val schemaNode = (catalogNode ?: roots.firstOrNull { it.name.equals(want.second ?: "", true) })
                    ?.let { base -> if (catalogNode != null) childNamed(base, want.second) else base }
                val tkind = com.intellij.database.model.ObjectKind.TABLE
                val vf = file.viewProvider.virtualFile
                // 2-part reference whose first segment is a CATALOG (hive_ovh.outbox): reinterpret
                // as catalog.schema so the not-introspected banner can name the real target.
                var bannerFqn = listOfNotNull(want.first, want.second).joinToString(".")
                val schemaNodeEff = schemaNode ?: (if (parts.size == 2)
                    roots.firstOrNull { it.name.equals(parts[0], true) }
                        ?.let { childNamed(it, parts[1]) }
                        ?.also { bannerFqn = "${parts[0]}.${parts[1]}" }
                else null)
                val schemaTables = schemaNodeEff?.let { sn ->
                    (sn.getDasChildren(tkind).toList() + sn.getDasChildren(null).toList()).distinct()
                }.orEmpty()
                // Banner ONLY for truly-childless nodes (M9 enumerated-but-not-introspected). A
                // name that simply doesn't match is mid-typing — never banner, never spam.
                if (schemaNodeEff != null && schemaTables.isEmpty()) {
                    val cat = bannerFqn.substringBeforeLast('.', "").takeIf { it.isNotBlank() }
                    val sch = bannerFqn.substringAfterLast('.')
                    dev.sort.doris.pipes.DorisPipesAutoIntrospect.request(
                        file.project, local, cat, sch, schemaNodeEff)
                    // One stable message whether this call kicked the refresh or an earlier one did
                    // (round 18: alternating texts flickered the banner on every completion).
                    dev.sort.doris.pipes.DorisPipesNotificationProvider.reportMiss(
                        file.project, vf,
                        "Doris Pipes: introspecting '$bannerFqn'\u2026 column completion lights up " +
                            "when it finishes (if nothing appears, introspect it in the Database view).")
                    return@recoverEditorOperation null
                }
                val table = schemaTables.firstOrNull { it.name.equals(want.third, true) }
                    as? com.intellij.database.model.DasTable ?: return@recoverEditorOperation null
                val cols = com.intellij.database.util.DasUtil.getColumns(table).map { it.name }.toList()
                if (cols.isEmpty()) {
                    dev.sort.doris.pipes.DorisPipesAutoIntrospect.request(
                        file.project, local, want.first, want.second ?: table.name, table)
                    dev.sort.doris.pipes.DorisPipesNotificationProvider.reportMiss(
                        file.project, vf,
                        "Doris Pipes: introspecting '${want.second}.${table.name}'\u2026")
                    return@recoverEditorOperation null
                }
                dev.sort.doris.pipes.DorisPipesNotificationProvider.clearMiss(file.project, vf)
                cols
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
