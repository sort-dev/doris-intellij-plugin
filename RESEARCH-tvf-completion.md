# Apache Doris Table-Valued Functions — Completion/Resolution Research

**Purpose:** Enumerate every Apache Doris TVF and design a completion/resolution model for the DataGrip/IntelliJ SQL plugin, so TVF calls like `tasks("type"="mv")`, `mv_infos('database'='x')`, `partitions(...)` get name completion, property-key completion, and (where possible) resolvable output columns.

**Doc version read:** Apache Doris docs "current" channel (== 4.x line; Doris 4.1.2 is current release as of 2026-07). Individual TVF pages read from the doc source of truth `raw.githubusercontent.com/apache/doris-website/master/docs/sql-manual/sql-functions/table-valued-functions/*.md`. Function catalog cross-checked against `apache/doris` @ master: `fe/fe-core/src/main/java/org/apache/doris/tablefunction/`.

---

## 1. Complete TVF inventory

### Canonical doc-listed TVFs (4.x SQL Manual "Table Valued Functions" sidebar)
`backends`, `catalogs`, `frontends`, `frontends_disks`, `hdfs`, `hudi-meta` (`hudi_meta`), `iceberg-meta` (`iceberg_meta`), `jobs`, `local`, `mv_infos`, `numbers`, `partition-values` (`partition_values`), `partitions`, `query`, `s3`, `tasks` — plus **`http`** (new in Doris 4.0, documented separately in file-analysis / lakehouse).

### FE class catalog (`org/apache/doris/tablefunction/`, master)
These are the implementation classes. Not all are FROM-clause queryable — several back INSERT/stream-load or are internal:

| FE class | SQL-callable in FROM? | Notes |
|---|---|---|
| `BackendsTableValuedFunction` | yes | metadata |
| `FrontendsTableValuedFunction` | yes | metadata |
| `FrontendsDisksTableValuedFunction` | yes | metadata |
| `CatalogsTableValuedFunction` | yes | metadata |
| `PartitionsTableValuedFunction` | yes | metadata |
| `PartitionValuesTableValuedFunction` | yes | **dynamic** schema |
| `JobsTableValuedFunction` | yes | metadata (arg-dependent) |
| `TasksTableValuedFunction` | yes | metadata (arg-dependent) |
| `MvInfosTableValuedFunction` | yes | metadata |
| `NumbersTableValuedFunction` | yes | utility |
| `MetadataTableValuedFunction` | yes | base for several metadata types |
| `S3TableValuedFunction` | yes | file/external (extends `ExternalFileTableValuedFunction`) |
| `HdfsTableValuedFunction` | yes | file/external (extends `ExternalFileTableValuedFunction`) |
| `LocalTableValuedFunction` | yes | file/external (extends `ExternalFileTableValuedFunction`) |
| `HttpTableValuedFunction` | yes | file/external, **new 4.0** |
| `ExternalFileTableValuedFunction` | (abstract) | shared base for s3/hdfs/local/http — same property + inference surface |
| `FileTableValuedFunction` | (abstract) | file base |
| `IcebergMeta` (`MetadataTableValuedFunction` iceberg mode) | yes | meta-read (arg-dependent, fixed-per-query_type) |
| `HudiTableValuedFunction` | yes | meta-read (`hudi_meta`) |
| `ParquetMetadataTableValuedFunction` | yes | reads parquet file metadata (fixed-ish schema) |
| `QueryTableValueFunction` | yes | passthrough (`query(...)`) |
| `JdbcQueryTableValueFunction` | yes | passthrough backend for jdbc catalogs |
| `GroupCommitTableValuedFunction` | **no** | stream-load / group commit only |
| `HttpStreamTableValuedFunction` | **no** | stream-load only |
| `CdcStreamTableValuedFunction` | **no** | CDC stream ingest |
| `DataGenTableValuedFunction` | (internal) | data-gen, `numbers` is the user-facing form |
| `TableBinlogFunction` | (internal) | `table$binlog`-style, not a normal TVF call |

> Note: In older versions `active_queries` and `workload_groups` existed as TVFs backed by `MetadataTableValuedFunction`. In the current 4.x SQL-manual TVF sidebar they are **not** listed as standalone TVF pages (functionality surfaced via `information_schema`/`SHOW` and workload-management views). Treat them as low-priority "verify against target server version" — not part of the guaranteed static set.

---

## 2. Per-TVF detail

### 2A. Metadata TVFs — FIXED, documented output schema (ship static)

**`backends()`** — no args. Fixed columns:
`BackendId, Host, HeartbeatPort, BePort, HttpPort, BrpcPort, ArrowFlightSqlPort, LastStartTime, LastHeartbeat, Alive, SystemDecommissioned, TabletNum, DataUsedCapacity, TrashUsedCapacity, AvailCapacity, TotalCapacity, UsedPct, MaxDiskUsedPct, RemoteUsedCapacity, Tag, ErrMsg, Version, Status, HeartbeatFailureCounter, NodeRole, CpuCores, Memory`.

**`frontends()`** — no args. Fixed columns:
`Name, Host, EditLogPort, HttpPort, QueryPort, RpcPort, ArrowFlightSqlPort, Role, IsMaster, ClusterId, Join, Alive, ReplayedJournalId, LastStartTime, LastHeartbeat, IsHelper, ErrMsg, Version, CurrentConnected`.

**`frontends_disks()`** — no args. Fixed columns:
`Name, Host, DirType, Dir, Filesystem, Capacity, Used, Available, UseRate, MountOn` (all TEXT).

**`catalogs()`** — no args. Fixed columns:
`CatalogId (BIGINT), CatalogName (TEXT), CatalogType (TEXT), Property (TEXT), Value (TEXT)`. (One row per catalog property, so multiple rows per catalog.)

**`mv_infos("database"="<db>")`** — one required key `database`. Fixed columns:
`Id (BIGINT), Name (TEXT), JobName (TEXT), State (TEXT), SchemaChangeDetail (TEXT), RefreshState (TEXT), RefreshInfo (TEXT), QuerySql (TEXT), MvProperties (TEXT), MvPartitionInfo (TEXT), SyncWithBaseTables (BOOLEAN)`.

**`partitions("catalog"=..,"database"=..,"table"=..)`** — three keys; `catalog` optional-defaulting, `database`+`table` required. Fixed columns (internal-table form):
`PartitionId, PartitionName, VisibleVersion, VisibleVersionTime, State, PartitionKey, Range, DistributionKey, Buckets, ReplicationNum, StorageMedium, CooldownTime, RemoteStoragePolicy, LastConsistencyCheckTime, DataSize, IsInMemory, ReplicaAllocation, IsMutable, SyncWithBaseTables, UnsyncTables`.
Caveat: column set can differ slightly for external-catalog tables (e.g. Hive) — treat the internal-table schema as the shipped default; degrade gracefully for external catalogs.

**`jobs("type"="<insert|mv>")`** — one required key `type`. **Output columns depend on `type`** but each variant is fixed & documented:
- `type="insert"`: `Id, Name, Definer, ExecuteType, RecurringStrategy, Status, ExecuteSql, CreateTime, SucceedTaskCount, FailedTaskCount, CanceledTaskCount, Comment`.
- `type="mv"`: `Id, Name, MvId, MvName, MvDatabaseId, MvDatabaseName, ExecuteType, RecurringStrategy, Status, CreateTime`.

**`tasks("type"="<insert|mv>")`** — one required key `type`. **Output columns depend on `type`**, each fixed & documented:
- `type="insert"`: `TaskId, JobId, JobName, Label, Status, ErrorMsg, CreateTime, FinishTime, TrackingUrl, LoadStatistic, User`.
- `type="mv"`: `TaskId, JobId, JobName, MvId, MvName, MvDatabaseId, MvDatabaseName, Status, ErrorMsg, CreateTime, StartTime, FinishTime, DurationMs, TaskContext, RefreshMode, NeedRefreshPartitions, CompletedPartitions, Progress, LastQueryId`.

### 2B. Utility TVF — FIXED

**`numbers("number"="<n>" [, "const_value"="<v>"])`** — keys: `number` (required), `const_value` (optional). Output: single column `number (BIGINT)`.

### 2C. File/External TVFs — INFERRED schema (need to read the file)

All of `s3`, `hdfs`, `local`, `http` extend `ExternalFileTableValuedFunction` and share the file-parsing property surface. **Output columns are inferred from the file/format, not fixed:**
- CSV / `csv_with_names` / `csv_with_names_and_types`: column count from first line; names auto `c1..cn` (or header-parsed); types default String.
- JSON: keys become columns, String-typed.
- Parquet / ORC: names + types parsed from embedded file metadata.

**`s3(...)`** required: `uri, s3.access_key, s3.secret_key, s3.region, s3.endpoint, format`. Optional: `s3.session_token, use_path_style, force_parsing_by_standard_uri, column_separator, line_delimiter, compress_type, read_json_by_line, strip_outer_array, json_root, jsonpaths, num_as_string, fuzzy_parse, trim_double_quotes, skip_lines, path_partition_keys, resource, enable_mapping_varbinary, enable_mapping_timestamp_tz`.

**`hdfs(...)`** required: `uri, format`. HDFS/Hadoop keys: `fs.defaultFS, hadoop.username, hadoop.security.authentication, hadoop.kerberos.principal, hadoop.kerberos.keytab, dfs.*` (HA/nameservice props) + the same file-parsing optional keys as `s3`. (Doc page fetch was flaky — repeatedly served the LOCAL page — but HDFS shares `ExternalFileTableValuedFunction`'s parsing surface; verify exact Hadoop-key list against the live HDFS doc page before shipping the key list.)

**`local(...)`** required: `file_path, backend_id, format`. Optional: `shared_storage, column_separator, line_delimiter, compress_type, read_json_by_line, strip_outer_array, json_root, json_paths, num_as_string, fuzzy_parse, trim_double_quotes, skip_lines, path_partition_keys, enable_mapping_varbinary, enable_mapping_timestamp_tz`.

**`http(...)`** — **new in Doris 4.0.** Reads a file over HTTP(S); key set = `uri`/url + `format` + shared file-parsing keys. Inferred schema, same model as s3/local. (Verify exact key names against the HTTP TVF page.)

**`partition_values("catalog"=..,"database"=..,"table"=..)`** — same three keys as `partitions`, but **output schema is dynamic**: one column per partition column of the target table (names/types mirror the table's partition key columns). Cannot be known statically without catalog metadata.

### 2D. Meta-reading TVFs — FIXED per query_type (schema is a known, closed enum of shapes)

**`iceberg_meta("table"="db.tbl", "query_type"="<type>")`** — keys `table`, `query_type`. `query_type` ∈ `{snapshots, manifests, all_manifests (v4.0.4+), files, data_files, delete_files, partitions, refs, history, metadata_log_entries}`. Schema is **fixed per query_type** (Iceberg's standard metadata-table schemas). Example `snapshots`: `committed_at, snapshot_id, parent_id, operation, manifest_list, summary`. Only `snapshots` is fully spelled out in the doc; the others follow Apache Iceberg's canonical metadata-table columns.

**`hudi_meta("table"="db.tbl", "query_type"="timeline")`** — keys `table`, `query_type` (only `timeline` today). Fixed columns: `timestamp, action, file_name, state, state_transition_time`.

**`query("catalog"=.., "query"=..)`** — passthrough SQL to an external (JDBC) catalog (v2.1.3+, JDBC only today). Output columns entirely determined by the remote query result — **fully dynamic**, only knowable by asking the remote system.

---

## 3. Four-bucket classification

**Bucket 1 — GENERALIZABLE (call-syntax surface shared by ALL TVFs).** Every TVF is a relation function usable in `FROM tvf_name(...)`. Two uniform completion surfaces we can support with zero server contact:
- (a) the **function name** in relation position (register all names + aliases as completable relation functions), and
- (b) the **property keys** inside the parens (named `"key"="value"` args). Even the file/exec TVFs have a fully documented, static key vocabulary that is completable regardless of schema. This is the biggest immediate win.
- Argument style is NOT uniform across the set — capture per-function:
  - Named `"key"="value"` properties: `s3, hdfs, local, http, iceberg_meta, hudi_meta, query, tasks, jobs, mv_infos, partitions, partition_values, numbers`.
  - Zero-arg: `backends, frontends, frontends_disks, catalogs`.
  - `numbers` mixes required+optional named keys.

**Bucket 2 — FIXED INFORMATION (static documented output schema → ship as hardcoded relation).**
`backends, frontends, frontends_disks, catalogs, mv_infos, partitions, numbers` (single fixed shape), plus `jobs, tasks` (fixed shape **selected by the `type` property value** — ship both variants keyed on the literal), plus `hudi_meta` (single fixed shape) and `iceberg_meta` (fixed shape per `query_type` literal — a closed enum of shapes). These resolve with **zero exec**.

**Bucket 3 — INFERRED INFORMATION (schema depends on args/data, not knowable statically).**
`s3, hdfs, local, http` (schema inferred from the target file/format), `partition_values` (schema = target table's partition columns), `query` (schema = remote query result). Column set genuinely unknown without touching data or catalog metadata.

**Bucket 4 — EXEC-REQUIRED, must NOT exec without explicit user permission** (subset of Bucket 3 where the only way to obtain real columns costs money / hits external systems):
- `s3` — reads object storage (network egress, S3 GET/list costs).
- `hdfs` — reads remote HDFS.
- `http` — fetches a remote URL.
- `query` — executes SQL against a remote JDBC/external catalog (real remote query, side-effect + cost).
- `local` — reads a file on a BE node (cheap, but still server-side exec / secure-path sensitive).
- `partition_values` / `iceberg_meta` / `hudi_meta` need catalog/metastore access to enumerate real data, though `iceberg_meta`/`hudi_meta` **column names/types are static per query_type** so they land in Bucket 2 for schema and only the *rows* need exec.

The mechanism to resolve inferred schemas is Doris's own `DESC FUNCTION <tvf>(...)` — it returns the inferred column list. **But `DESC FUNCTION` on a file/query TVF still triggers the schema-inference read** (opens the S3/HDFS/HTTP object or runs the remote query), so it is NOT free and belongs behind the same permission gate.

---

## 4. Recommended working model (tiers)

### Tier A — free, static, always on (no server contact)
1. **Register every FROM-clause TVF name** (+ aliases; exclude the non-queryable stream-load classes `group_commit`, `http_stream`, `cdc_stream`, `table$binlog`) as completable relation functions so `FROM s3(` / `FROM tasks(` autocompletes and parses without red errors.
2. **Property-key completion** for all named-arg TVFs from the hardcoded key vocabularies in §2 (required keys first, then optional). For `type`/`query_type`, also complete the **enum of allowed literal values** (`insert|mv`, the iceberg `query_type` list, `timeline`).
3. **Ship fixed output schemas** as hardcoded virtual relations for Bucket 2 so their columns resolve, with zero exec:
   - unconditional: `backends, frontends, frontends_disks, catalogs, mv_infos, partitions, numbers, hudi_meta`.
   - conditional-on-literal: `jobs`/`tasks` keyed on `type`, `iceberg_meta` keyed on `query_type`. Resolve the arg literal at parse time; if the literal is absent/unknown, fall back to the union or to no-columns (degrade, don't error).

### Tier B — opt-in "sample/infer columns" (permissioned, default OFF)
For Bucket 3/4 (`s3, hdfs, local, http, partition_values, query`): offer an explicit user action ("Infer columns for this TVF") that runs `DESC FUNCTION <tvf>(...)` against the connected data source and caches the resulting column list per call-site (keyed on the normalized property set).
- **Permission gate sits at the action invocation**, not at completion/parse time. Never auto-fire on typing, on file open, on completion popup, or on background resolve. Require an explicit click/command per call-site (or a session-scoped "allow inference for this data source" opt-in).
- Surface the cost in the prompt ("this reads the S3 object / runs a remote query"). Cache aggressively; invalidate on property change.
- `local` is cheaper but still server-side and secure-path-sensitive — keep it behind the same gate for consistency.

### What is genuinely undoable without exec → degrade gracefully
For Bucket 3/4 call-sites with no cached inference:
- Resolve the relation as an **open/unknown-columns relation**: no red "unresolved column" errors, no fake/placeholder columns, `SELECT *` and arbitrary `col` references accepted as unknown. The property keys still complete (Tier A).
- Do not fabricate columns and do not invent `c1..cn` (they may be header-named). Silence over guessing.

---

## 5. TVF → bucket → ship-static-schema table

| TVF | Aliases/notes | Arg style | Bucket | Ship static schema? | Notes |
|---|---|---|---|---|---|
| `backends` | — | zero-arg | 2 Fixed | **Y** | 27 cols, no exec |
| `frontends` | — | zero-arg | 2 Fixed | **Y** | 19 cols |
| `frontends_disks` | — | zero-arg | 2 Fixed | **Y** | 10 cols, all TEXT |
| `catalogs` | — | zero-arg | 2 Fixed | **Y** | 5 cols; row-per-property |
| `mv_infos` | — | `"database"=` | 2 Fixed | **Y** | 11 cols |
| `partitions` | — | `catalog/database/table` | 2 Fixed | **Y** | ~20 cols (internal-table form); external catalogs may differ |
| `partition_values` | — | `catalog/database/table` | 3 Inferred | **N** | cols = target table partition columns |
| `jobs` | — | `"type"=insert\|mv` | 2 Fixed (per type) | **Y** | two variants keyed on `type` |
| `tasks` | — | `"type"=insert\|mv` | 2 Fixed (per type) | **Y** | two variants keyed on `type` |
| `numbers` | (datagen) | `number`,`const_value` | 2 Fixed | **Y** | 1 col `number BIGINT` |
| `s3` | — | named props | 3/4 Inferred+Exec | **N** | reads object storage; cost |
| `hdfs` | — | named props | 3/4 Inferred+Exec | **N** | reads remote HDFS |
| `local` | — | `file_path/backend_id/format` | 3/4 Inferred+Exec | **N** | reads BE file; secure-path |
| `http` | **new 4.0** | named props | 3/4 Inferred+Exec | **N** | fetches remote URL |
| `iceberg_meta` | — | `table`,`query_type` | 2/4 | **Y (schema)** | fixed cols per `query_type`; rows need metastore |
| `hudi_meta` | — | `table`,`query_type=timeline` | 2/4 | **Y (schema)** | 5 fixed cols; rows need metastore |
| `query` | jdbc passthrough | `catalog`,`query` | 3/4 Inferred+Exec | **N** | cols = remote query result |
| `group_commit` | — | — | n/a | n/a | **stream-load only, not FROM-queryable — exclude** |
| `http_stream` | — | — | n/a | n/a | **stream-load only — exclude** |
| `cdc_stream` | — | — | n/a | n/a | **CDC ingest — exclude** |
| `table$binlog` | — | — | n/a | n/a | internal — exclude |

**New in Doris 4.x:** `http` TVF (4.0). `iceberg_meta` `all_manifests` query_type (4.0.4+). Everything else in the list predates 4.x (2.x/3.x lineage). `query` TVF is 2.1.3+, JDBC-only.

---

## 6. Key takeaways for the design decision

- **~80% of the value is Tier A and costs nothing:** name completion + property-key (and key-value enum) completion for all TVFs, plus hardcoded schemas for the 9-ish metadata/utility TVFs (`backends, frontends, frontends_disks, catalogs, mv_infos, partitions, numbers, jobs, tasks`) and the fixed meta-read schemas (`hudi_meta`, `iceberg_meta` per query_type). This is a static table baked into the plugin — no server version dependency, no exec.
- **The arg-value-dependent metadata TVFs (`jobs`, `tasks`, `iceberg_meta`) are the interesting case:** schema is a function of a *literal property value*, resolvable at parse time from the SQL text — still zero exec. Build the resolver to read the `type`/`query_type` literal and pick the variant.
- **The file/query TVFs (`s3, hdfs, local, http, partition_values, query`) must never auto-exec.** Their only real-schema source (`DESC FUNCTION` or running the query) touches paid/external systems. Gate behind an explicit, per-call-site, opt-in action; default to graceful open-relation degradation.
- **Exclude the non-queryable classes** (`group_commit`, `http_stream`, `cdc_stream`, `table$binlog`) from relation-function registration to avoid offering them in FROM completion.

---

## 7. Future feature — permissioned "Preview" for inferred TVFs (Tier B UX)

*TODO (later-in-life; the concrete realization of Tier B's opt-in path). Not a merge-gate item.*

The file/query TVFs (`s3`, `hdfs`, `local`, `http`, `query`, `partition_values`) have no static
schema — their columns are only knowable by reading the source, which costs money / egress and must
never happen automatically. Rather than leave them at bare open-relation degradation forever, give
them an explicit, cheap, cached escape hatch:

- **Hover affordance.** When the caret is on a file/query TVF call (`s3( … )`), show a tooltip /
  gutter action offering **Preview**.
- **Preview action.** On explicit click, run `SELECT * FROM s3( …same properties… ) LIMIT 100`
  against the current connection and drop the rows into the normal query-results panel — the user
  sees real data, and we get a real result-set schema for free.
- **Cache the schema.** Key the cached column list on the **normalized property set** of that call
  (the `"key"="value"` bag, order-insensitive, whitespace-normalized). Feed the cached columns into
  completion and resolution for that call-site, so after one Preview the TVF's columns autocomplete
  and resolve like any real table.
- **Invalidate on argument change.** When the contents of the `s3( … )` property set change, the
  normalized key changes, so the old entry is naturally stale — evict it and fall back to
  open-relation degradation until the next Preview. (Editing whitespace or reordering props must NOT
  invalidate — that's why the key is normalized, not textual.)

Why it fits the model: Preview **is** the permission gate — one explicit, visible, user-initiated
action, never a background/on-type/on-open exec. It upgrades a degraded relation to a fully-resolved
one at exactly the moment the user asks and pays for it, and caches so they pay once. Same gate
applies to `hdfs`/`http`/`local`/`query`/`partition_values`. This would freaking rock.
