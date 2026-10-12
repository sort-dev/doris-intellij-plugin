package dev.sort.doris.sql

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.dialects.functions.SqlFunctionDefinitionParser
import com.intellij.sql.dialects.mysql.MysqlDialect
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.TableFunctionInfo

object DorisTableFunctions {
    internal val all = DORIS_METADATA.default.tableFunctions.orEmpty()
    private val byLowerName = all
        .filter { it.queryable }.associateBy { it.name.lowercase() }

    fun byName(name: String?): TableFunctionInfo? = name?.let { byLowerName[it.lowercase()] }

    val allNames: Set<String> get() = byLowerName.keys

    val builtins: Map<String, BuiltinFunction> by lazy {
        val xml = buildString {
            append("<functions>\n")
            for (name in allNames) {
                append("  <function><name>").append(name).append("</name>")
                append("<prototype>([args:ANY...]):ANY</prototype></function>\n")
            }
            append("</functions>\n")
        }
        SqlFunctionDefinitionParser(MysqlDialect.INSTANCE).parse(xml).associateBy { it.name.lowercase() }
    }

    class BuiltinsOverlay(private val base: SqlLanguageDialectEx.BuiltinFunctions) :
        SqlLanguageDialectEx.BuiltinFunctions {
        override fun get(name: String?): BuiltinFunction? =
            base.get(name) ?: name?.let { builtins[it.lowercase()] }

        override fun contains(name: String?): Boolean =
            base.contains(name) || (name != null && builtins.containsKey(name.lowercase()))

        override fun byMatcher(matcher: PrefixMatcher): Iterable<BuiltinFunction> =
            base.byMatcher(matcher) + builtins.values.filter { matcher.prefixMatches(it.name) }

        override fun forTokens(): Collection<BuiltinFunction> = base.forTokens() + builtins.values

        override fun typeMethods(name: String, instance: Boolean): SqlLanguageDialectEx.BuiltinFunctions? =
            base.typeMethods(name, instance)
    }
}
