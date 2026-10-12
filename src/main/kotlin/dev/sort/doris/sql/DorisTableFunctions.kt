package dev.sort.doris.sql

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.dialects.functions.SqlFunctionDefinitionParser
import com.intellij.sql.dialects.mysql.MysqlDialect
import dev.brikk.house.sql.metadata.ColumnDef
import dev.brikk.house.sql.metadata.DORIS_METADATA
import dev.brikk.house.sql.metadata.OutputSchema
import dev.brikk.house.sql.metadata.PropertyDef
import dev.brikk.house.sql.metadata.TableFunctionInfo

object DorisTableFunctions {
    internal val icebergMeta = TableFunctionInfo("iceberg_meta", listOf(
        PropertyDef("table"),
        PropertyDef("query_type", listOf(
            "snapshots", "manifests", "all_manifests", "files", "data_files", "delete_files",
            "partitions", "refs", "history", "metadata_log_entries",
        )),
    ), OutputSchema.ByProperty(
        "query_type", buildMap {
            put("snapshots", listOf(
                ColumnDef("committed_at", "datetime"), ColumnDef("snapshot_id", "bigint"),
                ColumnDef("parent_id", "bigint"), ColumnDef("operation", "text"),
                ColumnDef("manifest_list", "text"), ColumnDef("summary", "text"),
            ))
            val manifests = listOf(
                ColumnDef("content", "int"), ColumnDef("path", "text"), ColumnDef("length", "bigint"),
                ColumnDef("partition_spec_id", "int"), ColumnDef("added_snapshot_id", "bigint"),
                ColumnDef("added_data_files_count", "int"), ColumnDef("existing_data_files_count", "int"),
                ColumnDef("deleted_data_files_count", "int"), ColumnDef("added_delete_files_count", "int"),
                ColumnDef("existing_delete_files_count", "int"),
                ColumnDef("deleted_delete_files_count", "int"), ColumnDef("partition_summaries", "text"),
            )
            put("manifests", manifests)
            put("all_manifests", manifests + ColumnDef("reference_snapshot_id", "bigint"))
            val files = listOf(
                ColumnDef("content", "int"), ColumnDef("file_path", "text"), ColumnDef("file_format", "text"),
                ColumnDef("spec_id", "int"), ColumnDef("partition", "text"), ColumnDef("record_count", "bigint"),
                ColumnDef("file_size_in_bytes", "bigint"), ColumnDef("column_sizes", "text"),
                ColumnDef("value_counts", "text"), ColumnDef("null_value_counts", "text"),
                ColumnDef("nan_value_counts", "text"), ColumnDef("lower_bounds", "text"),
                ColumnDef("upper_bounds", "text"), ColumnDef("key_metadata", "text"),
                ColumnDef("split_offsets", "text"), ColumnDef("equality_ids", "text"),
                ColumnDef("sort_order_id", "int"),
            )
            put("files", files)
            put("data_files", files)
            put("delete_files", files)
            put("partitions", listOf(
                ColumnDef("partition", "text"), ColumnDef("spec_id", "int"), ColumnDef("record_count", "bigint"),
                ColumnDef("file_count", "int"), ColumnDef("total_data_file_size_in_bytes", "bigint"),
                ColumnDef("position_delete_record_count", "bigint"),
                ColumnDef("position_delete_file_count", "int"),
                ColumnDef("equality_delete_record_count", "bigint"),
                ColumnDef("equality_delete_file_count", "int"),
                ColumnDef("last_updated_at", "datetime"), ColumnDef("last_updated_snapshot_id", "bigint"),
            ))
            put("refs", listOf(
                ColumnDef("name", "text"), ColumnDef("type", "text"), ColumnDef("snapshot_id", "bigint"),
                ColumnDef("max_reference_age_in_ms", "bigint"),
                ColumnDef("min_snapshots_to_keep", "int"), ColumnDef("max_snapshot_age_in_ms", "bigint"),
            ))
            put("history", listOf(
                ColumnDef("made_current_at", "datetime"), ColumnDef("snapshot_id", "bigint"),
                ColumnDef("parent_id", "bigint"), ColumnDef("is_current_ancestor", "boolean"),
            ))
            put("metadata_log_entries", listOf(
                ColumnDef("timestamp", "datetime"), ColumnDef("file", "text"),
                ColumnDef("latest_snapshot_id", "bigint"), ColumnDef("latest_schema_id", "int"),
                ColumnDef("latest_sequence_number", "bigint"),
            ))
        }
    ))

    private val byLowerName = (DORIS_METADATA.default.tableFunctions.orEmpty() + icebergMeta)
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
