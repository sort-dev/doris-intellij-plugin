package dev.sort.doris.sql

import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.dialects.functions.SqlFunctionDefinitionParser
import com.intellij.sql.dialects.mysql.MysqlDialect

/**
 * Registry of Apache Doris table-valued functions (TVFs) — the FROM-clause relation functions like
 * `tasks("type"="mv")`, `catalogs()`, `s3(...)`. Data source: RESEARCH-tvf-completion.md (docs +
 * FE class catalog, Doris 4.x line).
 *
 * Three schema classes (the research doc's Tier A/B model):
 *  - [Schema.Fixed]              — documented static output columns, shipped hardcoded (zero exec).
 *  - [Schema.ByPropertyLiteral]  — output columns depend on one property VALUE (`tasks`/`jobs` on
 *    "type", `iceberg_meta` on "query_type"); the variant is chosen by reading the literal from the
 *    SQL text at resolve time — still zero exec. Absent/unknown literal degrades to the variant
 *    union (columns of all variants resolve; nothing errors).
 *  - [Schema.Open]               — file/remote TVFs (`s3`, `hdfs`, `local`, `http`, `query`,
 *    `partition_values`) whose real columns are only knowable by reading external data. They are
 *    registered (name + property-key completion) but NEVER get fabricated columns and must not
 *    produce unresolved-column errors ("open relation" degradation). No execution, ever.
 *
 * Consumed by:
 *  - [DorisSqlDialect.createTokensHelper] — overlays [builtins] on the MySQL builtin-function map
 *    so the platform recognizes TVF calls (prototype lookup is what routes the call through
 *    [DorisTypeSystem.getBuiltinFunctionReturnType]).
 *  - [DorisTypeSystem] — serves the actual table type (columns) for a call site.
 *  - [DorisCompletionContributor] — TVF-name, property-key, and enum-value completion.
 *  - [DorisHighlightInfoFilter] — scopes the unresolved-column suppression to TVF contexts.
 */
object DorisTableFunctions {

    /** One output column: [name] as documented (case preserved), [type] a plain SQL type spec. */
    data class Column(val name: String, val type: String)

    /** One documented `"key"="value"` property: [values] non-empty for closed enums. */
    data class PropertyKey(val name: String, val values: List<String> = emptyList())

    sealed class Schema {
        class Fixed(val columns: List<Column>) : Schema()

        class ByPropertyLiteral(val key: String, val variants: Map<String, List<Column>>) : Schema() {
            /** Degrade target when the literal is absent/unknown: all variants' columns resolve. */
            val union: List<Column> by lazy {
                variants.values.flatten().distinctBy { it.name.lowercase() }
            }

            fun variant(literal: String?): List<Column> =
                literal?.let { variants[it.lowercase()] } ?: union
        }

        /** Columns unknowable without exec — degrade to an open relation, no errors, no columns. */
        object Open : Schema()
    }

    class Tvf(val name: String, val keys: List<PropertyKey>, val schema: Schema) {
        fun key(name: String?): PropertyKey? =
            name?.let { n -> keys.firstOrNull { it.name.equals(n, ignoreCase = true) } }
    }

    /**
     * Stream-load / ingest-only FE table functions that are NOT FROM-queryable — deliberately not
     * registered so they are never offered as relations (see research doc §1).
     */
    val EXCLUDED_NOT_QUERYABLE: Set<String> = setOf("group_commit", "http_stream", "cdc_stream", "table\$binlog")

    private fun col(name: String, type: String) = Column(name, type)

    /** File-parsing keys shared by the ExternalFileTableValuedFunction family (s3/hdfs/local/http). */
    private val FILE_PARSING_KEYS = listOf(
        PropertyKey("format", listOf(
            "csv", "csv_with_names", "csv_with_names_and_types", "json", "parquet", "orc", "avro"
        )),
        PropertyKey("column_separator"),
        PropertyKey("line_delimiter"),
        PropertyKey("compress_type"),
        PropertyKey("read_json_by_line", listOf("true", "false")),
        PropertyKey("strip_outer_array", listOf("true", "false")),
        PropertyKey("json_root"),
        PropertyKey("jsonpaths"),
        PropertyKey("num_as_string", listOf("true", "false")),
        PropertyKey("fuzzy_parse", listOf("true", "false")),
        PropertyKey("trim_double_quotes", listOf("true", "false")),
        PropertyKey("skip_lines"),
        PropertyKey("path_partition_keys"),
    )

    // -- Registry: full Tier A + Tier B (RESEARCH-tvf-completion.md §2/§5) -----------------------

    private val SPECS: List<Tvf> = listOf(

        // --- 2A. Metadata TVFs, fixed documented schemas ---------------------------------------

        Tvf("backends", emptyList(), Schema.Fixed(listOf(
            col("BackendId", "bigint"), col("Host", "text"), col("HeartbeatPort", "int"),
            col("BePort", "int"), col("HttpPort", "int"), col("BrpcPort", "int"),
            col("ArrowFlightSqlPort", "int"), col("LastStartTime", "datetime"),
            col("LastHeartbeat", "datetime"), col("Alive", "boolean"),
            col("SystemDecommissioned", "boolean"), col("TabletNum", "bigint"),
            col("DataUsedCapacity", "text"), col("TrashUsedCapacity", "text"),
            col("AvailCapacity", "text"), col("TotalCapacity", "text"), col("UsedPct", "text"),
            col("MaxDiskUsedPct", "text"), col("RemoteUsedCapacity", "text"), col("Tag", "text"),
            col("ErrMsg", "text"), col("Version", "text"), col("Status", "text"),
            col("HeartbeatFailureCounter", "int"), col("NodeRole", "text"),
            col("CpuCores", "int"), col("Memory", "text"),
        ))),

        Tvf("frontends", emptyList(), Schema.Fixed(listOf(
            col("Name", "text"), col("Host", "text"), col("EditLogPort", "text"),
            col("HttpPort", "text"), col("QueryPort", "text"), col("RpcPort", "text"),
            col("ArrowFlightSqlPort", "text"), col("Role", "text"), col("IsMaster", "text"),
            col("ClusterId", "text"), col("Join", "text"), col("Alive", "text"),
            col("ReplayedJournalId", "text"), col("LastStartTime", "text"),
            col("LastHeartbeat", "text"), col("IsHelper", "text"), col("ErrMsg", "text"),
            col("Version", "text"), col("CurrentConnected", "text"),
        ))),

        Tvf("frontends_disks", emptyList(), Schema.Fixed(listOf(
            col("Name", "text"), col("Host", "text"), col("DirType", "text"), col("Dir", "text"),
            col("Filesystem", "text"), col("Capacity", "text"), col("Used", "text"),
            col("Available", "text"), col("UseRate", "text"), col("MountOn", "text"),
        ))),

        Tvf("catalogs", emptyList(), Schema.Fixed(listOf(
            col("CatalogId", "bigint"), col("CatalogName", "text"), col("CatalogType", "text"),
            col("Property", "text"), col("Value", "text"),
        ))),

        Tvf("mv_infos", listOf(PropertyKey("database")), Schema.Fixed(listOf(
            col("Id", "bigint"), col("Name", "text"), col("JobName", "text"), col("State", "text"),
            col("SchemaChangeDetail", "text"), col("RefreshState", "text"),
            col("RefreshInfo", "text"), col("QuerySql", "text"), col("MvProperties", "text"),
            col("MvPartitionInfo", "text"), col("SyncWithBaseTables", "boolean"),
        ))),

        // Internal-table form; external-catalog tables may differ slightly (doc caveat) — the
        // open-relation degradation does NOT apply here, this is the documented default shape.
        Tvf("partitions", listOf(
            PropertyKey("catalog"), PropertyKey("database"), PropertyKey("table"),
        ), Schema.Fixed(listOf(
            col("PartitionId", "bigint"), col("PartitionName", "text"),
            col("VisibleVersion", "bigint"), col("VisibleVersionTime", "datetime"),
            col("State", "text"), col("PartitionKey", "text"), col("Range", "text"),
            col("DistributionKey", "text"), col("Buckets", "int"), col("ReplicationNum", "int"),
            col("StorageMedium", "text"), col("CooldownTime", "datetime"),
            col("RemoteStoragePolicy", "text"), col("LastConsistencyCheckTime", "datetime"),
            col("DataSize", "text"), col("IsInMemory", "boolean"), col("ReplicaAllocation", "text"),
            col("IsMutable", "boolean"), col("SyncWithBaseTables", "boolean"),
            col("UnsyncTables", "text"),
        ))),

        // --- 2B. Utility ------------------------------------------------------------------------

        Tvf("numbers", listOf(PropertyKey("number"), PropertyKey("const_value")), Schema.Fixed(listOf(
            col("number", "bigint"),
        ))),

        // --- 2A cont. Arg-conditional metadata TVFs (variant picked from the SQL literal) --------

        Tvf("jobs", listOf(PropertyKey("type", listOf("insert", "mv"))), Schema.ByPropertyLiteral(
            "type", mapOf(
                "insert" to listOf(
                    col("Id", "bigint"), col("Name", "text"), col("Definer", "text"),
                    col("ExecuteType", "text"), col("RecurringStrategy", "text"),
                    col("Status", "text"), col("ExecuteSql", "text"), col("CreateTime", "datetime"),
                    col("SucceedTaskCount", "bigint"), col("FailedTaskCount", "bigint"),
                    col("CanceledTaskCount", "bigint"), col("Comment", "text"),
                ),
                "mv" to listOf(
                    col("Id", "bigint"), col("Name", "text"), col("MvId", "bigint"),
                    col("MvName", "text"), col("MvDatabaseId", "bigint"),
                    col("MvDatabaseName", "text"), col("ExecuteType", "text"),
                    col("RecurringStrategy", "text"), col("Status", "text"),
                    col("CreateTime", "datetime"),
                ),
            )
        )),

        Tvf("tasks", listOf(PropertyKey("type", listOf("insert", "mv"))), Schema.ByPropertyLiteral(
            "type", mapOf(
                "insert" to listOf(
                    col("TaskId", "bigint"), col("JobId", "bigint"), col("JobName", "text"),
                    col("Label", "text"), col("Status", "text"), col("ErrorMsg", "text"),
                    col("CreateTime", "datetime"), col("FinishTime", "datetime"),
                    col("TrackingUrl", "text"), col("LoadStatistic", "text"), col("User", "text"),
                ),
                "mv" to listOf(
                    col("TaskId", "bigint"), col("JobId", "bigint"), col("JobName", "text"),
                    col("MvId", "bigint"), col("MvName", "text"), col("MvDatabaseId", "bigint"),
                    col("MvDatabaseName", "text"), col("Status", "text"), col("ErrorMsg", "text"),
                    col("CreateTime", "datetime"), col("StartTime", "datetime"),
                    col("FinishTime", "datetime"), col("DurationMs", "bigint"),
                    col("TaskContext", "text"), col("RefreshMode", "text"),
                    col("NeedRefreshPartitions", "text"), col("CompletedPartitions", "text"),
                    col("Progress", "text"), col("LastQueryId", "text"),
                ),
            )
        )),

        // --- 2D. Meta-reading TVFs: schema fixed per query_type (rows would need the metastore,
        //     but the COLUMN shape is static — Tier A for schema purposes) -----------------------

        Tvf("hudi_meta", listOf(
            PropertyKey("table"), PropertyKey("query_type", listOf("timeline")),
        ), Schema.Fixed(listOf(
            col("timestamp", "text"), col("action", "text"), col("file_name", "text"),
            col("state", "text"), col("state_transition_time", "text"),
        ))),

        // Only `snapshots` is spelled out in the Doris doc; the remaining variants carry Apache
        // Iceberg's canonical metadata-table columns (complex/nested types mapped to text).
        Tvf("iceberg_meta", listOf(
            PropertyKey("table"),
            PropertyKey("query_type", listOf(
                "snapshots", "manifests", "all_manifests", "files", "data_files", "delete_files",
                "partitions", "refs", "history", "metadata_log_entries",
            )),
        ), Schema.ByPropertyLiteral(
            "query_type", buildMap {
                put("snapshots", listOf(
                    col("committed_at", "datetime"), col("snapshot_id", "bigint"),
                    col("parent_id", "bigint"), col("operation", "text"),
                    col("manifest_list", "text"), col("summary", "text"),
                ))
                val manifests = listOf(
                    col("content", "int"), col("path", "text"), col("length", "bigint"),
                    col("partition_spec_id", "int"), col("added_snapshot_id", "bigint"),
                    col("added_data_files_count", "int"), col("existing_data_files_count", "int"),
                    col("deleted_data_files_count", "int"), col("added_delete_files_count", "int"),
                    col("existing_delete_files_count", "int"),
                    col("deleted_delete_files_count", "int"), col("partition_summaries", "text"),
                )
                put("manifests", manifests)
                put("all_manifests", manifests + col("reference_snapshot_id", "bigint"))
                val files = listOf(
                    col("content", "int"), col("file_path", "text"), col("file_format", "text"),
                    col("spec_id", "int"), col("partition", "text"), col("record_count", "bigint"),
                    col("file_size_in_bytes", "bigint"), col("column_sizes", "text"),
                    col("value_counts", "text"), col("null_value_counts", "text"),
                    col("nan_value_counts", "text"), col("lower_bounds", "text"),
                    col("upper_bounds", "text"), col("key_metadata", "text"),
                    col("split_offsets", "text"), col("equality_ids", "text"),
                    col("sort_order_id", "int"),
                )
                put("files", files)
                put("data_files", files)
                put("delete_files", files)
                put("partitions", listOf(
                    col("partition", "text"), col("spec_id", "int"), col("record_count", "bigint"),
                    col("file_count", "int"), col("total_data_file_size_in_bytes", "bigint"),
                    col("position_delete_record_count", "bigint"),
                    col("position_delete_file_count", "int"),
                    col("equality_delete_record_count", "bigint"),
                    col("equality_delete_file_count", "int"),
                    col("last_updated_at", "datetime"), col("last_updated_snapshot_id", "bigint"),
                ))
                put("refs", listOf(
                    col("name", "text"), col("type", "text"), col("snapshot_id", "bigint"),
                    col("max_reference_age_in_ms", "bigint"),
                    col("min_snapshots_to_keep", "int"), col("max_snapshot_age_in_ms", "bigint"),
                ))
                put("history", listOf(
                    col("made_current_at", "datetime"), col("snapshot_id", "bigint"),
                    col("parent_id", "bigint"), col("is_current_ancestor", "boolean"),
                ))
                put("metadata_log_entries", listOf(
                    col("timestamp", "datetime"), col("file", "text"),
                    col("latest_snapshot_id", "bigint"), col("latest_schema_id", "int"),
                    col("latest_sequence_number", "bigint"),
                ))
            }
        )),

        // --- 2C. File/external TVFs — inferred schemas, NEVER fabricated, never exec (Tier B) ---

        Tvf("s3", listOf(
            PropertyKey("uri"),
            PropertyKey("s3.access_key"),
            PropertyKey("s3.secret_key"),
            PropertyKey("s3.region"),
            PropertyKey("s3.endpoint"),
            PropertyKey("s3.session_token"),
            PropertyKey("use_path_style", listOf("true", "false")),
            PropertyKey("force_parsing_by_standard_uri", listOf("true", "false")),
            PropertyKey("resource"),
        ) + FILE_PARSING_KEYS, Schema.Open),

        Tvf("hdfs", listOf(
            PropertyKey("uri"),
            PropertyKey("fs.defaultFS"),
            PropertyKey("hadoop.username"),
            PropertyKey("hadoop.security.authentication"),
            PropertyKey("hadoop.kerberos.principal"),
            PropertyKey("hadoop.kerberos.keytab"),
            PropertyKey("resource"),
        ) + FILE_PARSING_KEYS, Schema.Open),

        Tvf("local", listOf(
            PropertyKey("file_path"),
            PropertyKey("backend_id"),
            PropertyKey("shared_storage", listOf("true", "false")),
        ) + FILE_PARSING_KEYS, Schema.Open),

        Tvf("http", listOf(
            PropertyKey("uri"),
        ) + FILE_PARSING_KEYS, Schema.Open),

        Tvf("query", listOf(
            PropertyKey("catalog"), PropertyKey("query"),
        ), Schema.Open),

        Tvf("partition_values", listOf(
            PropertyKey("catalog"), PropertyKey("database"), PropertyKey("table"),
        ), Schema.Open),
    )

    private val byLowerName: Map<String, Tvf> = SPECS.associateBy { it.name.lowercase() }

    fun byName(name: String?): Tvf? = name?.let { byLowerName[it.lowercase()] }

    val allNames: List<String> get() = SPECS.map { it.name }

    /**
     * The innermost enclosing REGISTERED TVF call whose argument parens contain [offset], walking
     * up from [position] (a PSI leaf/element at the caret). Shared between
     * [DorisCompletionContributor] (property-key/value completion) and
     * [DorisTvfAutoPopupConfidence] (don't skip autopopup inside the quoted property strings) so
     * the two always agree on what counts as "inside a TVF's parens". With empty parens the caret
     * leaf can be the ')' or an error element whose parent is the call itself, so the gate is
     * "after the '('" rather than the argument-list element's range.
     */
    fun callWithCaretInArgs(
        position: com.intellij.psi.PsiElement,
        offset: Int,
    ): com.intellij.sql.psi.SqlFunctionCallExpression? {
        var call = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
            position, com.intellij.sql.psi.SqlFunctionCallExpression::class.java, false)
        while (call != null) {
            val parenIndex = call.text.indexOf('(')
            val insideParens = parenIndex >= 0 && offset > call.textRange.startOffset + parenIndex
            if (insideParens && byName(call.nameElement?.name) != null) return call
            call = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                call, com.intellij.sql.psi.SqlFunctionCallExpression::class.java)
        }
        return null
    }

    // -- BuiltinFunction registration -----------------------------------------------------------

    /**
     * functions.xml-format definitions for the registry, parsed by the platform's own
     * [SqlFunctionDefinitionParser]. The prototypes are deliberately permissive
     * (`([args:ANY...]):ANY`): TVF property bags are free-form `"key"="value"` expressions, and
     * the OUTPUT schema does not come from the prototype (the `:table(col:type, ...)` return-type
     * round-trip — the way Snowflake ships its metadata table functions — dies in the MySQL
     * grammar: the spec string is re-parsed as a type element by the DIALECT's own parser and
     * MySQL has no table-type syntax; verified empirically, it parses to an unresolved type
     * reference). Columns are served by [DorisTypeSystem] instead, which intercepts BEFORE the
     * prototype return type is consulted (`SqlFunctionCallExpressionImpl.
     * createBuiltinFunctionReturnType` asks `dialect.getTypeSystem().getBuiltinFunctionReturnType`
     * first).
     */
    private fun functionsXml(): String = buildString {
        append("<functions>\n")
        for (t in SPECS) {
            append("  <function><name>").append(t.name).append("</name>")
            append("<prototype>([args:ANY...]):ANY</prototype></function>\n")
        }
        append("</functions>\n")
    }

    /**
     * TVF name -> [BuiltinFunction], parsed once. Parsed against [MysqlDialect] (NOT the Doris
     * dialect) on purpose: the parser only needs the builtin TYPE map + casing, which are the same,
     * and using the MySQL instance avoids re-entering DorisSqlDialect's own lazy initialization
     * from inside [DorisSqlDialect.createTokensHelper].
     */
    val builtins: Map<String, BuiltinFunction> by lazy {
        SqlFunctionDefinitionParser(MysqlDialect.INSTANCE).parse(functionsXml())
            .associateBy { it.name.lowercase() }
    }

    /**
     * The MySQL builtin-function map with the Doris TVFs overlaid. MySQL name lookup stays
     * untouched (TVF names don't collide with MySQL builtins); our names answer case-insensitively
     * like every other Doris function. The overlay contributes no keyword parameters, so
     * TokensHelper.initTokens registers no new tokens and lexing/parse trees are unchanged.
     */
    class BuiltinsOverlay(private val base: SqlLanguageDialectEx.BuiltinFunctions) :
        SqlLanguageDialectEx.BuiltinFunctions {

        override fun get(name: String?): BuiltinFunction? =
            base.get(name) ?: name?.let { builtins[it.lowercase()] }

        override fun contains(name: String?): Boolean =
            base.contains(name) || (name != null && builtins.containsKey(name.lowercase()))

        override fun byMatcher(matcher: PrefixMatcher): Iterable<BuiltinFunction> =
            base.byMatcher(matcher) + builtins.values.filter { matcher.prefixMatches(it.name) }

        override fun forTokens(): Collection<BuiltinFunction> =
            base.forTokens() + builtins.values

        override fun typeMethods(name: String, instance: Boolean): SqlLanguageDialectEx.BuiltinFunctions? =
            base.typeMethods(name, instance)
    }
}
