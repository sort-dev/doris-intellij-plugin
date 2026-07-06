package dev.sort.doris.sql

import com.intellij.database.Dbms
import com.intellij.database.dialects.DatabaseDialectEx
import com.intellij.database.dialects.DatabaseDialects
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.TokensHelper
import com.intellij.sql.dialects.functions.SqlFunctionsUtil
import com.intellij.sql.dialects.mysql.MysqlDialect
import com.intellij.sql.dialects.mysql.MysqlDialectBase
import com.intellij.sql.dialects.mysql.MysqlTokens
import dev.sort.doris.DorisDbms

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
    override fun createTokensHelper(): TokensHelper =
        TokensHelper(MysqlTokens::class.java, SqlFunctionsUtil.loadFunctionDefinition(MysqlDialect.INSTANCE))

    /** Register Doris data types on top of MySQL's so JSON/VARIANT/BITMAP/HLL/... are known. */
    override fun addTypes(types: MutableMap<String, BuiltinFunction.Type>) {
        super.addTypes(types)
        for (name in DorisDataTypes.NAMES) {
            types.putIfAbsent(name, BuiltinFunction.Type(name))
        }
    }

    // Introspection falls back to MySQL (plugin.xml: extensionFallback DORIS -> MYSQL), so the
    // metadata dialect must be MySQL too, or MysqlBaseIntrospector fails to cast the model root.
    override fun getDatabaseDialect(): DatabaseDialectEx = DatabaseDialects.findByDbms(Dbms.MYSQL)!!

    companion object {
        @JvmField
        val INSTANCE = DorisSqlDialect()
    }
}
