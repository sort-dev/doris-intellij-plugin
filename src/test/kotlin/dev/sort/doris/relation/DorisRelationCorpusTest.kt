package dev.sort.doris.relation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.model.DasColumn
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbDataSource
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.symbols.DasColumnSymbol
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.database.SqlDataSourceManager
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlNameElement
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.DataSourceProvider
import com.intellij.sql.symbols.virtual.DasVirtualSymbol
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.intellij.*
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.shape.ColumnShape
import dev.brikk.house.sql.shape.Shape
import dev.brikk.house.sql.shape.ShapeCatalog
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.generator.UnsupportedError
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.brikk.BrikkDorisContext
import dev.sort.doris.pipes.DorisPipesEngine
import dev.sort.doris.pipes.DorisPipes
import dev.sort.doris.pipes.DorisPipesSettings
import dev.sort.doris.sql.DorisRelationScope
import dev.brikk.house.sql.intellij.relation.ExternalRelationFacts
import dev.brikk.house.sql.intellij.relation.RelationFactsResult
import dev.brikk.house.sql.intellij.relation.RelationColumnLabels
import dev.brikk.house.sql.intellij.relation.relationDiagnostics
import dev.brikk.house.sql.intellij.completion.SqlLookupKind
import dev.brikk.house.sql.scope.*
import dev.sort.doris.sql.DorisRelationNavigation
import dev.sort.doris.sql.DorisSqlDialect
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.apache.doris.sqlparser.DorisSqlParser
import org.apache.doris.nereids.exceptions.ParseException as DorisParseException

class DorisRelationCorpusTest : BasePlatformTestCase() {
    private val facts by lazy { Json.parseToJsonElement(resource("facts.json")).jsonObject }
    private val columns get() = facts.getValue("columns").jsonObject
    private val shapes get() = facts.getValue("facts").jsonObject
    private val cases by lazy { resource("cases.jsonl").lineSequence().filter(String::isNotBlank)
        .map { Json.parseToJsonElement(it).jsonObject }.toList() }
    private val witnesses by lazy { Json.parseToJsonElement(resource("witnesses.json")).jsonObject }
    private val brikk get() = System.getProperty("test.kotlin") == "present"
    private lateinit var errors: BackgroundErrors
    private var contracts = emptyMap<String, ShapeResult>()
    private val declarations = LinkedHashMap<String, PsiElement>()
    private val locations = LinkedHashMap<String, SourceLocation>()
    private var physicalColumns = emptySet<String>()
    private var physicalFacts = emptyMap<Pair<String, String>, JsonObject>()
    private var dataSources = emptyList<DbDataSource>()
    private var previousPipes = false
    private var previousCatalogs: String? = null
    private var previousAutocomplete = false
    private var scopeFacts = RelationFacts()
    private var completingColumns = emptyList<ScopeColumn>()
    private val suppliedColumns = java.util.IdentityHashMap<ColumnFact, JsonObject>()

    override fun setUp() {
        super.setUp()
        errors = BackgroundErrors(testRootDisposable)
        previousAutocomplete = CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION
        CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = false
        previousPipes = project.service<DorisPipesSettings>().enabled
        previousCatalogs = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "false")
        project.service<DorisPipesSettings>().enabled = true
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        if (brikk) InjectedLanguageManager.getInstance(project).registerMultiHostInjector(object : MultiHostInjector {
            override fun elementsToInjectIn() = listOf(KtStringTemplateExpression::class.java)
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                if (!context.containingFile.name.startsWith("RelationCase-")) return
                val host = context as KtStringTemplateExpression
                registrar.startInjecting(DorisSqlDialect.INSTANCE)
                    .addPlace(null, null, host, TextRange(3, host.textLength - 3)).doneInjecting()
            }
        }, testRootDisposable)
        DataSourceProvider.EP_NAME.point.registerExtension(object : DataSourceProvider {
            override fun getDataSources(element: PsiFile) = dataSources
        }, testRootDisposable)
        val providers = com.intellij.openapi.extensions.ExtensionPointName.create<ExternalRelationFacts>("dev.sort.doris-intellij-plugin.relationFacts")
        providers.point.registerExtension(ExternalRelationFacts.of(
            labels = RelationColumnLabels(declared = "brikk column", declaredKind = SqlLookupKind.BRIKK_COLUMN),
            navigationTarget = { _, provenance -> declarations[provenance] },
        ) { RelationFactsResult.Available(scopeFacts.copy(tables = emptyMap()), scopeFacts.hashCode().toLong()) }, testRootDisposable)
        providers.point.registerExtension(ExternalRelationFacts.of(platformResolves = true,
            navigationTarget = { _, provenance -> physicalTarget(provenance) },
        ) { RelationFactsResult.Available(RelationFacts(tables = scopeFacts.tables, catalog = scopeFacts.catalog), scopeFacts.hashCode().toLong()) }, testRootDisposable)
        val ids = columns.values.filter { it.jsonObject.string("origin") != "PHYSICAL_TABLE" }
            .mapNotNull { it.jsonObject.stringOrNull("navigationId") }.distinct()
        val navigationFile = if (brikk) myFixture.addFileToProject("corpus/Declarations.kt", ids.joinToString("\n") {
            "val ${declarationName(it)} = 0"
        }) else myFixture.addFileToProject("corpus/Declarations.xml", "<columns>" +
            ids.joinToString("\n") { id ->
                val name = columns.values.map { it.jsonObject }.first { it.stringOrNull("navigationId") == id }.string("name")
                "<column id=\"$id\">$name</column>"
            } + "</columns>")
        val properties: Map<String?, PsiElement> = if (brikk) {
            PsiTreeUtil.findChildrenOfType(navigationFile, KtProperty::class.java).associateBy { it.name }
        } else {
            PsiTreeUtil.findChildrenOfType(navigationFile, com.intellij.psi.xml.XmlTag::class.java)
                .filter { it.name == "column" }.associate { tag ->
                    declarationName(checkNotNull(tag.getAttributeValue("id"))) to tag.value.textElements.single()
                }
        }
        for (id in ids) {
            val declaration = properties.getValue(declarationName(id))
            declarations[id] = declaration
            locations[id] = SourceLocation(navigationFile.virtualFile.url, declaration.textRange.startOffset, declaration.textRange.endOffset)
        }
    }

    override fun tearDown() = RunAll(
        { LookupManager.getInstance(project).hideActiveLookup() },
        { CodeInsightSettings.getInstance().AUTOCOMPLETE_ON_CODE_COMPLETION = previousAutocomplete },
        { project.service<DorisPipesSettings>().enabled = previousPipes },
        { previousCatalogs?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { PlatformTestUtil.dispatchAllEventsInIdeEventQueue() },
        { errors.awaitScopes() },
        { errors.assertNone() },
        { super.tearDown() },
    ).run()

    fun testCurrentRelationImplementationsAgainstAcceptanceCorpus() {
        assertEquals("2.2", facts.string("version"))
        assertEquals(177, cases.size)
        assertEquals(cases.size, cases.map { it.string("id") }.toSet().size)
        val ledger = DorisRelationKnownDifferences.entries
        val applicable = cases.filter { JsonPrimitive("doris") in it.array("dialects") }
        assertTrue(ledger.keys.all { key -> applicable.any { it.string("id") == key.substringBefore('/') } })
        val duckdb = Json.parseToJsonElement(resource("duckdb-disagreements.json")).jsonObject.getValue("shared").jsonObject
        val evidence = Json.parseToJsonElement(resource("doris-evidence.json")).jsonObject
        val differences = ArrayList<JsonObject>()
        val results = ArrayList<JsonObject>()
        val resolved = ArrayList<String>()
        val hookReports = LinkedHashMap<String, JsonElement>()
        val syntaxReports = LinkedHashMap<String, JsonElement>()
        val stageReports = LinkedHashMap<String, JsonElement>()
        val witnessReports = verifyWitnesses(applicable)
        for (case in applicable) {
            val id = case.string("id")
            val progress = Path.of("build", "relation-corpus", "progress-${lane()}.txt")
            Files.createDirectories(progress.parent)
            Files.writeString(progress, id)
            val sql = case.string("sql")
            val pipe = "|>" in sql
            contracts = (case.getValue("relations").jsonObject + case.getValue("callContracts").jsonObject)
                .mapValues { (_, value) -> contract(value.jsonPrimitive.content) }
            scopeFacts = copiedFacts(case)
            val expected = expected(case)
            val actual = withPhysicalTables(case) {
                val file = configure(id, sql)
                if (!pipe) syntaxReports[id] = nativeSyntax(sql)
                if (id == "pipe-star-join") {
                    val fragment = SqlFragment(sql, "doris")
                    val slots = contracts.mapNotNull { (name, result) ->
                        val shape = (result as? ShapeResult.Available)?.shape ?: return@mapNotNull null
                        name to Shape(shape.columns.map { column -> ColumnShape(column.name, "UNKNOWN", when (column.nullability) {
                            ColumnNullability.NON_NULL -> false; ColumnNullability.NULLABLE -> true; ColumnNullability.UNKNOWN -> null
                        }) })
                    }.toMap().filterKeys { it in fragment.tableSlots }
                    stageReports[id] = buildJsonObject {
                        put("slots", strings(fragment.tableSlots))
                        put("names", JsonArray(fragment.stageShapes(ShapeCatalog(emptyMap(), slots)).map { strings(it.names()) }))
                    }
                }
                if (!pipe) hookReports[id] = JsonArray(PsiTreeUtil.findChildrenOfType(file, SqlFunctionCallExpression::class.java)
                    .map { call ->
                        val contract = slotContract(call)
                        if (contract != null) assertEquals("dev.sort.doris.sql.DorisBrikkFunctionCall", call.javaClass.name)
                        val type = call.dasType as? SqlTableType
                        buildJsonObject {
                            put("call", call.text); put("implementation", call.javaClass.name); put("slot", contract != null)
                            put("columns", JsonArray((0 until (type?.columnCount ?: 0)).map { index -> buildJsonObject {
                                put("name", type!!.getColumnName(index))
                                val source = type.getSourceColumnElement(index) ?: type.getColumnElement(index)
                                put("sourceKind", source.javaClass.name)
                                put("navigationId", nullable(idOf(source.navigationElement)))
                            } }))
                        }
                    })
                val references = case.array("references").map { it.jsonObject }
                val observed = linkedMapOf<String, JsonElement>(
                    "references" to JsonArray(references.map { reference -> reference(reference, sharedTargets(sql, reference)) }),
                    "resolve" to JsonArray(references.map { reference -> reference(reference, nativeTargets(file, reference.int("start"))) }),
                    "navigation" to JsonArray(references.map { reference -> navigationReference(reference, navigation(file, reference.int("start"))) }),
                    "diagnostics" to diagnostics(file, case),
                )
                case["declarationRanges"]?.let { observed["declarationRanges"] = declarationRanges(file, case) }
                val scopes = ArrayList<JsonObject>()
                val completions = ArrayList<JsonObject>()
                val orders = ArrayList<JsonObject>()
                for (caret in case.array("carets").map { it.jsonObject }) {
                    val offset = caret.int("offset")
                    assertTrue("$id caret", offset in 0..sql.length)
                    // Preserve the corpus's repaired-PSI setup before opening the fresh lookup fixture.
                    configure(id, sql.substring(0, offset) + "__brikk_caret" + sql.substring(offset))
                    val scope = sharedScope(sql, offset)
                    scopes += JsonObject(scope - "orderedNames")
                    if (caret.string("order") == "SIGNIFICANT") orders += buildJsonObject {
                        put("offset", offset); put("columns", scope.getValue("orderedNames"))
                    }
                    configure(id, sql.substring(0, offset) + "<caret>" + sql.substring(offset))
                    completingColumns = RelationScope.complete(sql, "doris", scopeFacts, offset).items.mapNotNull { it.column }
                    completions += buildJsonObject {
                        put("offset", offset)
                        myFixture.completeBasic()
                        put("columns", sorted(myFixture.lookupElements.orEmpty().mapNotNull(::completion)))
                    }
                    LookupManager.getInstance(project).hideActiveLookup()
                }
                observed["scope"] = JsonArray(scopes)
                observed["completion"] = JsonArray(completions)
                if (orders.isNotEmpty()) observed["order"] = JsonArray(orders)
                case["coreOrder"]?.let { observed["coreOrder"] = coreOrder(case) }
                case["coreRefusal"]?.let { observed["coreRefusal"] = coreRefusal(sql) }
                case["coreExecution"]?.let { observed["coreExecution"] = coreExecution(case) }
                observed
            }
            val allowed = ledger.keys.filter { it.substringBefore('/') == id }.map { it.substringAfter('/') }.toSet()
            assertTrue(id, allowed.all { it in expected.keys })
            val duckAxes = duckdb[id]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            val axes = ArrayList<JsonObject>()
            for ((axis, wanted) in expected) {
                val got = actual.getValue(axis)
                val equal = wanted == got
                axes += buildJsonObject { put("axis", axis); put("expected", wanted); put("actual", got); put("matches", equal) }
                if (equal) {
                    if (axis in allowed) resolved += "$id/$axis"
                } else {
                    differences += buildJsonObject {
                        put("id", id); put("category", case.string("category")); put("axis", axis); put("input", sql)
                        put("mode", if (pipe) "PIPE" else "ordinary SQL")
                        put("expected", wanted); put("actual", got); put("known", axis in allowed)
                        ledger["$id/$axis"]?.let { entry ->
                            put("classification", entry.classification); put("reason", entry.reason)
                        }
                        put("duckdbKnownDifference", axis in duckAxes)
                        put("judgement", facts.getValue("evidence").jsonObject.getValue(case.string("category")))
                        evidence.getValue("priority").jsonObject[id]?.let { put("dorisJudgement", it) }
                    }
                }
            }
            results += buildJsonObject {
                put("id", id); put("input", sql); put("axes", JsonArray(axes))
                hookReports[id]?.let { put("nativeHooks", it) }
                syntaxReports[id]?.let { put("nativeSyntax", it) }
                stageReports[id]?.let { put("coreStageShapes", it) }
                put("positions", JsonArray(case.array("carets").map { it.jsonObject.getValue("positionKind") }))
                put("changes", case.getValue("changes"))
                case.getValue("engine").jsonObject["doris"]?.takeUnless { it == JsonNull }?.let {
                    put("witness", it); put("nativeWitness", witnessReports.getValue(it.jsonPrimitive.content))
                }
            }
            errors.assertNone()
        }
        val report = buildJsonObject {
            put("corpusSize", cases.size); put("disagreementCount", differences.size)
            put("applicableCases", applicable.size)
            put("notApplicable", strings((cases - applicable.toSet()).map { it.string("id") }))
            put("nativeWitnesses", JsonObject(witnessReports))
            put("disagreeingCases", differences.map { it.string("id") }.toSet().size)
            put("resolvedKnownDisagreements", strings(resolved))
            put("representationNotes", facts.getValue("representationNotes"))
            put("dorisEvidence", evidence)
            put("adapter", "Unchanged v2.2 Doris SQL and expectations. Fixture facts enter the production RelationScopeProfile providers, with native and Kotlin declaration targets. References and scope observe the shared analyzer; resolve and declaration ranges still observe platform PSI, navigation uses the registered shared handler, diagnostics use the shared relation feature, and completion reads actual lookupElements. Physical completion and resolve use real offline DDL models. FE witnesses prove syntax only; server rows remain unverified.")
            put("lane", if (brikk) "Kotlin raw-string injection with Kotlin declaration targets" else "Native Doris SQL files with foreign XML navigation targets; Kotlin absent")
            put("categories", JsonObject(differences.groupBy { it.string("category") }.mapValues { JsonArray(it.value) }))
            put("categorySummary", JsonObject(applicable.groupBy { it.string("category") }.mapValues { (category, entries) ->
                val mismatches = differences.filter { it.string("category") == category }
                buildJsonObject {
                    put("cases", entries.size); put("disagreements", mismatches.size)
                    put("disagreeingCases", mismatches.map { it.string("id") }.toSet().size)
                }
            }))
            put("results", JsonArray(results))
        }
        val output = Path.of("build", "relation-corpus", "doris-${lane()}.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), report))
        println("Doris relation corpus: ${cases.size} cases, ${differences.size} disagreements in ${differences.map { it.string("id") }.toSet().size} cases, ${resolved.size} resolved; $output")
        DorisRelationKnownDifferences.verify(differences.map { "${it.string("id")}/${it.string("axis")}" }.toSet())
    }

    fun testOrdinarySqlNavigationAndHighlightingSamples() {
        myFixture.enableInspections(dev.sort.doris.sqlResolveInspection(), com.intellij.sql.inspections.SqlAmbiguousColumnInspection())
        val platformCases = DorisRelationKnownDifferences.entries.filterValues { it.classification == "platform" }
            .keys.map { it.substringBefore('/') }.toSet()
        val sampleIds = setOf("ordinary-group-alias", "ordinary-set-union-all", "set-nullability-names-intersect",
            "set-nullability-names-except", "natural-merged-star")
        assertTrue(platformCases.containsAll(sampleIds))
        val reports = cases.filter { it.string("id") in sampleIds }.map { case ->
            val id = case.string("id")
            val progress = Path.of("build", "relation-corpus", "editor-impact-progress-${lane()}.txt")
            Files.createDirectories(progress.parent)
            Files.writeString(progress, id)
            contracts = (case.getValue("relations").jsonObject + case.getValue("callContracts").jsonObject)
                .mapValues { (_, value) -> contract(value.jsonPrimitive.content) }
            scopeFacts = copiedFacts(case)
            withPhysicalTables(case) {
                // Audit the user's ordinary-SQL editor, not the corpus's synthetic Kotlin wrapper.
                val file = myFixture.configureByText("EditorImpact-$id.sql", case.string("sql"))
                val manager = InjectedLanguageManager.getInstance(project)
                fun editorOffset(offset: Int) = if (manager.isInjectedFragment(file)) manager.injectedToHost(file, offset) else offset
                val highlights = myFixture.doHighlighting().filter { it.severity == com.intellij.lang.annotation.HighlightSeverity.ERROR }
                val core = RelationScope.analyze(case.string("sql"), "doris", scopeFacts)
                buildJsonObject {
                    put("id", id); put("sql", case.getValue("sql"))
                    put("errors", JsonArray(highlights.map { info -> buildJsonObject {
                        put("message", info.description); put("start", info.startOffset); put("end", info.endOffset)
                    } }))
                    put("columnErrorsNotDiagnosedBySharedAnalysis", JsonArray(highlights.filter { info ->
                        val message = info.description.orEmpty()
                        (message.contains("Unable to resolve column") || message.contains("Unknown column") || message.contains("Ambiguous column")) &&
                            core.diagnostics.none { info.startOffset == editorOffset(it.start) && info.endOffset == editorOffset(it.end) }
                    }.map { JsonPrimitive(it.description) }))
                    put("navigation", JsonArray(case.array("references").map { value ->
                        val probe = value.jsonObject
                        val offset = probe.int("start")
                        val targets = com.intellij.codeInsight.navigation.actions.GotoDeclarationAction.findAllTargetElements(
                            project, myFixture.editor, editorOffset(offset),
                        ).orEmpty()
                        buildJsonObject {
                            put("start", offset); put("end", probe.int("end"))
                            put("expectedSourceIds", strings(probe["navigationTargets"]?.jsonArray?.map { it.jsonPrimitive.content }
                                ?: probe.array("targets").mapNotNull { expectedColumn(it).stringOrNull("navigationId") }))
                            put("actionTargets", JsonArray(targets.map { target -> buildJsonObject {
                                put("sourceId", nullable(idOf(target))); put("text", target.text)
                                put("file", target.containingFile?.name)
                                put("kind", target.javaClass.name)
                                put("start", target.textRange?.startOffset?.let(::JsonPrimitive) ?: JsonNull)
                                put("end", target.textRange?.endOffset?.let(::JsonPrimitive) ?: JsonNull)
                                put("navigationSourceId", nullable(idOf(target.navigationElement)))
                                put("navigationText", target.navigationElement.text)
                            } }))
                        }
                    }))
                }
            }
        }
        val output = Path.of("build", "relation-corpus", "editor-impact-${lane()}.json")
        Files.createDirectories(output.parent)
        Files.writeString(output, Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), JsonArray(reports)))
        assertEquals(sampleIds.size, reports.size)
        assertTrue("Unexpected column errors in ordinary-SQL samples; see $output",
            reports.all { it.getValue("columnErrorsNotDiagnosedBySharedAnalysis").jsonArray.isEmpty() })
        errors.assertNone()
    }

    private fun lane() = "${ApplicationInfo.getInstance().build.baselineVersion}-${if (brikk) "brikk" else "native"}"
    private fun declarationName(id: String) = id.replace(Regex("[^A-Za-z0-9_]"), "_")

    private fun resource(name: String) = Files.readString(Path.of(System.getProperty("corpus.dir"))
        .resolveSibling("relation-corpus").resolve(name))

    private fun contract(id: String): ShapeResult {
        val shape = shapes.getValue(id).jsonObject
        if (shape.string("knowledge") == "UNKNOWN") return ShapeResult.Unavailable("corpus unknown input")
        val copied = shape.array("columns").map { key ->
            val column = columns.getValue(key.jsonPrimitive.content).jsonObject
            val navigation = column.stringOrNull("navigationId")?.let { target -> object : NavigationTarget {
                override val kind = if (column.string("origin") == "RELATION_CONTRACT") NavigationKind.RELATION_FALLBACK else NavigationKind.PROPERTY
                override val location get() = locations.getValue(target)
                override fun navigate(requestFocus: Boolean) {
                    val declaration = declarations.getValue(target)
                    OpenFileDescriptor(project, declaration.containingFile.virtualFile, declaration.textOffset).navigate(requestFocus)
                }
            } }
            ColumnContract(column.string("name"), column.stringOrNull("kotlinType"),
                ColumnNullability.valueOf(column.string("nullability")), BrikkTypeFamilies.forClassId(column.stringOrNull("kotlinType")), navigation)
        }
        return ShapeResult.Available(RelationShape(ShapeKnowledge.valueOf(shape.string("knowledge")), copied, ShapeOrigin.DECLARED))
    }

    private fun configure(id: String, sql: String): PsiFile {
        if (!brikk) {
            val file = myFixture.configureByText("RelationCase-$id.sql", sql)
            file.putUserData(BrikkDorisContext.PROFILE, true)
            file.putUserData(BrikkDorisContext.RELATIONS, relationProvider())
            return file
        }
        val hostFile = myFixture.configureByText("RelationCase-$id.kt", "val sql = \"\"\"$sql\"\"\"")
        val manager = InjectedLanguageManager.getInstance(project)
        val host = requireNotNull(PsiTreeUtil.findChildOfType(manager.getTopLevelFile(hostFile), KtStringTemplateExpression::class.java))
        host.putUserData(BrikkDorisContext.PROFILE, true)
        host.putUserData(BrikkDorisContext.RELATIONS, relationProvider())
        val file = requireNotNull(manager.getInjectedPsiFiles(host)?.single()).first.containingFile
        assertEquals(id, sql.replace("<caret>", ""), file.text)
        return file
    }

    private fun relationProvider() = BrikkDorisContext.RelationProvider {
        ContextResult.Available(BrikkContext(BrikkDialect.DORIS, emptyList(), emptyList(), contracts))
    }

    private fun <T> withPhysicalTables(case: JsonObject, action: () -> T): T {
        val tables = case.getValue("physicalTables").jsonObject
        physicalFacts = tables.entries.flatMap { (name, fact) ->
            shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").map { key ->
                val column = columns.getValue(key.jsonPrimitive.content).jsonObject
                (name to column.string("name")) to column
            }
        }.toMap()
        physicalColumns = tables.values.flatMap { fact -> shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns") }
            .map { columns.getValue(it.jsonPrimitive.content).jsonObject.string("name") }.toSet()
        if (tables.isEmpty()) return action()
        val ddl = tables.entries.joinToString("\n") { (name, fact) ->
            val fields = shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").joinToString(", ") { key ->
                val column = columns.getValue(key.jsonPrimitive.content).jsonObject
                "${column.string("name")} ${column.string("sqlType")}" + if (column.string("nullability") == "NON_NULL") " NOT NULL" else ""
            }
            "CREATE TABLE $name ($fields);"
        }
        val schema = myFixture.addFileToProject("corpus/${case.string("id")}-schema.sql", ddl)
        setSqlDialectMapping(project, schema.virtualFile, DorisSqlDialect.INSTANCE, testRootDisposable)
        val manager = SqlDataSourceManager.getInstance(project)
        val source = manager.createEmpty().apply {
            name = "Relation acceptance ${case.string("id")}"; definedDbms = DorisDbms.DORIS
            isAutoSync = false; setFiles(arrayOf(schema.virtualFile))
        }
        manager.addDataSource(source)
        var local: LocalDataSource? = null
        try {
            PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread { source.sync() }, 30_000)
            DbPsiFacade.getInstance(project).clearCaches()
            dataSources = listOf(requireNotNull(DbPsiFacade.getInstance(project).findDataSource(source.uniqueId)))
            local = object : LocalDataSource() { init { setUniqueId(source.uniqueId) } }.apply {
                name = "Offline ${case.string("id")}"
                databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
                url = "jdbc:offline:relation-corpus"; isAutoSynchronize = false; isKeepAlive = false
                model = source.model
            }
            LocalDataSourceManager.getInstance(project).addDataSource(local)
            DbPsiFacade.getInstance(project).clearCaches()
            return action()
        } finally {
            dataSources = emptyList()
            local?.let(LocalDataSourceManager.getInstance(project)::removeDataSource)
            manager.removeDataSource(source)
            DbPsiFacade.getInstance(project).clearCaches()
        }
    }

    private fun copiedFacts(case: JsonObject): RelationFacts {
        suppliedColumns.clear()
        fun relations(key: String) = case.getValue(key).jsonObject.mapValues { (_, value) ->
            val shape = shapes.getValue(value.jsonPrimitive.content).jsonObject
            RelationFact(shape.array("columns").map { key ->
                val column = columns.getValue(key.jsonPrimitive.content).jsonObject
                ColumnFact(column.string("name"), column.stringOrNull("sqlType"),
                    column.stringOrNull("kotlinType")?.substringAfterLast('.'), Nullability.valueOf(column.string("nullability")),
                    if (column.string("origin") == "KOTLIN_RELATION") ColumnOrigin.DECLARED else ColumnOrigin.valueOf(column.string("origin")),
                    column.stringOrNull("navigationId")).also { suppliedColumns[it] = column }
            }, Knowledge.valueOf(shape.string("knowledge")))
        }
        return RelationFacts(relations("relations"), relations("physicalTables"), relations("callContracts"),
            if (case.string("catalogKnowledge") == "CLOSED") CatalogKnowledge.CLOSED else CatalogKnowledge.OPEN)
    }

    private fun observed(column: ScopeColumn): JsonObject = buildJsonObject {
        val source = column.source?.column?.let { suppliedColumns[it] }
        val displayType = column.displayType?.let { display ->
            suppliedColumns.values.mapNotNull { it.stringOrNull("kotlinType") }.distinct()
                .singleOrNull { it.substringAfterLast('.') == display }
        }
        put("name", column.name); put("relation", column.relation)
        put("origin", if (column.origin == ColumnOrigin.DECLARED) "KOTLIN_RELATION" else column.origin.name)
        put("kotlinType", source?.getValue("kotlinType") ?: nullable(displayType))
        put("sqlType", nullable(column.sqlType)); put("nullability", column.nullability.name)
        put("navigationId", nullable(column.provenance))
        put("identity", source?.getValue("identity") ?: JsonPrimitive("computed.${column.name}"))
    }

    private fun sharedTargets(sql: String, probe: JsonObject): List<JsonObject> {
        val found = RelationScope.analyze(sql, "doris", scopeFacts).references.firstOrNull {
            it.start == probe.int("start") && it.end == probe.int("end") && it.kind.name == probe.string("kind")
        } ?: return emptyList()
        return found.columns.takeIf { found.resolution == Resolution.RESOLVED }.orEmpty().map { column ->
            val observed = observed(column)
            target(column.relation, column.name, observed.string("identity"), column.provenance)
        }
    }

    private fun sharedScope(sql: String, offset: Int): JsonObject {
        val completion = RelationScope.complete(sql, "doris", scopeFacts, offset)
        val selected = completion.items.mapNotNull { it.column }
        return buildJsonObject {
            put("offset", offset); put("knowledge", completion.knowledge?.name ?: "NONE")
            put("columns", sorted(selected.map(::observed)))
            put("orderedNames", strings(selected.map { it.name }))
        }
    }

    private fun slotContract(call: SqlFunctionCallExpression): ShapeResult? =
        if (call.parent?.node?.elementType == com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION &&
            call.parameterList?.expressionList.isNullOrEmpty() && call.nameElement?.qualifierExpression == null)
            contracts[call.nameElement?.name] else null

    private fun sourceContract(source: PsiElement?, name: String): ColumnContract? {
        val reference = source as? SqlReferenceExpression ?: PsiTreeUtil.getParentOfType(source, SqlReferenceExpression::class.java, false)
        val context = reference?.resolveSingle()?.let { it as? SqlResolveResult }
            ?.targetSymbol?.let { it as? DasVirtualSymbol }?.contextElement
        val id = idOf(context?.navigationElement) ?: idOf(reference?.reference?.resolve()?.navigationElement) ?: idOf(source?.navigationElement)
        val supplied = contracts.values.filterIsInstance<ShapeResult.Available>().flatMap { it.shape.columns }
        if (id != null) return supplied.distinctBy { listOf(it.name, it.kotlinClassId, it.nullability, idOf(it.navigation?.location)) }
            .singleOrNull { idOf(it.navigation?.location) == id && it.name.equals(name, true) }
        val call = source as? SqlFunctionCallExpression ?: return null
        return (slotContract(call) as? ShapeResult.Available)?.shape?.columns?.singleOrNull { it.name.equals(name, true) }
    }

    private fun observedColumn(name: String, relation: String, contract: ColumnContract?, physical: DasColumn?) = buildJsonObject {
        put("name", name); put("relation", relation)
        put("origin", when { physical != null -> "PHYSICAL_TABLE"; contract?.navigation?.kind == NavigationKind.RELATION_FALLBACK -> "RELATION_CONTRACT"; contract != null -> "KOTLIN_RELATION"; else -> "COMPUTED" })
        put("kotlinType", nullable(contract?.kotlinClassId)); put("sqlType", nullable(physical?.dasType?.toDataType()?.typeName?.uppercase()))
        put("nullability", contract?.nullability?.name ?: if (physical != null) { if (physical.isNotNull) "NON_NULL" else "NULLABLE" } else "UNKNOWN")
        put("navigationId", nullable(if (physical != null) physicalFact(physical)?.stringOrNull("navigationId") else idOf(contract?.navigation?.location)))
        put("identity", if (physical != null) physicalFact(physical)?.getValue("identity") ?: JsonNull else JsonPrimitive(identity(contract, name)))
    }

    private fun nativeReference(file: PsiFile, start: Int): SqlReferenceExpression? =
        PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)
            .filter { it.identifier?.textRange?.startOffset == start }.minByOrNull { it.textLength }

    private fun nativeTargets(file: PsiFile, start: Int): List<JsonObject> {
        val reference = nativeReference(file, start) ?: return emptyList()
        val resolved = reference.reference.resolve() ?: return emptyList()
        val result = reference.resolveSingle() as? SqlResolveResult
        val qualifier = reference.qualifierExpression?.text ?: (result?.immediateQualifier as? SqlNameElement)?.name
            ?: (result?.qualifier as? SqlNameElement)?.name.orEmpty()
        val symbol = resolved as? DasColumnSymbol ?: reference.resolveSymbol()
        if (symbol is DasColumnSymbol) symbol.dasObject?.let(::physicalFact)?.let { fact ->
            return listOf(target(qualifier, symbol.name, fact.string("identity"), fact.stringOrNull("navigationId")))
        }
        val context = (result?.targetSymbol as? DasVirtualSymbol)?.contextElement
        val authored = (resolved as? PsiNamedElement)?.name ?: reference.identifier?.text ?: return emptyList()
        val declaration = sourceContract(context ?: resolved, authored)
        val name = declaration?.name?.takeIf { it.equals(authored, true) } ?: authored
        return listOf(target(qualifier, name, identity(declaration, name), idOf(context?.navigationElement ?: resolved.navigationElement)))
    }

    private fun navigation(file: PsiFile, start: Int): List<String> {
        val element = file.findElementAt(start) ?: return emptyList()
        val targets = DorisRelationNavigation().getGotoDeclarationTargets(element, start, myFixture.editor)?.mapNotNull(::idOf).orEmpty()
        return targets.ifEmpty { nativeTargets(file, start).mapNotNull { it.stringOrNull("navigationId") } }.distinct()
    }

    private fun diagnostics(file: PsiFile, case: JsonObject): JsonArray {
        val core = RelationScope.analyze(case.string("sql"), "doris", scopeFacts)
        return sorted(relationDiagnostics(file, DorisRelationScope).map { diagnostic ->
            val problem = core.diagnostics.single { it.start == diagnostic.start && it.end == diagnostic.end }
            val probe = case.array("references").map { it.jsonObject }.firstOrNull {
                it.int("start") == diagnostic.start && it.int("end") == diagnostic.end
            }
            val subject = probe?.string("kind") ?: "COLUMN"
            buildJsonObject {
                put("kind", when (problem.problem) {
                    ScopeProblem.MISSING_COLUMN -> "MISSING"
                    ScopeProblem.AMBIGUOUS_COLUMN -> "AMBIGUOUS"
                    else -> problem.problem.name
                })
                put("subject", subject)
                put("start", diagnostic.start); put("end", diagnostic.end)
            }
        })
    }

    private fun completion(item: LookupElement): JsonObject? {
        val shown = LookupElementPresentation().also(item::renderElement)
        val tail = shown.tailText.orEmpty()
        // Labels intentionally name origin tables/stages when no qualifier survives. The corpus
        // compares semantic qualifiers instead; match the actual rendered item, never expected data.
        val presented = completingColumns.filter { column ->
            if (column.name != item.lookupString) return@filter false
            val labels = if (column.origin == ColumnOrigin.DECLARED || column.origin == ColumnOrigin.RELATION_CONTRACT)
                RelationColumnLabels(declared = "brikk column") else RelationColumnLabels()
            val label = labels.label(column)
            val text = "  ${label.origin}" + if (label.relation.isBlank()) "" else " (${label.relation})"
            text == tail
        }.singleOrNull()
        val symbol = (item.`object` as? SqlResolveResult)?.targetSymbol ?: item.psiElement as? DasColumnSymbol
        val physical = (symbol as? DasColumnSymbol)?.dasObject
        val origin = when {
            tail.contains("relation contract") -> "RELATION_CONTRACT"
            tail.contains("brikk column") -> "KOTLIN_RELATION"
            tail.startsWith("  column (") -> "PHYSICAL_TABLE"
            physical != null && item.lookupString == physical.name && physical.name in physicalColumns -> "PHYSICAL_TABLE"
            tail.contains("computed column") -> "COMPUTED"
            symbol?.kind == ObjectKind.COLUMN && item.lookupString == symbol.name -> "COMPUTED"
            else -> return null
        }
        val platformResult = item.`object` as? SqlResolveResult
        val relation = presented?.relation ?: if (origin == "PHYSICAL_TABLE")
            (platformResult?.immediateQualifier as? SqlNameElement)?.name ?: (platformResult?.qualifier as? SqlNameElement)?.name
                ?: (symbol as? DasColumnSymbol)?.dasObject?.table?.name.orEmpty()
        else Regex("\\(([^()]*)\\)$").find(tail)?.groupValues?.get(1)
            ?: (symbol as? DasColumnSymbol)?.dasObject?.table?.name.orEmpty()
        return completionColumn(item.lookupString, relation, origin, shown.typeText.orEmpty().let { if (origin == "PHYSICAL_TABLE") it.removeSuffix("?").uppercase() else it }, when {
            origin == "PHYSICAL_TABLE" && presented == null -> if (physical?.isNotNull == true) "NON_NULL" else "NULLABLE"
            shown.typeText.orEmpty().contains("nullability unknown") || shown.typeText in setOf("pipe column", "stage scope", "table column") -> "UNKNOWN"
            shown.typeText.orEmpty().endsWith('?') -> "NULLABLE"
            origin == "COMPUTED" && presented == null -> "UNKNOWN"
            else -> "NON_NULL"
        })
    }

    private fun expected(case: JsonObject): Map<String, JsonElement> {
        val references = case.array("references")
        val carets = case.array("carets").map { it.jsonObject }
        val resolved = JsonArray(references.map { value ->
            val reference = value.jsonObject
            reference(reference, reference.array("targets").map { value ->
                val column = expectedColumn(value)
                target(column.string("relation"), column.string("name"), column.string("identity"), column.stringOrNull("navigationId"))
            })
        })
        return linkedMapOf<String, JsonElement>(
            "references" to resolved, "resolve" to resolved,
            "navigation" to JsonArray(references.map { ref ->
                val reference = ref.jsonObject
                navigationReference(reference, reference["navigationTargets"]?.jsonArray?.map { it.jsonPrimitive.content }
                    ?: reference.array("targets").mapNotNull { expectedColumn(it).stringOrNull("navigationId") })
            }),
            "diagnostics" to sorted(case.array("diagnostics")),
            "scope" to JsonArray(carets.map { caret -> buildJsonObject {
                put("offset", caret.int("offset")); put("knowledge", caret.getValue("knowledge"))
                put("columns", sorted(caret.array("columns").map(::expectedColumn)))
            } }),
            "completion" to JsonArray(carets.map { caret -> buildJsonObject {
                put("offset", caret.int("offset"))
                put("columns", sorted((caret["completionColumns"] ?: caret.getValue("columns")).jsonArray.map { tuple ->
                    val column = expectedColumn(tuple)
                    completionColumn(column.string("name"), column.string("relation"), column.string("origin"), display(column), column.string("nullability"))
                }))
            } }),
        ).apply {
            val ordered = carets.filter { it.string("order") == "SIGNIFICANT" }
            if (ordered.isNotEmpty()) put("order", JsonArray(ordered.map { caret -> buildJsonObject {
                put("offset", caret.int("offset")); put("columns", strings(caret.array("columns").map { expectedColumn(it).string("name") }))
            } }))
            case["coreOrder"]?.let { put("coreOrder", it) }
            case["coreRefusal"]?.let { put("coreRefusal", it) }
            case["coreExecution"]?.let { put("coreExecution", it) }
            case["declarationRanges"]?.let { put("declarationRanges", it) }
        }
    }

    private fun nativeSyntax(sql: String): JsonObject {
        val masked = StringBuilder(sql)
        for (range in DorisPipes.namedParameterRanges(sql)) {
            masked.setCharAt(range.first, '0')
            for (index in (range.first + 1)..range.last) masked.setCharAt(index, ' ')
        }
        return try {
            DorisSqlParser().parseStatement(masked.toString())
            buildJsonObject { put("accepted", true) }
        } catch (failure: DorisParseException) {
            buildJsonObject { put("accepted", false); put("message", failure.message) }
        }
    }

    private fun expectedColumn(value: JsonElement): JsonObject {
        val tuple = value.jsonArray
        val column = columns.getValue(tuple[0].jsonPrimitive.content).jsonObject
        fun override(index: Int, key: String) = tuple.getOrNull(index)?.takeUnless { it == JsonNull } ?: column.getValue(key)
        return buildJsonObject {
            put("name", override(2, "name")); put("relation", tuple[1]); put("origin", override(4, "origin"))
            put("kotlinType", column.getValue("kotlinType")); put("sqlType", column.getValue("sqlType"))
            put("nullability", override(3, "nullability")); put("navigationId", override(5, "navigationId"))
            put("identity", override(6, "identity"))
        }
    }

    private fun display(column: JsonObject): String {
        val base = column.stringOrNull("kotlinType")?.substringAfterLast('.') ?: column.stringOrNull("sqlType") ?: "unknown type"
        if (column.string("origin") == "PHYSICAL_TABLE") return base
        return base + when (column.string("nullability")) { "NULLABLE" -> "?"; "UNKNOWN" -> " (nullability unknown)"; else -> "" }
    }

    private fun idOf(location: SourceLocation?) = locations.entries.firstOrNull { it.value == location }?.key
    private fun idOf(element: PsiElement?): String? = declarations.entries.firstOrNull { (_, declaration) ->
        element != null && element.containingFile === declaration.containingFile && declaration.textRange.contains(element.textRange)
    }?.key ?: physicalFacts.values.mapNotNull { it.stringOrNull("navigationId") }.distinct().firstOrNull { id ->
        element != null && physicalTarget(id)?.let { element === it || element.isEquivalentTo(it) } == true
    }

    private fun physicalTarget(provenance: String): PsiElement? = dataSources.firstNotNullOfOrNull { source ->
        com.intellij.database.util.DasUtil.getTables(source).firstNotNullOfOrNull { table ->
            com.intellij.database.util.DasUtil.getColumns(table).firstOrNull {
                physicalFact(it)?.stringOrNull("navigationId") == provenance
            }?.let(source::findElement)
        }
    }
    private fun target(relation: String, column: String, identity: String, navigation: String?) = buildJsonObject {
        put("relation", relation); put("column", column); put("identity", identity); put("navigationId", nullable(navigation))
    }
    private fun identity(contract: ColumnContract?, name: String): String {
        val id = idOf(contract?.navigation?.location)
        return columns.values.map { it.jsonObject }.firstOrNull {
            id != null && it.stringOrNull("navigationId") == id && it.string("name").equals(contract?.name, true)
        }?.string("identity") ?: "computed.$name"
    }

    private fun physicalFact(column: DasColumn): JsonObject? {
        val table = column.table ?: return null
        val schema = com.intellij.database.util.DasUtil.getSchema(table)
        return physicalFacts["$schema.${table.name}" to column.name]
            ?: physicalFacts[table.name to column.name]
            ?: physicalFacts.entries.filter { (key, _) -> key.first.substringAfterLast('.') == table.name && key.second == column.name }
                .singleOrNull()?.value
    }

    private fun declarationRanges(file: PsiFile, case: JsonObject) = JsonArray(case.array("declarationRanges").map { value ->
        val probe = value.jsonObject
        val resolved = nativeReference(file, probe.int("start"))?.reference?.resolve()
        val identifier = (resolved as? com.intellij.psi.PsiNameIdentifierOwner)?.nameIdentifier
            ?: (resolved as? SqlReferenceExpression)?.identifier ?: resolved
        val targets = if (identifier?.containingFile === file) listOf(buildJsonObject {
            put("start", identifier.textRange.startOffset); put("end", identifier.textRange.endOffset)
        }) else emptyList()
        buildJsonObject {
            put("start", probe.int("start")); put("end", probe.int("end")); put("targets", JsonArray(targets))
        }
    })

    private fun coreExecution(case: JsonObject): JsonObject {
        val input = case.getValue("engineRelations").jsonObject.entries.fold(case.string("sql")) { text, (slot, table) ->
            text.replace(Regex("\\b${Regex.escape(slot)}\\s*\\(\\s*\\)"), table.jsonPrimitive.content)
        }
        val lowered = SqlFragment(input, "doris").toExecutable("doris")
        val syntax = nativeSyntax(lowered.sql)
        return buildJsonObject {
            put("rows", JsonNull)
            put("columns", coreOrder(case))
            if (!syntax.getValue("accepted").jsonPrimitive.boolean) put("nativeSyntax", syntax)
        }
    }

    private fun coreOrder(case: JsonObject): JsonElement {
        fun shape(fact: JsonElement) = Shape(shapes.getValue(fact.jsonPrimitive.content).jsonObject.array("columns").map { value ->
            val column = columns.getValue(value.jsonPrimitive.content).jsonObject
            ColumnShape(column.string("name"), column.stringOrNull("sqlType") ?: "UNKNOWN", when (column.string("nullability")) {
                "NON_NULL" -> false; "NULLABLE" -> true; else -> null
            })
        })
        val slots = case.getValue("relations").jsonObject.mapValues { shape(it.value) }
        val tables = case.getValue("physicalTables").jsonObject.mapValues { shape(it.value) }
        return strings(SqlFragment(case.string("sql"), "doris").outputShape(ShapeCatalog(tables, slots)).names())
    }

    private fun coreRefusal(sql: String): JsonElement = JsonPrimitive(try {
        SqlFragment(sql, "doris").toExecutable("doris")
        false
    } catch (_: UnsupportedError) { true })

    private fun verifyWitnesses(applicable: List<JsonObject>): Map<String, JsonObject> {
        val fixes = Json.parseToJsonElement(resource("doris-witnesses.json")).jsonObject
        val keys = applicable.mapNotNull { it.getValue("engine").jsonObject["doris"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content }.toSet()
        assertEquals(witnesses.keys.filter { it.startsWith("doris:") }.toSet(), keys)
        assertTrue(fixes.keys.all { it in keys })
        return keys.associateWith { key ->
            val original = witnesses.getValue(key).jsonObject
            assertFalse(key, original.getValue("verified").jsonPrimitive.boolean)
            val sql = fixes[key]?.jsonObject?.string("sql") ?: original.string("sql")
            val syntax = nativeSyntax(sql)
            assertTrue("$key: $syntax", syntax.getValue("accepted").jsonPrimitive.boolean)
            buildJsonObject {
                put("sql", sql); put("originalSql", original.getValue("sql")); put("syntaxVerified", true)
                put("verified", false); put("verification", "Doris 4.1.4 FE grammar only; no server execution")
                put("rows", original["rows"] ?: JsonNull); put("failure", original["failure"] ?: JsonNull)
                fixes[key]?.let { put("correction", it) }
            }
        }
    }
    private fun reference(expected: JsonObject, targets: List<JsonObject>) = buildJsonObject {
        put("start", expected.int("start")); put("end", expected.int("end")); put("targets", sorted(targets))
    }
    private fun navigationReference(expected: JsonObject, targets: List<String>) = buildJsonObject {
        put("start", expected.int("start")); put("end", expected.int("end")); put("targets", strings(targets.sorted()))
    }
    private fun completionColumn(name: String, relation: String, origin: String, type: String, nullability: String) = buildJsonObject {
        put("name", name); put("relation", relation); put("origin", origin); put("displayType", type); put("nullability", nullability)
    }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.stringOrNull(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.array(key: String) = getValue(key).jsonArray
    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun strings(values: Collection<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun nullableStrings(values: List<String>?) = values?.let { strings(it.distinct().sorted()) } ?: JsonNull
    private fun sorted(values: Collection<JsonElement>) = JsonArray(values.sortedBy(JsonElement::toString))
}
