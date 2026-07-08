package dev.sort.doris.model

import com.intellij.database.Dbms
import com.intellij.database.model.ModelTextStorage
import com.intellij.database.model.NameValueGetter
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.basic.BasicElement
import com.intellij.database.model.basic.BasicModModel
import com.intellij.database.model.basic.BasicModMultiLevelMateDatabase
import com.intellij.database.model.basic.BasicModMultiLevelMultiDatabaseRoot
import com.intellij.database.model.basic.BasicModMultiLevelSchema
import com.intellij.database.model.basic.BasicModTable
import com.intellij.database.model.basic.BasicModTableColumn
import com.intellij.database.model.meta.BasicMetaModel
import com.intellij.database.model.meta.BasicMetaObject
import com.intellij.database.model.meta.BasicMetaProperty
import java.util.function.BiConsumer

/**
 * Gate 0 (RESEARCH-catalog-introspection.md) compile-level proof: a **two-level (multi-database)**
 * DataGrip model shape — `root -> database(catalog) -> schema(doris database) -> table` — is
 * DEFINABLE and CONSTRUCTIBLE by a third-party plugin, using only public platform API.
 *
 * ## What this proves (and what it deliberately does not)
 *
 * The tree depth DataGrip renders is driven entirely by the [ObjectKind] graph encoded in the
 * [BasicMetaObject] children arrays of a [BasicMetaModel] (F1/F4 in the research doc). MySQL's model
 * collapses the database level ([ObjectKind.ROOT] -> [ObjectKind.SCHEMA] directly); Postgres/SQL
 * Server keep it ([ObjectKind.ROOT] -> [ObjectKind.DATABASE] -> [ObjectKind.SCHEMA] -> ...). This
 * class hand-builds the *multi-database* kind graph via the **public** [BasicMetaObject] and
 * [BasicMetaModel] constructors and shows the DATABASE level is present — the level Doris external
 * catalogs need. [DorisMetaModelTest] instantiates it and walks root -> database -> schema -> table.
 *
 * The [BasicMetaModel] constructor only traverses the [BasicMetaObject] children graph to index
 * `kind -> metaObjects`; walking [BasicMetaModel.getChildKinds] never invokes the node/model
 * factories. So this skeleton is enough to prove the SHAPE without standing up live node instances.
 *
 * ## The Gate-0 blocker this makes concrete
 *
 * A *bespoke, hand-authored* live model (real [BasicModModel] with real node instances) is NOT
 * feasible for a third party: every shipped family's live impls (`MysqlImplModel$Root/$Schema/$Table`,
 * `PgImplModel$...`) are **package-private, final, code-generated** classes wired to their own family's
 * static `META` graph via an internal `*GeneratedModelUtil.bind` step. There is no public generator,
 * no public constructor, and the generated nodes cannot be reparented under a new database level. That
 * is why [dataFactory] / [modelFactory] below are intentionally unimplemented stubs — filling them by
 * hand would mean reimplementing the generated property-storage boilerplate for dozens of `BasicMod*`
 * supertypes per node kind.
 *
 * ## The productionization path Gate 0 clears (see [reusedMultiDatabaseModelClassName])
 *
 * Because you cannot hand-author the live model, path (a) productionizes by **reusing an existing
 * multi-database family wholesale** (Postgres `PgMetaModel.MODEL` or SQL Server `MsMetaModel.MODEL`,
 * both public) as the meta-model, then overriding — for `dbms="DORIS"` — the small set of inherited
 * MySQL EPs that hard-cast model nodes to `MysqlBase*` types (introspector, sqlObjectBuilder,
 * scriptGenerator, hookUpHelper, predicatesHelper). All five are `DbmsExtension`-keyed and overridable
 * (TIDB/VITESS/MEMSQL precedent). This file does not register anything; it is proof-of-shape only.
 */
object DorisMetaModel {

    /** The catalog level (Doris CATALOG). Reuses the platform multi-level "mate database" kind/API. */
    val CATALOG_KIND: ObjectKind = ObjectKind.DATABASE

    /** The schema level (Doris DATABASE). */
    val SCHEMA_KIND: ObjectKind = ObjectKind.SCHEMA

    /**
     * Build the multi-database Doris meta-model skeleton: ROOT -> DATABASE -> SCHEMA -> (TABLE ->
     * COLUMN). Constructed entirely from public constructors. Walkable via [BasicMetaModel.getChildKinds].
     */
    fun buildMultiDatabaseSkeleton(dbms: Dbms): BasicMetaModel<*> {
        val column = node(ObjectKind.COLUMN, BasicModTableColumn::class.java, noChildren())
        val table = node(ObjectKind.TABLE, BasicModTable::class.java, arrayOf(column))
        val schema = node(SCHEMA_KIND, BasicModMultiLevelSchema::class.java, arrayOf(table))
        val database = node(CATALOG_KIND, BasicModMultiLevelMateDatabase::class.java, arrayOf(schema))
        val root = node(ObjectKind.ROOT, BasicModMultiLevelMultiDatabaseRoot::class.java, arrayOf(database))

        val modelFactory = com.intellij.util.Function<ModelTextStorage, BasicModModel> {
            error(
                "Gate-0 skeleton: live BasicModModel factory is intentionally unimplemented — the " +
                    "shipped ImplModel nodes are package-private/generated and cannot be hand-authored; " +
                    "productionize by reusing PgMetaModel.MODEL / MsMetaModel.MODEL (see class KDoc)."
            )
        }
        // Constructed via DorisMetaCompat (not the ctor directly): the ctor's Function param type
        // differs between platform 261 and 262 — see COMPAT-262.md.
        return DorisMetaCompat.newMetaModel(dbms, root, BasicModModel::class.java, modelFactory)
    }

    /**
     * The concrete productionization target named for the record: an existing, shipped, *public*
     * multi-database meta-model that already exposes the DATABASE level. Kept as a string (not a hard
     * reference) so this proof stays independent of whether the mssql/postgres dialect modules are on
     * the compile classpath in a given SDK slice; the reuse itself is a one-line
     * `getMetaModel() = MsMetaModel.MODEL` in a DORIS ModelFacade.
     */
    const val reusedMultiDatabaseModelClassName: String =
        "com.intellij.database.dialects.mssql.model.MsMetaModel"

    private fun node(
        kind: ObjectKind,
        api: Class<out BasicElement>,
        children: Array<BasicMetaObject<*>>,
    ): BasicMetaObject<BasicElement> {
        val dataFactory = com.intellij.util.Function<BasicMetaObject<BasicElement>, BasicElement> {
            error("Gate-0 skeleton: node dataFactory is intentionally unimplemented (kind-graph proof only)")
        }
        val deserializer = BiConsumer<BasicElement, NameValueGetter<String>> { _, _ -> }
        val noProps = arrayOf<BasicMetaProperty<BasicElement, *>>()
        val noRefs = arrayOf<BasicMetaProperty<BasicElement, *>>()
        // Constructed via DorisMetaCompat (not the ctor directly): the ctor's Function param type
        // differs between platform 261 and 262 — see COMPAT-262.md.
        return DorisMetaCompat.newMetaObject(kind, api, dataFactory, deserializer, noProps, noRefs, children)
    }

    private fun noChildren(): Array<BasicMetaObject<*>> = arrayOf()
}
