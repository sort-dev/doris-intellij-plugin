package dev.sort.doris.sql

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import icons.DatabaseIcons
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.util.ProcessingContext

// Kind-specific completion presentation, reusing the Database plugin's own semantic icons so it
// matches the rest of the IDE and adapts to light/dark. Presentation only — kind is never used to
// GATE which functions appear (aggregate-vs-scalar validity is too context-dependent to decide
// reliably mid-typing; gating there would risk the false-suppression bug the autopopup allowlist fixed).
private fun iconFor(kind: DorisFunctions.Kind) = when (kind) {
    DorisFunctions.Kind.AGGREGATE, DorisFunctions.Kind.WINDOW -> DatabaseIcons.Aggregate
    DorisFunctions.Kind.TABLE -> DatabaseIcons.Table
    DorisFunctions.Kind.SCALAR -> DatabaseIcons.Function
}

private fun typeTextFor(kind: DorisFunctions.Kind) = when (kind) {
    DorisFunctions.Kind.AGGREGATE -> "Doris aggregate"
    DorisFunctions.Kind.WINDOW -> "Doris window function"
    DorisFunctions.Kind.TABLE -> "Doris table function"
    DorisFunctions.Kind.SCALAR -> "Doris function"
}

/**
 * Offers Doris built-in function names (from the docs/registry-generated list, DorisFunctions.NAMES)
 * in code completion. DataGrip resolves/completes functions against the introspected data-source
 * model, ignoring the dialect's builtin-function registry, so Doris built-ins must be contributed
 * explicitly here — the pattern the StarRocks and TDengine dialect plugins use.
 *
 * Additionally completes the table-valued-function surface ([DorisTableFunctions]):
 *  - TVF names not already covered by the generated list (e.g. `iceberg_meta`);
 *  - documented PROPERTY KEYS inside a TVF call's parens (`tasks("<caret>` -> `type`);
 *  - closed enum VALUES for a key on the right of `=` (`tasks("type"="<caret>` -> insert|mv).
 */
class DorisCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), FunctionProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), TvfArgumentProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PipeStageKeywordProvider)
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), PipeColumnProvider)
    }

    /**
     * PIPES SPIKE: heuristic COLUMN completion inside a pipe statement — the lenient pipe token
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
            runCatching {
                val console = dev.sort.doris.pipes.DorisPipesUi.consoleFor(file.project, file)
                    ?: return@runCatching null
                val local = console.session.connectionPoint.dataSource
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.delegate === local || it.uniqueId == local.uniqueId }
                    ?: return@runCatching null
                val roots = dataSource.model.modelRoots.toList()
                val ns = runCatching {
                    generateSequence(console.currentNamespace) { it.parent }
                        .mapNotNull { it.name.takeIf(String::isNotBlank) }.toList().reversed()
                }.getOrDefault(emptyList())
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
                    ?: schema) as? com.intellij.database.model.DasTable ?: return@runCatching null
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
            // `|> AS e` — the piped relation: its columns are the stage scope at the caret.
            if (PIPE_AS.findAll(chunkText).any { it.groupValues[1].equals(qual, true) }) {
                val scope = dev.sort.doris.pipes.DorisPipes
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
            for (m in JOIN_REL.findAll(chunkText)) {
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
            runCatching {
                val m = Regex("""\b(?:FROM|JOIN)\s+([\w`.]*)$""", RegexOption.IGNORE_CASE)
                    .find(chunkText.substring(0, rel)) ?: return false
                val parents = m.groupValues[1].split('.').map { it.trim('`') }.dropLast(1)
                val console = dev.sort.doris.pipes.DorisPipesUi.consoleFor(file.project, file) ?: return true
                val local = console.session.connectionPoint.dataSource
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.delegate === local || it.uniqueId == local.uniqueId }
                    ?: return true
                val roots = dataSource.model.modelRoots.toList()
                val nsFirst = runCatching {
                    generateSequence(console.currentNamespace) { it.parent }
                        .mapNotNull { it.name.takeIf(String::isNotBlank) }.toList().lastOrNull()
                }.getOrNull()
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
            if (!dev.sort.doris.pipes.DorisPipes.enabled) return
            val file = parameters.originalFile
            if (!file.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            val text = file.text
            val offset = parameters.offset.coerceAtMost(text.length)
            val chunk = dev.sort.doris.pipes.DorisPipes.chunkAt(text, offset) ?: return
            if (!dev.sort.doris.pipes.DorisPipes.looksLikePipeChunk(chunk.text)) return

            val sink = result.caseInsensitive()
            val rel = (offset - chunk.startOffset).coerceIn(0, chunk.text.length)

            // Table-path position (FROM head or any JOIN stage): offer path segments, not columns.
            if (offerFromPath(sink, file, chunk.text, rel)) return

            // Base relation: the FROM table's das columns (introspected model — the DbDataSource
            // wrapper, NOT the LocalDataSource, whose table list is silently empty; round 6).
            val qualified = FROM_TABLE.find(chunk.text)?.groupValues?.get(1)?.let { q ->
                q.split('.').joinToString(".") { it.trim('`') }
            }
            val dasColumns: List<String>? = runCatching {
                val parts = qualified?.split('.') ?: return@runCatching null
                val console = dev.sort.doris.pipes.DorisPipesUi.consoleFor(file.project, file)
                    ?: return@runCatching null.also { dev.sort.doris.pipes.DorisPipes.info("columns: no console for file") }
                val local = console.session.connectionPoint.dataSource
                val facade = com.intellij.database.psi.DbPsiFacade.getInstance(file.project)
                val dataSource = facade.findDataSource(local.uniqueId)
                    ?: facade.dataSources.firstOrNull { it.delegate === local || it.uniqueId == local.uniqueId }
                    ?: return@runCatching null.also { dev.sort.doris.pipes.DorisPipes.info("columns: no DbDataSource for ${local.uniqueId}") }
                // DETERMINISTIC (user call-out): the console KNOWS its context — qualify the
                // FROM reference against the live namespace instead of name-hunting the tree.
                // currentNamespace is catalog(.schema) in our multi-catalog model; a 2-part name
                // is schema.table under the current catalog, a bare name uses the current schema.
                val nsNames = runCatching {
                    generateSequence(console.currentNamespace) { it.parent }
                        .mapNotNull { it.name.takeIf(String::isNotBlank) }.toList().reversed()
                }.getOrDefault(emptyList())
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
                    return@runCatching null
                }
                val table = schemaTables.firstOrNull { it.name.equals(want.third, true) }
                    as? com.intellij.database.model.DasTable ?: return@runCatching null
                val cols = com.intellij.database.util.DasUtil.getColumns(table).map { it.name }.toList()
                if (cols.isEmpty()) {
                    dev.sort.doris.pipes.DorisPipesAutoIntrospect.request(
                        file.project, local, want.first, want.second ?: table.name, table)
                    dev.sort.doris.pipes.DorisPipesNotificationProvider.reportMiss(
                        file.project, vf,
                        "Doris Pipes: introspecting '${want.second}.${table.name}'\u2026")
                    return@runCatching null
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
            val scope = dev.sort.doris.pipes.DorisPipes.stageScopeAt(chunk.text, rel, qualified, dasColumns)
            if (!scope.isNullOrEmpty()) {
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
            for (m in AS_ALIAS.findAll(chunk.text.substring(0, rel))) {
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

    /**
     * PIPES SPIKE (branch pipes-spike): stage-operator keywords right after a `|>`. The pipe
     * statement is a lenient token run, so the platform offers nothing there itself. Textual gate:
     * only immediately after `|>` (optionally one or two partial words in), never elsewhere.
     */
    private object PipeStageKeywordProvider : CompletionProvider<CompletionParameters>() {
        private val STAGE_KEYWORDS = listOf(
            "WHERE", "SELECT", "EXTEND", "SET", "DROP", "RENAME", "AGGREGATE", "DISTINCT",
            "ORDER BY", "LIMIT", "JOIN", "LEFT JOIN", "CROSS JOIN", "UNION ALL", "INTERSECT",
            "EXCEPT", "WINDOW", "PIVOT", "UNPIVOT", "TABLESAMPLE", "AS", "CALL",
        )
        // First stage word only — once a complete keyword + space is typed, columns take over.
        // Second word allowed only for the multi-word keywords (ORDER BY / LEFT JOIN / ...).
        private val AFTER_PIPE =
            Regex("""\|>\s*(?:[A-Za-z]*|(?:ORDER|LEFT|CROSS|UNION)\s+[A-Za-z]*)$""", RegexOption.IGNORE_CASE)

        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            if (!dev.sort.doris.pipes.DorisPipes.enabled) return
            if (!parameters.originalFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            val text = parameters.originalFile.text
            val offset = parameters.offset.coerceAtMost(text.length)
            val tail = text.substring((offset - 120).coerceAtLeast(0), offset)
            if (!AFTER_PIPE.containsMatchIn(tail)) return
            val sink = result.caseInsensitive()
            for (kw in STAGE_KEYWORDS) {
                sink.addElement(
                    PrioritizedLookupElement.withPriority(
                        LookupElementBuilder.create(kw).bold().withTypeText("pipe stage", true),
                        90.0,
                    ),
                )
            }
        }
    }

    private object FunctionProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            if (!parameters.originalFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            // Dogfood 2026-07-08 P2 (0.4.0): never offer functions after a qualifier. At
            // `v.<caret>` the caret's reference expression has `v` as its qualifier, and only
            // MEMBERS of the qualified relation apply there — Doris scalar/table functions are
            // never schema- or alias-qualified, so offering `mid` (accepting yields `v.mid()`)
            // is always wrong. Bare positions (`SELECT <caret>`) keep the full function list.
            val ref = PsiTreeUtil.getParentOfType(parameters.position, SqlReferenceExpression::class.java, false)
            if (ref?.qualifierExpression != null) return
            // Dogfood 2026-07-08 P1 (0.5.0): a digit-led prefix never begins a function name, so a
            // substring matcher offering `sha1`/`log10` for prefix `1` (breaking `GROUP BY 1`) is
            // pure over-reach — withhold the whole list even on explicit invoke.
            if (DorisExpressionPosition.prefixIsDigitLed(parameters.originalFile, parameters.offset)) return
            // ALLOWLIST the autopopup: functions default to explicit-invoke-only and pop up
            // automatically only in positively-detected expression positions (see
            // DorisExpressionPosition). invocationCount == 0 is the auto-popup; >= 1 is Ctrl+Space,
            // which always gets the full list. This is per-contributor, so it leaves the platform's
            // own table/column autopopup (e.g. after FROM) untouched.
            if (parameters.invocationCount == 0 &&
                !DorisExpressionPosition.isFunctionAutopopupPosition(parameters.originalFile, parameters.offset)
            ) return
            // Doris function names are case-insensitive, so match regardless of the IDE's
            // "Match case" setting (otherwise typing AB... won't complete a lowercase 'abs').
            // Each item carries a kind-specific icon + label (scalar / aggregate / window / table)
            // from the catalog — presentation only; we never *gate* on kind (aggregate-vs-scalar
            // validity is too context-dependent to decide reliably mid-typing).
            val sink = result.caseInsensitive()
            for ((upperName, kind) in DorisFunctions.BY_NAME) {
                sink.addElement(
                    LookupElementBuilder.create(upperName.lowercase())
                        .withIcon(iconFor(kind))
                        .withTypeText(typeTextFor(kind), true)
                        .withInsertHandler(CALL_PARENS)
                )
            }
            // TVF names missing from the generated catalog (registry names are FROM-queryable only;
            // the non-queryable stream-load functions are never registered).
            for (name in DorisTableFunctions.allNames) {
                if (name.uppercase() in DorisFunctions.NAMES) continue
                sink.addElement(
                    LookupElementBuilder.create(name.lowercase())
                        .withIcon(iconFor(DorisFunctions.Kind.TABLE))
                        .withTypeText(typeTextFor(DorisFunctions.Kind.TABLE), true)
                        .withInsertHandler(CALL_PARENS)
                )
            }
        }
    }

    /**
     * Property-key / enum-value completion inside the parens of a registered TVF call.
     *
     * Both the `"key"` and `"value"` positions are STRINGS in Doris SQL, so the caret is usually
     * inside a (possibly unterminated) quoted token; the platform's identifier-based prefix does
     * not apply there. We recompute the prefix from the quote character to the caret and offer the
     * bare names; at a bare (unquoted) position the insert handler wraps the name in quotes.
     */
    private object TvfArgumentProvider : CompletionProvider<CompletionParameters>() {
        override fun addCompletions(
            parameters: CompletionParameters,
            context: ProcessingContext,
            result: CompletionResultSet
        ) {
            if (!parameters.originalFile.language.isKindOf(DorisSqlDialect.INSTANCE)) return
            val position = parameters.position
            val offset = parameters.offset

            // Enclosing registered TVF call with the caret inside its argument parens (shared with
            // DorisTvfAutoPopupConfidence so autopopup and the provider agree on the context).
            val call = DorisTableFunctions.callWithCaretInArgs(position, offset) ?: return
            val tvf = DorisTableFunctions.byName(call.nameElement?.name) ?: return

            // Value position = caret inside/after the right operand of a `lhs = rhs` argument.
            val binary = PsiTreeUtil.getParentOfType(position, SqlBinaryExpression::class.java)
            val inValuePosition = binary != null &&
                call.textRange.contains(binary.textRange) &&
                binary.lOperand != null && offset > binary.lOperand!!.textRange.endOffset &&
                binary.text.substring(0, (offset - binary.textRange.startOffset).coerceIn(0, binary.textLength)).contains('=')

            val candidates: List<String> = if (inValuePosition) {
                val key = binary!!.lOperand?.text?.trim('\'', '"', '`')
                tvf.key(key)?.values ?: return
            } else {
                tvf.keys.map { it.name }
            }
            if (candidates.isEmpty()) return

            // Prefix: strip the opening quote when the caret sits inside a quoted token.
            val leafText = position.text ?: ""
            val leafStart = position.textRange.startOffset
            val typed = if (offset > leafStart) leafText.take(offset - leafStart) else ""
            val quoted = typed.isNotEmpty() && typed.first() in "'\"`"
            val prefix = if (quoted) typed.drop(1) else typed.takeWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
            val sink = result.withPrefixMatcher(prefix).caseInsensitive()

            val typeText = if (inValuePosition) "${tvf.name} value" else "${tvf.name} property"
            for (name in candidates) {
                var element = LookupElementBuilder.create(name)
                    .withIcon(AllIcons.Nodes.Parameter)
                    .withTypeText(typeText, true)
                if (!quoted) element = element.withInsertHandler(WRAP_IN_QUOTES)
                // Rank above the ~900 general function names — inside a TVF's parens the
                // documented property keys are what the user is after (the lookup is also
                // hard-capped at 500 variants, which would otherwise drop these entirely).
                sink.addElement(PrioritizedLookupElement.withPriority(element, 100.0))
            }
        }

        /** At a bare position, insert `"name"` instead of `name` (Doris properties are strings). */
        private val WRAP_IN_QUOTES = InsertHandler<LookupElement> { ctx, item ->
            val doc = ctx.document
            doc.replaceString(ctx.startOffset, ctx.tailOffset, "\"${item.lookupString}\"")
            ctx.editor.caretModel.moveToOffset(ctx.startOffset + item.lookupString.length + 2)
        }
    }

    private companion object {
        // Insert "()" after the function name and place the caret between them (unless one is already there).
        private val CALL_PARENS = InsertHandler<LookupElement> { context, _ ->
            val offset = context.tailOffset
            val doc = context.document
            val alreadyHasParen = offset < doc.textLength && doc.charsSequence[offset] == '('
            if (!alreadyHasParen) {
                doc.insertString(offset, "()")
            }
            context.editor.caretModel.moveToOffset(offset + 1)
        }
    }
}
