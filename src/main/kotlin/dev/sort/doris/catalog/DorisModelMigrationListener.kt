package dev.sort.doris.catalog

import com.intellij.database.dataSource.DataSourceModelStorage
import com.intellij.database.dataSource.DataSourceStorage
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.model.DasModel
import com.intellij.database.model.serialization.ModelImporter
import com.intellij.openapi.project.Project
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Persisted-model shape migration across the catalogs flag (Gate 1 / M9 Part B — implements
 * RESEARCH-model-migration.md §5 recommendation (b) exactly).
 *
 * The flag-ON (Ms-family, two-level) and flag-OFF (MySQL, flat) persisted models do not
 * cross-load: `ModelImporter.populateModel` drops nodes whose family is missing in the parent
 * (`LOG.error("...'s family not in parent")`) or throws `ImportException` on orphaned children.
 * Because [DorisCatalogs.enabled] is read once per JVM, a shape mismatch can only exist at IDE
 * startup — so this **application-level** [DataSourceModelStorage.Listener] has 100% coverage by
 * construction: `DataSourceModelStorageImpl.readStateHeavy` fires `started(Project)`
 * **synchronously before** any model file is read (bytecode offsets 73/88; no
 * ProjectActivity race).
 *
 * `started(Project)` sniffs each Doris data source's model file
 * (`DataSourceStorage.getStorageDir(project)/<uniqueId>.xml`, per
 * `DataSourceModelStorageImpl.getModelPath`) with [DorisModelShape.sniff]; if the persisted shape
 * contradicts the current flag state, the `.xml` and the `<uniqueId>/entities` directory (the
 * `entities.dat` fast-path storage `ModelImporter.deserializeFast` prefers) are deleted, so the
 * platform silently starts with an empty model — the normal "new data source" experience — and
 * repopulates on first connect/refresh.
 *
 * This is the one deliberate **always-on** catalog-side behavior: it must clear catalogs-shaped
 * files when the flag is OFF too. It touches only data sources whose dbms is DORIS, only when the
 * sniffed shape and the flag disagree, and logs one `DorisCatalogs:` line per cleared model.
 */
class DorisModelMigrationListener : DataSourceModelStorage.Listener {

    override fun started(project: Project?) {
        project ?: return
        try {
            val storageDir = DataSourceStorage.getStorageDir(project) ?: return
            for (dataSource in DataSourceStorage.getProjectStorage(project).dataSources) {
                if (dataSource.dbms != DorisDbms.DORIS) continue
                checkDataSource(storageDir, dataSource)
            }
        } catch (t: Throwable) {
            // Never break model loading: the platform's own corruption handling stays the net.
            DorisCatalogs.warn("model-shape migration check failed", t)
        }
    }

    private fun checkDataSource(storageDir: String, dataSource: LocalDataSource) {
        val uniqueId = dataSource.uniqueId ?: return
        val modelXml = Paths.get(storageDir, "$uniqueId.xml")
        val shape = DorisModelShape.sniff(readHead(modelXml)) ?: return // absent/garbage: leave alone
        val expected = if (DorisCatalogs.enabled) DorisModelShape.Shape.CATALOGS else DorisModelShape.Shape.FLAT
        if (shape == expected) return

        Files.deleteIfExists(modelXml)
        deleteRecursively(Paths.get(storageDir, uniqueId, "entities"))
        DorisCatalogs.info(
            "stale model shape $shape for flag=${DorisCatalogs.enabled}, cleared for silent rebuild " +
                "(data source '${dataSource.name}')",
        )
    }

    private fun readHead(path: Path): String? {
        return try {
            if (!Files.isRegularFile(path)) return null
            Files.newInputStream(path).use { input ->
                val bytes = input.readNBytes(DorisModelShape.SNIFF_LIMIT_BYTES)
                String(bytes, Charsets.UTF_8)
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun deleteRecursively(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { p ->
                try {
                    Files.deleteIfExists(p)
                } catch (t: Throwable) {
                    DorisCatalogs.warn("could not delete stale model file $p", t)
                }
            }
        }
    }

    // The remaining listener methods are deliberate no-ops.
    override fun finished(project: Project?) = Unit
    override fun started(project: Project?, dataSource: LocalDataSource) = Unit
    override fun finished(
        project: Project?,
        dataSource: LocalDataSource,
        model: DasModel,
        importer: ModelImporter,
    ) = Unit

    override fun failed(project: Project?, dataSource: LocalDataSource, error: Throwable?) = Unit
}

/**
 * Pure shape sniffer for persisted DataGrip model XML (unit-tested offline).
 *
 * The serialized model nests elements with `parent` attributes referencing node ids; the ROOT is
 * id `1`. The **first element whose `parent="1"`** therefore reveals the level directly under the
 * root: `<database ...>` = the two-level catalogs shape (Ms family, flag-ON), `<schema ...>` = the
 * flat single-database shape (MySQL family, flag-OFF). Anything else — missing file, truncated
 * head, non-model XML, garbage — sniffs to null and is left untouched.
 */
object DorisModelShape {

    /** How much of the file the sniffer reads — the first root-child appears well within this. */
    const val SNIFF_LIMIT_BYTES: Int = 8192

    enum class Shape { CATALOGS, FLAT }

    private val FIRST_ROOT_CHILD = Regex("""<(database|schema)\b[^>]*\bparent="1"""")

    fun sniff(head: String?): Shape? {
        if (head.isNullOrBlank()) return null
        val match = FIRST_ROOT_CHILD.find(head) ?: return null
        return if (match.groupValues[1] == "database") Shape.CATALOGS else Shape.FLAT
    }
}
