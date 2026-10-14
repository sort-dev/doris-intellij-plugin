package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.mssql.model.MsDatabase
import com.intellij.database.dialects.mssql.model.MsRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.util.TreePattern
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.catalog.DorisCatalogProfile
import dev.sort.doris.catalog.DorisCatalogScopes
import dev.sort.doris.pipes.DorisPipesSettings
import dev.sort.doris.sqlResolveInspection
import java.io.File

/**
 * Scans a directory of real Doris SQL through the editor's real highlighting, locally: every statement,
 * with a model built from the directory's own DDL (PIPE off and on) and with no model at all. Findings go
 * to `build/realsql/` (file:line, mode, layer, message, flagged text), which is never committed. Skipped
 * unless `-PrealSql.dir=<path>` is set; the test holds no SQL of its own.
 */
class ExternalSqlScanTest : BasePlatformTestCase() {
    private val directory: String? = System.getProperty("realSql.dir")
    private var previousFlag: String? = null
    private var previousReplay: String? = null
    private var source: LocalDataSource? = null

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(DorisCatalogs.PROPERTY)
        System.setProperty(DorisCatalogs.PROPERTY, "true")
        // The production default, which the rest of the suite pins off for its goldens.
        previousReplay = System.getProperty(DorisReplay.PROPERTY)
        System.setProperty(DorisReplay.PROPERTY, "true")
    }

    override fun tearDown() = RunAll(
        { project.service<DorisPipesSettings>().enabled = false },
        { source?.let { LocalDataSourceManager.getInstance(project).removeDataSource(it) } },
        { DbPsiFacade.getInstance(project).clearCaches() },
        { previousFlag?.let { System.setProperty(DorisCatalogs.PROPERTY, it) } ?: System.clearProperty(DorisCatalogs.PROPERTY) },
        { previousReplay?.let { System.setProperty(DorisReplay.PROPERTY, it) } ?: System.clearProperty(DorisReplay.PROPERTY) },
        { super.tearDown() },
    ).run()

    private class Statement(val file: String, val line: Int, val sql: String, val namespace: List<String>)

    private class Table(val catalog: String, val database: String, val name: String, val columns: List<String>?, val view: Boolean = false)

    private fun settle() {
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testScan() {
        val root = directory?.let(::File)?.takeIf { it.isDirectory } ?: run { println("SKIP: realSql.dir not set"); return }
        HeldIntrospections(testRootDisposable)
        myFixture.enableInspections(sqlResolveInspection())
        setSqlDialectMapping(project, null, DorisSqlDialect.INSTANCE, testRootDisposable)
        val statements = ArrayList<Statement>()
        val tables = ArrayList<Table>()
        val catalogs = linkedSetOf("internal")
        val referenced = ArrayList<Table>()
        for (file in root.walkTopDown().filter { it.isFile && it.extension.equals("sql", true) }.sortedBy { it.path }) {
            val text = file.readText()
            var namespace = listOf("internal")
            for (range in SqlScriptSnapshot.parse(text, "doris").statements.filter { it.hasSql && it.end > it.start }) {
                val sql = text.substring(range.start, range.end)
                val code = sql.trim()
                val head = withoutLeadingComments(code)
                namespace = nextNamespace(head, namespace)
                val line = text.substring(0, range.start + (sql.length - sql.trimStart().length)).count { it == '\n' } + 1
                statements += Statement(file.relativeTo(root).path, line, code, namespace)
                createdCatalog(head)?.let(catalogs::add)
                createdTable(head, namespace)?.let(tables::add)
                referenced += referencedTables(head, namespace)
            }
        }
        // The directory rarely creates every table it reads: the rest are known by name only, as listed
        // but never introspected, so that findings point at the editor rather than at the scan's model.
        val known = tables.mapTo(HashSet()) { "${it.catalog}.${it.database}.${it.name}".lowercase() }
        for (table in referenced) if (known.add("${table.catalog}.${table.database}.${table.name}".lowercase()) && table.catalog.lowercase() in catalogs.map { it.lowercase() }) tables += table
        val out = File("build/realsql").apply { mkdirs() }
        out.resolve("model.txt").writeText("catalogs=$catalogs\n" + tables.joinToString("\n") { "${it.catalog}.${it.database}.${it.name} ${it.columns}" })
        source = dataSource(catalogs, tables)
        val findings = StringBuilder()
        var count = 0
        for ((mode, pipes, model) in listOf(Triple("model", false, true), Triple("model-pipe", true, true), Triple("no-model", false, false))) {
            project.service<DorisPipesSettings>().enabled = pipes
            if (!model) source?.let {
                // Unmapped files resolve against every data source of the project: none may be left.
                LocalDataSourceManager.getInstance(project).removeDataSource(it)
                source = null
                DbPsiFacade.getInstance(project).clearCaches()
            }
            settle()
            for (statement in statements) {
                for (info in highlight(statement, if (model) source else null)) {
                    count++
                    val flagged = runCatching { myFixture.editor.document.getText(TextRange(info.startOffset, info.endOffset)) }.getOrDefault("")
                    findings.append("${statement.file}:${statement.line}\t$mode\t${layer(info)}\t${info.severity}\t${info.description}\t${flagged.take(80).replace('\n', ' ')}\n")
                }
            }
        }
        out.resolve("findings.tsv").writeText(findings.toString())
        println("SCAN statements=${statements.size} tables=${tables.size} findings=$count -> ${out.absolutePath}")
    }

    private fun layer(info: HighlightInfo): String = info.inspectionToolId ?: when {
        info.description.orEmpty().startsWith("Unknown ") || info.description.orEmpty().startsWith("Ambiguous") -> "shared-relation"
        info.description.orEmpty().startsWith("Doris") -> "doris-annotator"
        else -> "annotator-or-parser"
    }

    private fun highlight(statement: Statement, dataSource: LocalDataSource?): List<HighlightInfo> {
        val file = myFixture.configureByText("scan.sql", statement.sql)
        var console: RecordingConsole? = null
        if (dataSource != null) {
            SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
                TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(dataSource.uniqueId)))))
            console = RecordingConsole(project, file.virtualFile, dataSource).also { recording ->
                val path = statement.namespace.withIndex().fold(null as ObjectPath?) { path, (index, name) ->
                    val kind = if (index == 0) ObjectKind.DATABASE else ObjectKind.SCHEMA
                    path?.append(name, kind) ?: ObjectPath.create(name, kind)
                }
                path?.let { recording.console.setSearchPath(SearchPath.of(it)) }
            }
        }
        return try {
            settle()
            myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }
        } catch (e: Throwable) {
            listOf(HighlightInfo.newHighlightInfo(com.intellij.codeInsight.daemon.impl.HighlightInfoType.ERROR)
                .range(0, 0).descriptionAndTooltip("SCAN-EXCEPTION ${e.javaClass.simpleName}: ${e.message?.take(200)}").createUnconditionally())
        } finally {
            console?.close()
            SqlResolveMappings.getInstance(project).setMapping(file.virtualFile, null)
        }
    }

    private fun dataSource(catalogs: Set<String>, tables: List<Table>): LocalDataSource {
        val model = ModelFactory.BLACK_HOLE.createModel(DorisDbms.DORIS)
        val root = (model as BasicModModel).root as MsRoot
        var id = 0L
        val byName = HashMap<String, MsDatabase>()
        fun catalog(name: String) = byName.getOrPut(name.lowercase()) { root.databases.createOrGet(++id).apply { this.name = name } }
        catalogs.forEach(::catalog)
        for (table in tables) {
            val schema = catalog(table.catalog).schemas.createOrGet(table.database)
            if (table.view) schema.views.createOrGet(table.name).let { view -> table.columns?.forEach { view.columns.createOrGet(it) } }
            else schema.tables.createOrGet(table.name).let { node -> table.columns?.forEach { node.columns.createOrGet(it) } }
        }
        val namespaces = tables.map { listOf(it.catalog, it.database) }.distinct()
        val created = LocalDataSource().apply {
            name = "Real SQL scan"
            databaseDriver = checkNotNull(DatabaseDriverManager.getInstance().getDriver("doris"))
            url = "jdbc:offline:real-sql-scan"; isAutoSynchronize = false; isKeepAlive = false
            this.model = model
            introspectionScope = namespaces.map { CatalogScopes.select(DorisCatalogProfile.levels, it) }
                .fold(DorisCatalogScopes.multiCatalogDefaultScope(), CatalogScopes::widen)
        }
        LocalDataSourceManager.getInstance(project).addDataSource(created)
        DbPsiFacade.getInstance(project).clearCaches()
        settle()
        return created
    }

    private companion object {
        val NAME = """(?:`[^`]+`|[A-Za-z_][\w$]*)"""
        val PATH = Regex("""$NAME(?:\s*\.\s*$NAME){0,2}""")
        val USE = Regex("""^USE\s+(${PATH.pattern})\s*;?$""", RegexOption.IGNORE_CASE)
        val SWITCH = Regex("""^SWITCH\s+($NAME)\s*;?$""", RegexOption.IGNORE_CASE)
        val CATALOG = Regex("""^CREATE\s+(?:EXTERNAL\s+)?CATALOG\s+(?:IF\s+NOT\s+EXISTS\s+)?($NAME)""", RegexOption.IGNORE_CASE)
        val TABLE = Regex("""^CREATE\s+(?:OR\s+REPLACE\s+)?(?:EXTERNAL\s+|TEMPORARY\s+)?(TABLE|VIEW|MATERIALIZED\s+VIEW)\s+(?:IF\s+NOT\s+EXISTS\s+)?(${PATH.pattern})\s*(\()?""", RegexOption.IGNORE_CASE)
        val NOT_COLUMN = setOf("INDEX", "KEY", "PRIMARY", "UNIQUE", "CONSTRAINT", "FOREIGN", "CHECK")

        fun withoutLeadingComments(code: String): String {
            var rest = code.trimStart()
            while (true) {
                rest = when {
                    rest.startsWith("--") || rest.startsWith("#") -> rest.substringAfter('\n', "").trimStart()
                    rest.startsWith("/*") -> rest.substringAfter("*/", "").trimStart()
                    else -> return rest
                }
            }
        }

        fun parts(path: String) = path.split('.').map { it.trim().trim('`') }

        fun nextNamespace(code: String, current: List<String>): List<String> {
            val body = code.removeSuffix(";").trim()
            USE.find(body)?.let { match ->
                val path = parts(match.groupValues[1])
                if (path.first().startsWith("@")) return current
                return if (path.size >= 2) path.take(2) else listOf(current.first(), path.single())
            }
            SWITCH.find(body)?.let { return listOf(parts(it.groupValues[1]).single()) }
            return current
        }

        val READ = Regex("""\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\s+(${PATH.pattern})""", RegexOption.IGNORE_CASE)

        fun referencedTables(code: String, namespace: List<String>): List<Table> = READ.findAll(code).mapNotNull { match ->
            val path = parts(match.groupValues[1])
            when (path.size) {
                3 -> Table(path[0], path[1], path[2], null)
                2 -> Table(namespace.first(), path[0], path[1], null)
                else -> null
            }
        }.toList()

        fun createdCatalog(code: String): String? = CATALOG.find(code)?.groupValues?.get(1)?.trim('`')

        fun createdTable(code: String, namespace: List<String>): Table? {
            val match = TABLE.find(code) ?: return null
            val path = parts(match.groupValues[2])
            val (catalog, database) = when (path.size) {
                3 -> path[0] to path[1]
                2 -> namespace.first() to path[0]
                else -> namespace.first() to (namespace.getOrNull(1) ?: return null)
            }
            val columns = if (match.groupValues[3].isEmpty()) null else columnList(code, match.range.last + 1)
                .takeIf { match.groupValues[1].equals("TABLE", true) || it.isNotEmpty() }
            return Table(catalog, database, path.last(), columns, view = match.groupValues[1].equals("VIEW", true))
        }

        /** The first name of each top-level item of the parenthesized list starting at [from], skipping constraints. */
        fun columnList(code: String, from: Int): List<String> {
            val items = ArrayList<String>()
            var depth = 1
            var start = from
            var i = from
            var quote: Char? = null
            while (i < code.length && depth > 0) {
                val c = code[i]
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '-' && code.startsWith("--", i) -> { i = code.indexOf('\n', i).let { if (it < 0) code.length else it }; continue }
                    c == '\'' || c == '"' || c == '`' -> quote = c
                    c == '(' -> depth++
                    c == ')' -> { depth--; if (depth == 0) items += code.substring(start, i) }
                    c == ',' && depth == 1 -> { items += code.substring(start, i); start = i + 1 }
                }
                i++
            }
            return items.mapNotNull { item ->
                val word = Regex("""^\s*($NAME)""").find(item.replace(Regex("""--[^\n]*"""), ""))?.groupValues?.get(1) ?: return@mapNotNull null
                word.trim('`').takeUnless { it.uppercase() in NOT_COLUMN }
            }
        }
    }
}
