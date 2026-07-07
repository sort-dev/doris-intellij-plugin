package dev.sort.doris

import com.intellij.database.Dbms
import com.intellij.database.dialects.mssql.model.MsMetaModel
import com.intellij.database.dialects.mysql.model.MysqlMetaModel
import com.intellij.database.dialects.mysqlbase.model.MysqlBaseModelHelper
import com.intellij.database.model.ModelFacade
import com.intellij.database.model.ModelHelper
import com.intellij.database.model.meta.BasicMetaModel
import dev.sort.doris.catalog.DorisCatalogModelHelper

/**
 * Supplies the model shape for Doris data sources.
 *
 * ## Flag OFF (default): unchanged single-database MySQL model
 *
 * Returns [MysqlMetaModel.MODEL] + [MysqlBaseModelHelper] verbatim — the collapsed
 * `ROOT -> SCHEMA -> TABLE` shape the plugin has always shipped. External catalogs are invisible,
 * as before.
 *
 * ## Flag ON: reuse SQL Server's multi-database model
 *
 * Returns [MsMetaModel.MODEL] + [DorisCatalogModelHelper], a **public, shipped** multi-database
 * meta-model exposing `ROOT -> DATABASE -> SCHEMA -> TABLE`. Gate 0 proved a bespoke Doris model is
 * infeasible (the live node impls are package-private/generated) and that reusing an existing
 * multi-database family is the productionization path. Gate 1 picks the **SQL Server (`Ms*`)
 * family** for its seam structure and public multi-database model (see the "Gate 1 log" in
 * RESEARCH-catalog-introspection.md; since the stateless-first revision the Ms "switch into each
 * database" rhythm applies only to the fallback query path).
 *
 * The ModelHelper flag-ON is [DorisCatalogModelHelper] (M2): the generic base helper for the Ms
 * model shape, plus the DATABASE object kind renamed to "catalog" so the tree and the schemas pane
 * label Doris's top level correctly.
 *
 * The parsing dialect stays MySQL-based ([dev.sort.doris.sql.DorisSqlDialect]); only the *model
 * family* changes. The inherited MySQL editor helpers that hard-cast model nodes to `MysqlBase*`
 * types are overridden for `dbms="DORIS"` (see [dev.sort.doris.catalog]) so they never see an
 * `Ms*` node they cannot cast.
 */
class DorisModelFacade(dbms: Dbms) : ModelFacade(dbms) {
    private val mysqlHelper = MysqlBaseModelHelper()
    private val catalogHelper by lazy { DorisCatalogModelHelper() }

    override fun getMetaModel(): BasicMetaModel<*> {
        return if (DorisCatalogs.enabled) MsMetaModel.MODEL else MysqlMetaModel.MODEL
    }

    override fun getModelHelper(): ModelHelper {
        return if (DorisCatalogs.enabled) catalogHelper else mysqlHelper
    }
}
