# Apache Doris support for DataGrip & IntelliJ IDEA

A JetBrains IDE plugin that adds first-class [Apache Doris](https://doris.apache.org/) support to
DataGrip and IntelliJ IDEA Ultimate: a Doris SQL dialect, a ready-to-use data-source/driver
template, and Doris-accurate editing that the built-in MySQL support gets wrong.

> [!IMPORTANT]
> **Not affiliated with Apache Doris.** This is an independent, third-party plugin developed by
> Sortdev SRL. It is **not** developed, endorsed, sponsored by, or officially part of the Apache
> Doris project or the Apache Software Foundation (ASF). "Apache Doris", the Doris name, and the
> Doris logo are trademarks of the ASF; they are used here only to identify the database this plugin
> integrates with (nominative use). For the official project, see
> [doris.apache.org](https://doris.apache.org/).

## Why

Doris speaks the MySQL wire protocol, so you *can* point DataGrip's MySQL support at it — but the
MySQL parser mis-handles a lot of real Doris SQL, most visibly by **breaking statement/run-block
boundaries** so "run statement at caret" grabs the wrong thing. This plugin adds a dedicated
`DorisSQL` dialect (built on the MySQL foundation) that keeps those boundaries correct and layers on
Doris-specific completion and validation.

## Features

- **Correct statement & run-block boundaries** for Doris constructs the MySQL grammar mis-parses:
  `CREATE [OR REPLACE] VIEW` bodies with modern SQL, multi-line window functions (`… OVER (…)`),
  `SELECT * EXCEPT(…)`, `QUALIFY`, `INSERT OVERWRITE`, `DISTRIBUTED BY` / `PROPERTIES` DDL,
  materialized views, `CREATE JOB`, and more.
- **Multi-catalog database tree** (default on): Doris external catalogs (Hive, Iceberg, JDBC, …)
  appear as a real `catalog → database → table` hierarchy with three-part reference resolution and
  a stepped console switcher. See [External catalogs](#external-catalogs-multi-catalog-tree--on-by-default-since-030).
- **Table-valued-function completion**: `tasks()`, `jobs()`, `mv_infos()`, `partitions()`,
  `backends()`, … complete their property keys and enum values, and their output columns resolve —
  including schema-by-argument (`tasks("type"="mv")`). File TVFs (`s3()`, `hdfs()`, …) stay quiet
  without fabricating columns.
- **Reliable query cancel** (default on): the Stop button actually kills the running Doris query,
  even behind a load balancer. See [Reliable query cancel](#reliable-query-cancel--on-by-default-since-050).
- **Completion** for 825 Doris built-in functions and Doris data types (`VARIANT`, `BITMAP`, `HLL`,
  `AGG_STATE`, `ARRAY`/`MAP`/`STRUCT`, …), plus ~570 Doris keywords. Function auto-popup fires only
  in expression positions, so it never interrupts typing keywords or literals (explicit
  <kbd>Ctrl</kbd>+<kbd>Space</kbd> always offers the full list).
- **Doris-accurate error validation** via an embedded build of Doris's own SQL parser (see below),
  with the platform's false MySQL-syntax errors suppressed. Note that suppression makes the editor
  stop *reporting* false errors — e.g. `SELECT * EXCEPT(…)` no longer red-flags — but does not fully
  model every semantic (an `EXCEPT`-ed column is still visible to downstream references; the query
  behaves per the server at run time).
- **Native typed parsing** (default on since 0.5.0): Doris DDL/statements parse into real typed PSI
  via a shadow-replay of Doris's own grammar, giving navigation and structure inside
  `CREATE VIEW`/`TABLE`/`MATERIALIZED VIEW`/`JOB` bodies. It falls back to the lenient path on
  anything it can't cleanly type, so it is never *worse* than before.
- **Ready-to-use data source**: an "Apache Doris" driver template (MySQL Connector/J, default port
  9030, native-password auth) so you can connect without hand-configuring a MySQL driver.

## Requirements

- **DataGrip** or **IntelliJ IDEA Ultimate**, **2026.1 or 2026.2** (JetBrains platform builds
  **261** and **262**). A single artifact serves both generations; it will not load on 2025.2 or
  earlier.
- An Apache Doris server reachable over the MySQL protocol (FE query port, default `9030`).

## Installation

**From the JetBrains Marketplace** (recommended): search for *"SQL Dialect for Apache Doris"* in
**Settings → Plugins → Marketplace**, install, and restart.

**From disk** (specific version / offline):

1. Download `doris-intellij-plugin-<version>.zip` from the
   [Releases](https://github.com/sort-dev/doris-intellij-plugin/releases) page, or build it yourself
   (below).
2. In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, and restart.

Then add a data source from the **Apache Doris** template and connect.

## External catalogs (multi-catalog tree) — on by default since 0.3.0

Doris multi-catalog support (external catalogs — Hive, Iceberg, JDBC, ... — as a real level in the
database tree) is **enabled by default** as of 0.3.0. The escape hatch back to the old flat
single-level behavior:

```
-Ddoris.catalogs.experimental=false     # VM option; default (unset) = catalogs ON
-Ddoris.replay.poc=false                # VM option; default (unset) = native typed parsing ON (0.5.0)
```

**Upgrading / toggling:** the two model shapes don't cross-load, so on the first IDE start after
an upgrade or a flag change the plugin silently clears each Doris data source's cached model
(one `DorisCatalogs:` log line per data source); the tree starts empty and repopulates on the
first connect/refresh — the normal new-data-source experience. Your data source settings and
schema selections are untouched.

With catalogs enabled:

- The tree gains a **catalogs** level: every catalog from `SHOW CATALOGS` appears; the `internal`
  catalog is deep-introspected by default, while **external catalogs are enumerated but lazy** —
  opt each one into introspection in the schemas pane before its contents (and editor
  completion/resolution for it) light up. This default protects you from accidentally scanning a
  huge external metastore on first connect.
- **Fully-qualified references work in the editor**: `catalog.database.table` completes and
  resolves at every segment (once the catalog is introspected).
- The **console namespace picker is a two-step drill-down**: hover a catalog and use the `›`
  chevron to expand its databases. Selecting a database issues `` USE `catalog`.`db` ``; selecting
  just the catalog issues `` SWITCH `catalog` ``. This is deliberate — every switch names its
  catalog explicitly, which prevents the classic wrong-catalog `use` mistake.
- Introspection is **stateless** (catalog-qualified queries; no session `SWITCH` under the hood),
  so shared connections are never left pointing at an unexpected catalog.

Note on relative references: a 2-part `db.table` reference resolves against the console's
**current catalog** — switch catalogs and previously written relative lines will (correctly) stop
resolving until you switch back or qualify them fully. Fully-qualified `catalog.db.table` is
immune to context switches. Minor known behaviors: catalog entries in flat completion lists are
intentional, and the current-namespace label may briefly show a placeholder segment.

## Reliable query cancel — on by default since 0.5.0

The IDE's stock Stop button relies on the MySQL driver, which opens a *second* connection to the
JDBC URL and issues `KILL QUERY <connection-id>`. Behind a load balancer (e.g. a Kubernetes
Service in front of several FEs) that second connection can land on a different frontend, where
that connection id either doesn't exist (the cancel silently does nothing and your query keeps
burning cluster resources) or — because Doris frontends number connections independently —
belongs to a *different session of the same user*, silently killing the wrong query.

The plugin replaces this: at connect time each Doris connection is tagged with a unique trace id
(`SET session_context = 'trace_id:dg-...'`, also visible as `DorisTraceId=dg-...` in
`SHOW PROCESSLIST` `Info`). Pressing Stop issues `KILL QUERY "dg-..."` from a short-lived helper
connection — on Doris 4.0+ the frontends forward that kill among themselves until the owner is
found, killing exactly your statement (the session stays alive). On older servers (2.1/3.x) the
plugin falls back to locating your query in the all-frontends processlist by its trace marker and
killing it by query id. The server erroring the statement is what unblocks the console (the red
"cancel query by user" bar). The IDE's stock cancel runs **only** if the plugin path genuinely
can't act — so you no longer see the spurious "Cancelling Failed. Deactivate the Data Source?"
dialog when the cancel actually succeeded. The trade-off: the console unblocks when the kill lands
(a fraction of a second) rather than instantly. Everything is logged under the `DorisCancel:`
prefix in `idea.log`.

The escape hatch back to the stock (driver-only) cancel:

```
-Ddoris.cancel.experimental=false       # VM option; default (unset) = plugin cancel ON
```

Tip: prefer Doris' own `query_timeout` session/global variable over the data source's JDBC
"query timeout" option — the JDBC timeout uses the same broken second-connection kill.

## Building from source

```bash
./gradlew buildPlugin
# → build/distributions/doris-intellij-plugin-<version>.zip
```

The build targets the DataGrip 2026.1 SDK (`datagrip("2026.1.3")`), downloaded automatically on the
first build. Kotlin 2.3.0, JVM target 21.

## The bundled Doris parser

For Doris-accurate parsing/validation the plugin embeds **`fe-sql-parser`** — the standalone ANTLR
SQL parser that is being **factored out of the Apache Doris codebase into its own module**
(`fe/fe-sql-parser`, introduced upstream in commit `7fc8e27`). That module is **pre-release and is
not published to any public Maven repository**, so we vendor a point-in-time build of it (pinned to
a specific Doris commit) under [`vendor/lib/`](vendor/) and reference it directly. See
[`vendor/README.md`](vendor/README.md) for the exact commit SHA and rebuild steps.

It is unreleased upstream, but functional and good to go for this plugin's purposes. If/when Doris
publishes `fe-sql-parser` as a real artifact, the vendored jar will be swapped for that coordinate.

## Status

**Published on the JetBrains Marketplace and in active development.** Shipped and dogfooded against
real Doris workloads.

- Available on the **JetBrains Marketplace** and as installable zips on the
  [Releases](https://github.com/sort-dev/doris-intellij-plugin/releases) page.
- Compatible with the current (2026.1) and upcoming (2026.2) IDE lines from a single artifact.
- Known gaps and in-progress work are tracked in the issues. The semantic corners noted above
  (e.g. full `EXCEPT` modeling, some file-TVF output schemas) depend on platform capabilities that
  are not yet exposed to third-party dialects.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). Copyright 2026 Sortdev SRL.

Third-party components (the bundled Doris `fe-sql-parser`, adapted parser approach, and the Doris
logo used for icons) are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
