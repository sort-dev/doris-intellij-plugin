# Apache Doris SQL for DataGrip and IntelliJ IDEA

The complete [Apache Doris](https://doris.apache.org/) SQL dialect for DataGrip and IntelliJ IDEA
Ultimate. Doris's own parser handles parsing and validation, so your SQL is checked against the
grammar Doris actually uses, not a MySQL approximation. The dialect is updated for new Doris
releases. This build bundles the **Doris 4.1.4** grammar.

Optional PIPE syntax is built in. Before execution, the plugin transpiles your pipeline into standard
Doris SQL and validates the generated SQL with the same parser. The server receives ordinary Doris
SQL, so it needs no PIPE support.

> [!IMPORTANT]
> **Not affiliated with Apache Doris.** This is an independent plugin by Sortdev SRL. It is not
> developed, endorsed, or sponsored by the Apache Doris project or the Apache Software Foundation.
> "Apache Doris" and the Doris logo are ASF trademarks, used here only to name the database this
> plugin works with.

## Why a dedicated dialect?

Doris speaks the MySQL wire protocol, but its SQL grammar is not MySQL's. The IDE's built-in MySQL
support can connect to Doris, but it flags valid Doris SQL and can split statements in the wrong
places. This plugin uses the Doris parser for validation instead and adds Doris-specific completion,
catalogs, and function information.

## Features

- **Correct statement boundaries.** Doris syntax that MySQL gets wrong stays intact:
  `SELECT * EXCEPT(…)`, `QUALIFY`, `INSERT OVERWRITE`, multi-line window functions,
  `CREATE [OR REPLACE] VIEW`, `DISTRIBUTED BY` / `PROPERTIES` DDL, materialized views, `CREATE JOB`,
  and more.
- **Doris-accurate errors.** The editor underlines errors reported by Doris's own parser and hides
  false syntax errors from the MySQL dialect.
- **Doris-aware completion.** Suggestions cover functions, data types (`VARIANT`, `BITMAP`, `HLL`,
  `ARRAY`/`MAP`/`STRUCT`, …), and keywords. Function suggestions pop up only where an expression
  fits, so they don't get in the way while you type keywords. <kbd>Ctrl</kbd>+<kbd>Space</kbd> always
  shows the full list.
- **Multi-catalog database tree.** External catalogs (Hive, Iceberg, JDBC, …) show up as
  `catalog → database → table`, and `catalog.db.table` references complete and resolve.
- **Table-valued function completion.** `tasks()`, `jobs()`, `mv_infos()`, `partitions()`,
  `backends()` and others complete their arguments, and their output columns resolve.
- **Query cancel that works.** Stop kills the running query, even behind a load balancer and even
  with several consoles open on the same data source. Each Stop only kills its own console's query.
- **Automatic introspection.** Reference a table in a schema that hasn't been loaded yet and the
  plugin loads just that schema in the background. Columns appear a moment later.
- **Optional PIPE syntax.** Write pipe queries (`FROM t |> WHERE … |> AGGREGATE …`) and run them on
  a Doris server without native PIPE support. The plugin transpiles them before execution. See
  [PIPE syntax](#pipe-syntax).
- **Ready-made data source.** An "Apache Doris" driver template with port 9030 already set up.

## Requirements

- DataGrip or IntelliJ IDEA Ultimate, **2026.1 to 2026.3**. Older IDE versions are not supported.
- A Doris server reachable on its MySQL query port (default `9030`).

## Install

**From the JetBrains Marketplace (recommended).** Open **Settings → Plugins → Marketplace**, search
for *SQL Dialect for Apache Doris*, install it and restart the IDE.

**From a file.** Download the zip from the
[Releases](https://github.com/sort-dev/doris-intellij-plugin/releases) page, then use
**Settings → Plugins → ⚙ → Install Plugin from Disk…** and restart.

## Getting started

1. Add a new data source and pick **Apache Doris**.
2. Enter your host, user and password, then connect.
3. Open a console and write Doris SQL.

## External catalogs

Every catalog from `SHOW CATALOGS` appears in the database tree.

- The `internal` catalog loads in full. External catalogs are listed but not loaded until you tick
  them in the data source's **Schemas** tab, so you don't scan a huge metastore by accident.
- In the console's namespace picker, hover a catalog and click `›` to see its databases. Picking a
  database runs `` USE `catalog`.`db` ``. Picking a catalog runs `` SWITCH `catalog` ``.
- A two-part `db.table` resolves against the console's current catalog. Use `catalog.db.table` if
  you want references that keep working after you switch catalogs.
- If a refresh fails, the tree keeps what it had from the last successful load.

## Query cancel

The IDE's normal Stop button opens a second connection to send `KILL QUERY`. Behind a load
balancer, that connection can land on a different Doris frontend. There the kill either does
nothing or hits a different session of the same user.

The plugin tags each connection and kills your query by its query ID on whichever frontend is
running it. The console unblocks when the kill lands, usually within a fraction of a second.

**Tip:** use Doris's `query_timeout` variable instead of the data source's JDBC "query timeout"
option. The JDBC timeout uses the same unreliable second-connection kill.

## PIPE syntax

Write queries in [pipe syntax](https://cloud.google.com/bigquery/docs/pipe-syntax-guide) and run
them as ordinary Doris SQL. Your Doris server does not need to understand PIPE.

```sql
FROM sales.orders
|> WHERE order_date >= '2026-01-01'
|> AGGREGATE count(*) AS orders, sum(amount) AS revenue GROUP BY region
|> ORDER BY revenue DESC
|> LIMIT 10;
```

Before execution, the plugin transpiles the pipeline into a plain Doris `SELECT`, validates the
generated SQL with the bundled Doris parser, and only then sends it to the server.

**Turn it on** in **Settings → Tools → Database → Apache Doris PIPE** with **Enable PIPE syntax in
this project**. It's off by default and is set per project. No restart is needed. If you used PIPE
before an upgrade, tick this box again.

What you get:

- Completion after `|>` for stage keywords and the columns flowing into each stage.
- Syntax errors underlined on the exact pipe line.
- Right-click → **Doris PIPE → Run Stages up to Caret** runs the pipeline only up to the stage at
  your cursor.
- Right-click → **Doris PIPE → Preview Generated SQL** shows the SQL that will run, without running
  it.
- Server errors point back to the line and column in your pipe query.

The plugin refuses to run a pipe query when transpilation would change its meaning or isn't
supported. Preview still shows you the SQL and the reason.

## Known limitations

- **Explain Plan** only works on read queries for now, not on DDL or DML.
- **DDL generation from the database tree is off** while the catalog tree is on. The IDE's model
  can't represent Doris table properties, so generated scripts would be wrong. Use
  `SHOW CREATE TABLE` instead.
- File table functions (`s3()`, `hdfs()`, …) don't offer output columns.

## Related plugins and tools

- [ClickHouse SQL](https://github.com/sort-dev/clickhouse-intellij-plugin) dialect for JetBrains
  IDEs.
- [DuckDB SQL](https://plugins.jetbrains.com/plugin/33098-sql-dialect-for-duckdb-embedded--quack-)
  dialect for JetBrains IDEs, for embedded DuckDB and Quack servers.
- [Trino SQL](https://plugins.jetbrains.com/plugin/33124-sql-dialect-for-trino) dialect for
  JetBrains IDEs.
- [SQL Transpiler](https://plugins.jetbrains.com/plugin/32900-sql-transpiler) converts SQL between
  dialects in JetBrains IDEs. You don't need it for Doris PIPE.
- [Trino Doris connector](https://github.com/brikk/trino-doris-connector) lets Trino query Doris.
- [Trino DuckLake catalog](https://github.com/brikk/ducklake-integrations/tree/main/jvm/trino-ducklake)
  with read and write support.
- [Trino Duckbridge](https://github.com/brikk/duckbridge) gives Trino remote access to DuckDB.

## Feedback

Found a bug or SQL the plugin gets wrong?
[Open an issue](https://github.com/sort-dev/doris-intellij-plugin/issues). A small, anonymized SQL
sample helps a lot.

## License

Licensed under the Apache License 2.0. See [LICENSE](LICENSE). Copyright 2026 Sortdev SRL. Bundled
third-party parts are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
