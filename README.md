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
  materialized views, and more.
- **Completion** for 825 Doris built-in functions and Doris data types (`VARIANT`, `BITMAP`, `HLL`,
  `AGG_STATE`, `ARRAY`/`MAP`/`STRUCT`, …), plus ~570 Doris keywords.
- **Doris-accurate error validation** via an embedded build of Doris's own SQL parser (see below),
  with the platform's false MySQL-syntax errors suppressed.
- **Ready-to-use data source**: an "Apache Doris" driver template (MySQL Connector/J, default port
  9030, native-password auth) so you can connect without hand-configuring a MySQL driver.

## Requirements

- **DataGrip 2026.1** or **IntelliJ IDEA Ultimate 2026.1** (JetBrains platform build **261**).
  The plugin is pinned to the 261 line and will not load on other generations.
- An Apache Doris server reachable over the MySQL protocol (FE query port, default `9030`).

## Installation

Until it is on the JetBrains Marketplace (see [Status](#status)), install from disk:

1. Download `doris-intellij-plugin-<version>.zip` from the
   [Releases](https://github.com/sort-dev/doris-intellij-plugin/releases) page, or build it yourself
   (below).
2. In your IDE: **Settings → Plugins → ⚙ → Install Plugin from Disk…**, pick the zip, and restart.
3. Add a data source from the **Apache Doris** template and connect.

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

**Pre-release / under active validation.** It works and is being dogfooded against real Doris
workloads, but it has not yet had wide testing.

- **Publishing to the JetBrains Marketplace is TBD**, pending more validation.
- Known gaps and in-progress work are tracked in the issues.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE). Copyright 2026 Sortdev SRL.

Third-party components (the bundled Doris `fe-sql-parser`, adapted parser approach, and the Doris
logo used for icons) are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
