package dev.sort.doris.sql

import com.intellij.database.Dbms
import com.intellij.database.dialects.DatabaseDialectEx
import com.intellij.database.dialects.DatabaseDialects
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.TokensHelper
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
    override fun createTokensHelper(): TokensHelper = createTokensHelper(MysqlTokens::class.java)

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
