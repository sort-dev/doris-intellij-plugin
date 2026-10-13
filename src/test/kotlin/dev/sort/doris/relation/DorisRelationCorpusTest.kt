package dev.sort.doris.relation

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
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
import com.intellij.sql.psi.SqlQueryExpression
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
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.ShapeError
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
import dev.sort.doris.sql.BrikkRelationColumns
import dev.sort.doris.sql.BrikkRelationNavigation
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sql.DorisHighlightInfoFilter
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
    private var dataSources = emptyList<DbDataSource>()
    private var previousPipes = false
    private var previousCatalogs: String? = null
    private var previousAutocomplete = false

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
        assertEquals("2.1", facts.string("version"))
        assertEquals(156, cases.size)
        assertEquals(cases.size, cases.map { it.string("id") }.toSet().size)
        val baseline = Json.parseToJsonElement(resource("doris-disagreements.json")).jsonObject
        val shared = baseline["shared"]?.jsonObject ?: baseline
        val local = baseline[if (brikk) "brikk" else "native"]?.jsonObject.orEmpty()
        val known = (shared.keys + local.keys).associateWith { key ->
            JsonArray((shared[key]?.jsonArray.orEmpty() + local[key]?.jsonArray.orEmpty()).distinct())
        }
        val applicable = cases.filter { JsonPrimitive("doris") in it.array("dialects") }
        assertTrue(known.keys.all { id -> applicable.any { it.string("id") == id } })
        val duckdb = Json.parseToJsonElement(resource("duckdb-disagreements.json")).jsonObject.getValue("shared").jsonObject
        val evidence = Json.parseToJsonElement(resource("doris-evidence.json")).jsonObject
        val differences = ArrayList<JsonObject>()
        val results = ArrayList<JsonObject>()
        val unexpected = ArrayList<String>()
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
            val expected = expected(case).toMutableMap()
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
                        val contract = BrikkRelationColumns.slot(call)
                        if (contract != null) assertEquals("dev.sort.doris.sql.DorisBrikkFunctionCall", call.javaClass.name)
                        val type = call.dasType as? SqlTableType
                        buildJsonObject {
                            put("call", call.text); put("implementation", call.javaClass.name); put("slot", contract != null)
                            put("columns", JsonArray((0 until (type?.columnCount ?: 0)).map { index -> buildJsonObject {
                                put("name", type!!.getColumnName(index))
                                val source = type.getSourceColumnElement(index) ?: type.getColumnElement(index)
                                put("sourceKind", nullable(source?.javaClass?.name))
                                put("navigationId", nullable(idOf(source?.navigationElement)))
                            } }))
                        }
                    })
                val references = case.array("references").map { it.jsonObject }
                val observed = linkedMapOf<String, JsonElement>(
                    "references" to JsonArray(references.map { reference -> reference(reference, nativeTargets(file, reference.int("start"))) }),
                    "resolve" to JsonArray(references.map { reference -> reference(reference, nativeTargets(file, reference.int("start"))) }),
                    "navigation" to JsonArray(references.map { reference -> navigationReference(reference, navigation(file, reference.int("start"))) }),
                    "diagnostics" to diagnostics(file, case),
                )
                val scopes = ArrayList<JsonObject>()
                val completions = ArrayList<JsonObject>()
                val rawScopes = ArrayList<JsonElement>()
                val orders = ArrayList<JsonObject>()
                for (caret in case.array("carets").map { it.jsonObject }) {
                    val offset = caret.int("offset")
                    assertTrue("$id caret", offset in 0..sql.length)
                    val marked = sql.substring(0, offset) + "__brikk_caret" + sql.substring(offset)
                    val repaired = configure(id, marked)
                    val scope = if (pipe) pipeScope(repaired, sql, offset) else nativeScope(repaired, offset)
                    scopes += JsonObject(scope - "orderedNames")
                    if (caret.string("order") == "SIGNIFICANT") orders += buildJsonObject {
                        put("offset", offset); put("columns", scope.getValue("orderedNames"))
                    }
                    if (id == "pipe-star-join") rawScopes += nullableStrings(BrikkRelationColumns.pipeScope(repaired, sql, offset))
                    if (id == "physical-pipe") rawScopes += nullableStrings(DorisPipesEngine.stageScopeAt(sql, offset, "physical", physicalColumns.toList()))
                    configure(id, sql.substring(0, offset) + "<caret>" + sql.substring(offset))
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
                observed
            }
            val allowed = known[id]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
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
                        put("duckdbKnownDifference", axis in duckAxes)
                        put("judgement", facts.getValue("evidence").jsonObject.getValue(case.string("category")))
                        evidence.getValue("priority").jsonObject[id]?.let { put("dorisJudgement", it) }
                    }
                    if (axis !in allowed) unexpected += "$id/$axis"
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
            put("adapter", "Unchanged v2.1 Doris-tagged SQL with copied relation contracts on the native Doris hook. Ordinary references/resolve observe SQL PSI, scope observes SqlTableType, PIPE observes pipeScope/pipeDisplay. Completion reads lookupElements with auto-insert disabled. Unsupported PIPE identity, knowledge and navigation stay unknown. Physical tables use real DDL data sources and the same-id offline local model. Native witnesses prove FE syntax only, not rows or semantic rejection.")
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
        assertTrue("Unlisted relation disagreements: ${unexpected.joinToString()}; see $output", unexpected.isEmpty())
        assertTrue("Fixed differences must turn green: ${resolved.joinToString()}", resolved.isEmpty())
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

    private fun nativeScope(file: PsiFile, offset: Int): JsonObject {
        val ref = nativeReference(file, offset)
        val query = PsiTreeUtil.getParentOfType(ref, SqlQueryExpression::class.java)
        val qualifier = ref?.qualifierExpression?.text
        val selected = ArrayList<JsonElement>()
        var complete = false
        if (ref?.referenceElementType?.targetKind == ObjectKind.COLUMN && query != null) {
            val shadowed = HashSet<String>()
            for (scope in generateSequence(query) { PsiTreeUtil.getParentOfType(it, SqlQueryExpression::class.java) }) {
                val type = scope.tableExpression?.dasType ?: continue
                val open = PsiTreeUtil.findChildrenOfType(scope.tableExpression, SqlFunctionCallExpression::class.java).any { call ->
                    val result = BrikkRelationColumns.slot(call)
                    result != null && (result !is ShapeResult.Available || result.shape.knowledge != ShapeKnowledge.FULL)
                }
                val unknownCall = PsiTreeUtil.findChildrenOfType(scope.tableExpression, SqlFunctionCallExpression::class.java)
                    .any { BrikkRelationColumns.slot(it) == null }
                complete = complete || type.columnCount > 0 && !open && !unknownCall
                val aliases = HashSet<String>()
                for (index in 0 until type.columnCount) {
                    val name = type.getColumnName(index) ?: continue
                    val relation = (type.getColumnQualifier(index) as? PsiNamedElement)?.name.orEmpty()
                    aliases += relation.lowercase()
                    if (relation.lowercase() in shadowed || qualifier != null && !qualifier.equals(relation, true)) continue
                    val source = type.getSourceColumnElement(index) ?: type.getColumnElement(index)
                    val contract = sourceContract(source, name)
                    val physical = (source as? DasColumnSymbol)?.dasObject?.takeIf { it.name in physicalColumns }
                    selected += observedColumn(name, relation, contract, physical)
                }
                shadowed += aliases
            }
        }
        return buildJsonObject {
            put("offset", offset)
            put("knowledge", when {
                ref?.referenceElementType?.targetKind != ObjectKind.COLUMN -> "NONE"
                complete -> "FULL"
                selected.isNotEmpty() -> "PARTIAL"
                else -> "UNKNOWN"
            })
            put("columns", sorted(selected))
            put("orderedNames", strings(selected.map { it.jsonObject.string("name") }))
        }
    }

    private fun pipeScope(file: PsiFile, sql: String, offset: Int): JsonObject {
        val names = try {
            BrikkRelationColumns.pipeScope(file, sql, offset)
        } catch (_: ParseError) { null } catch (_: TokenError) { null } catch (_: ShapeError) { null }
        val displays = BrikkRelationColumns.pipeDisplay(file, sql, offset).orEmpty()
        val qualifier = Regex("([A-Za-z_]\\w*)\\.$").find(sql.substring(0, offset))?.groupValues?.get(1)
        val selected = names.orEmpty().mapNotNull { name ->
            val display = displays[name]
            if (qualifier != null && display?.origin != qualifier) return@mapNotNull null
            buildJsonObject {
                put("name", name); put("relation", display?.origin.orEmpty())
                put("origin", when { display?.relationFallback == true -> "RELATION_CONTRACT"; display?.kotlinClassId != null -> "KOTLIN_RELATION"; else -> "COMPUTED" })
                put("kotlinType", nullable(display?.kotlinClassId)); put("sqlType", JsonNull)
                put("nullability", (display?.nullability ?: ColumnNullability.UNKNOWN).name); put("navigationId", JsonNull)
                put("identity", JsonNull)
            }
        }
        return buildJsonObject {
            put("offset", offset)
            put("knowledge", JsonNull)
            put("columns", sorted(selected))
            put("orderedNames", strings(selected.map { it.string("name") }))
        }
    }

    private fun sourceContract(source: PsiElement?, name: String): ColumnContract? {
        val reference = source as? SqlReferenceExpression ?: PsiTreeUtil.getParentOfType(source, SqlReferenceExpression::class.java, false)
        val context = reference?.resolveSingle()?.let { it as? SqlResolveResult }
            ?.targetSymbol?.let { it as? DasVirtualSymbol }?.contextElement
        val id = idOf(context?.navigationElement) ?: idOf(reference?.reference?.resolve()?.navigationElement) ?: idOf(source?.navigationElement)
        val supplied = contracts.values.filterIsInstance<ShapeResult.Available>().flatMap { it.shape.columns }
        if (id != null) return supplied.distinctBy { listOf(it.name, it.kotlinClassId, it.nullability, idOf(it.navigation?.location)) }
            .singleOrNull { idOf(it.navigation?.location) == id && it.name.equals(name, true) }
        val call = source as? SqlFunctionCallExpression ?: return null
        return (BrikkRelationColumns.slot(call) as? ShapeResult.Available)?.shape?.columns?.singleOrNull { it.name.equals(name, true) }
    }

    private fun observedColumn(name: String, relation: String, contract: ColumnContract?, physical: DasColumn?) = buildJsonObject {
        put("name", name); put("relation", relation)
        put("origin", when { physical != null -> "PHYSICAL_TABLE"; contract?.navigation?.kind == NavigationKind.RELATION_FALLBACK -> "RELATION_CONTRACT"; contract != null -> "KOTLIN_RELATION"; else -> "COMPUTED" })
        put("kotlinType", nullable(contract?.kotlinClassId)); put("sqlType", nullable(physical?.dasType?.toDataType()?.typeName?.uppercase()))
        put("nullability", contract?.nullability?.name ?: if (physical != null) { if (physical.isNotNull) "NON_NULL" else "NULLABLE" } else "UNKNOWN")
        put("navigationId", nullable(if (physical != null) "decl:physical.${physical.name}" else idOf(contract?.navigation?.location)))
        put("identity", if (physical != null) "physical.${physical.name}" else identity(contract, name))
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
        if (symbol is DasColumnSymbol) return symbol.dasObject?.name?.let {
            listOf(target(qualifier, it, "physical.$it", "decl:physical.$it"))
        }.orEmpty()
        val context = (result?.targetSymbol as? DasVirtualSymbol)?.contextElement
        val authored = (resolved as? PsiNamedElement)?.name ?: reference.identifier?.text ?: return emptyList()
        val declaration = sourceContract(context ?: resolved, authored)
        val name = declaration?.name?.takeIf { it.equals(authored, true) } ?: authored
        return listOf(target(qualifier, name, identity(declaration, name), idOf(context?.navigationElement ?: resolved.navigationElement)))
    }

    private fun navigation(file: PsiFile, start: Int): List<String> {
        val element = file.findElementAt(start) ?: return emptyList()
        val targets = BrikkRelationNavigation().getGotoDeclarationTargets(element, start, myFixture.editor)?.mapNotNull(::idOf).orEmpty()
        return targets.ifEmpty { nativeTargets(file, start).mapNotNull { it.stringOrNull("navigationId") } }.distinct()
    }

    private fun diagnostics(file: PsiFile, case: JsonObject): JsonArray {
        val type = try { Class.forName("com.intellij.sql.inspections.resolve.SqlResolveInspection") }
            catch (_: ClassNotFoundException) { Class.forName("com.intellij.sql.inspections.SqlResolveInspection") }
        val inspection = type.getConstructor().newInstance() as LocalInspectionTool
        val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
        val visitor = inspection.buildVisitor(holder, true)
        PsiTreeUtil.processElements(file) { it.accept(visitor); true }
        return sorted(holder.results.mapNotNull { problem ->
            val description = problem.descriptionTemplate
            if (!description.contains("Unable to resolve") && !description.contains("Ambiguous")) return@mapNotNull null
            val range = problem.psiElement.textRange
            val highlight = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(range)
                .descriptionAndTooltip(description).create() ?: return@mapNotNull null
            if (!DorisHighlightInfoFilter().accept(highlight, file)) return@mapNotNull null
            val probe = case.array("references").map { it.jsonObject }.firstOrNull {
                it.int("start") == range.startOffset && it.int("end") == range.endOffset
            }
            val subject = probe?.string("kind") ?: "COLUMN"
            buildJsonObject {
                put("kind", when {
                    description.contains("Ambiguous") -> "AMBIGUOUS"
                    subject == "QUALIFIER" -> "UNKNOWN_QUALIFIER"
                    subject == "TABLE" -> "UNKNOWN_TABLE"
                    else -> "MISSING"
                })
                put("subject", subject)
                put("start", range.startOffset); put("end", range.endOffset)
            }
        })
    }

    private fun completion(item: LookupElement): JsonObject? {
        val shown = LookupElementPresentation().also(item::renderElement)
        val tail = shown.tailText.orEmpty()
        val symbol = (item.`object` as? SqlResolveResult)?.targetSymbol ?: item.psiElement as? DasColumnSymbol
        val physical = (symbol as? DasColumnSymbol)?.dasObject
        val origin = when {
            tail.contains("relation contract") -> "RELATION_CONTRACT"
            tail.contains("brikk column") -> "KOTLIN_RELATION"
            physical != null && item.lookupString == physical.name && physical.name in physicalColumns -> "PHYSICAL_TABLE"
            tail.contains("PIPE column") || shown.typeText in setOf("pipe column", "stage scope", "table column") -> "COMPUTED"
            symbol?.kind == ObjectKind.COLUMN && item.lookupString == symbol.name -> "COMPUTED"
            else -> return null
        }
        val relation = Regex("\\(([^()]*)\\)$").find(tail)?.groupValues?.get(1)
            ?: (symbol as? DasColumnSymbol)?.dasObject?.table?.name.orEmpty()
        return completionColumn(item.lookupString, relation, origin, shown.typeText.orEmpty().let { if (origin == "PHYSICAL_TABLE") it.uppercase() else it }, when {
            origin == "PHYSICAL_TABLE" -> if (physical?.isNotNull == true) "NON_NULL" else "NULLABLE"
            shown.typeText.orEmpty().contains("nullability unknown") || shown.typeText in setOf("pipe column", "stage scope", "table column") -> "UNKNOWN"
            shown.typeText.orEmpty().endsWith('?') -> "NULLABLE"
            origin == "COMPUTED" -> "UNKNOWN"
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
    private fun idOf(element: PsiElement?) = declarations.entries.firstOrNull { (_, declaration) ->
        element != null && element.containingFile === declaration.containingFile && declaration.textRange.contains(element.textRange)
    }?.key
    private fun target(relation: String, column: String, identity: String, navigation: String?) = buildJsonObject {
        put("relation", relation); put("column", column); put("identity", identity); put("navigationId", nullable(navigation))
    }
    private fun identity(contract: ColumnContract?, name: String): String {
        val id = idOf(contract?.navigation?.location)
        return columns.values.map { it.jsonObject }.firstOrNull {
            id != null && it.stringOrNull("navigationId") == id && it.string("name").equals(contract?.name, true)
        }?.string("identity") ?: "computed.$name"
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
