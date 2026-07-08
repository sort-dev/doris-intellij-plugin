package dev.sort.doris.sql

import com.intellij.database.Dbms
import com.intellij.database.dialects.DatabaseDialectEx
import com.intellij.database.dialects.DatabaseDialects
import com.intellij.database.model.ObjectName
import com.intellij.database.psi.DbDataSource
import com.intellij.database.util.TreePattern
import com.intellij.database.util.TreePatternUtils
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.sql.dialects.mysql.MysqlDialectBase
import com.intellij.sql.dialects.mysql.MysqlTokens
import dev.sort.doris.DorisCatalogs
import dev.sort.doris.DorisDbms
import dev.sort.doris.catalog.DorisCatalogScopes

/**
 * DorisSQL dialect, based on MySQL (`MysqlDialectBase`) rather than SQL92.
 *
 * MySQL's grammar handles modern analytical SQL — window functions (`OVER (...)`), CTEs,
 * `SELECT *, expr`, etc. — that the SQL92 grammar cannot, so those parse with full structure
 * (statement boundaries, completion) instead of breaking. This mirrors the StarRocks plugin
 * (StarRocks is a Doris fork), which uses the MySQL base for exactly this reason.
 *
 * MySQL's stricter validation — the reason we originally avoided it — is neutralized by our
 * [DorisHighlightErrorFilter] / [DorisHighlightInfoFilter]; Doris-accurate error reporting comes
 * from the embedded fe-sql-parser ([DorisErrorAnnotator]). Doris-only statements that MySQL still
 * can't parse are handled by [DorisPsiParser].
 */
class DorisSqlDialect private constructor() : MysqlDialectBase("DorisSQL") {
    override fun getDbms(): Dbms = DorisDbms.DORIS

    // MySQL keyword tokens for the parser; Doris-specific keyword *coloring* is added separately by
    // DorisKeywordHighlighter (from the Doris keyword lists).
    //
    // CRITICAL: the builtin-function map must be MySQL's, loaded explicitly. The base
    // createTokensHelper(Class) resolves `functions.xml` relative to the DIALECT'S OWN class package
    // (dev/sort/doris/sql/ — which has none), yielding an EMPTY function map. Without it every
    // special-form builtin (CAST(x AS type), TRIM(... FROM ...), EXTRACT, POSITION, ...) degrades to
    // a generic call and `AS`/`FROM` inside becomes a hidden parse error — silently breaking type
    // calc and resolution (e.g. "cannot resolve '*'"). Loading MysqlDialect's definitions at runtime
    // keeps us in sync with the platform and avoids copying JetBrains resources.
    // TODO(far-future): version-gated function completion. We inherit the FULL Doris (here: MySQL-loaded)
    // builtin-function set for completion regardless of the connected server's version, so completion can
    // offer functions a given Doris FE doesn't yet have. A future enhancement would gate each function by a
    // per-function `since`-version map and filter completion to the connected server version. No such
    // `since` map exists today (Doris doesn't publish one), and the connected version is only readable
    // out-of-process (see the version-gating dead-end note), so this is a deliberate non-goal for now.
    //
    // On top of MySQL's map we overlay the Doris table-valued functions (tasks, catalogs, s3, ...;
    // see DorisTableFunctions). Registration is what makes the platform treat `FROM tasks(...)` as
    // a call with a known prototype and ask the DORIS type system (DorisTypeSystem) for its return
    // type — which serves the TVF's static output schema so its columns resolve with zero exec.
    // The overlay adds no keyword parameters, so TokensHelper.initTokens registers no new tokens
    // and lexing/parse trees are unchanged.
    override fun createTokensHelper(): TokensHelper =
        TokensHelper(
            MysqlTokens::class.java,
            DorisTableFunctions.BuiltinsOverlay(SqlFunctionsUtil.loadFunctionDefinition(MysqlDialect.INSTANCE))
        )

    /** Register Doris data types on top of MySQL's so JSON/VARIANT/BITMAP/HLL/... are known. */
    override fun addTypes(types: MutableMap<String, BuiltinFunction.Type>) {
        super.addTypes(types)
        for (name in DorisDataTypes.NAMES) {
            types.putIfAbsent(name, BuiltinFunction.Type(name))
        }
    }

    // The METADATA (wire-facing) dialect stays MYSQL in BOTH flag modes — deliberately, M3-audited.
    //
    // Flag-off: the introspector is MysqlBase-typed, and dialect/model/introspector must agree (the
    // historic GenericImplModel cast crash). Flag-on: getDatabaseDialect() is NOT where SQL-editor
    // qualification depth lives (that is meta-model + SqlLanguageDialect imports, see
    // getBaseImports below); it IS what the data grid, extractors, and console engine use to
    // GENERATE SQL sent over the wire (86 consumer classes audited in bytecode — pagination in
    // DatabaseTableGridDataHookUp, INSERT/SELECT generators, JdbcEngine, DDL qualification via
    // DatabaseDialectEx.qualifiedIdentifier/catToScript). Flipping it to MSSQL would emit T-SQL
    // (bracket quoting, TOP/FETCH paging) at a MySQL-protocol server. See the Gate 1 log, M3.
    override fun getDatabaseDialect(): DatabaseDialectEx = DatabaseDialects.findByDbms(Dbms.MYSQL)!!

    /**
     * M3 (flag-ON only): make Doris **catalogs resolvable in the SQL editor**.
     *
     * The import state that gates which top-level namespaces a reference's *head* segment may
     * resolve to is seeded from this method (`SqlDialectImplUtilCore.checkImports` against it; see
     * [DorisCatalogScopes.allCatalogsImportPattern] for the full bytecode trail). The inherited
     * MySQL implementation can only express schema-level imports, so with the multi-catalog model
     * the head of `extcat.somedb.sometable` (a DATABASE-kind node) never resolved and 3-part
     * references died at segment one. Union the MySQL base imports (current-namespace anchoring,
     * unchanged) with a wildcard catalog-level group: every catalog becomes a legal, completable
     * qualifier head, while no external catalog's *contents* are pulled into the unqualified scope.
     *
     * Flag-OFF: exact MySQL behaviour, untouched.
     *
     * `names` is nullable: the platform passes null when resolving in a file with no attached
     * data source (Java side has no annotation; a non-null Kotlin parameter here turned every
     * such resolve into an NPE).
     */
    override fun getBaseImports(dataSource: DbDataSource?, names: Array<ObjectName?>?): TreePattern {
        val mysqlImports = super.getBaseImports(dataSource, names)
        if (!DorisCatalogs.enabled) return mysqlImports
        val dsNames = names?.filterNotNull()?.toTypedArray() ?: emptyArray()
        // M10: no attached data source (empty names) -> nothing to import catalogs into; the
        // wildcard pattern would trip the platform's "Empty positive naming" assertion
        // (SqlImportUtil.createDataSources -> PositiveNaming(empty)). Latent since M3, exposed by
        // the default flip: every data-source-less SQL file resolves through this path.
        if (dsNames.isEmpty()) return mysqlImports
        return TreePatternUtils.union(mysqlImports, DorisCatalogScopes.allCatalogsImportPattern(dsNames))
    }

    companion object {
        @JvmField
        val INSTANCE = DorisSqlDialect()
    }
}
