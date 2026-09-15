# Third-Party Notices

This plugin includes, bundles, or is partly derived from third-party software. Each component
below is used under the terms of its own license.

## Apache Doris — `fe-sql-parser` (bundled)

This plugin bundles the `fe-sql-parser` library from Apache Doris, used for Doris-accurate SQL
parsing and validation.

- Project: Apache Doris — https://github.com/apache/doris (module `fe/fe-sql-parser`)
- Copyright: The Apache Software Foundation
- License: Apache License, Version 2.0

The bundled artifact is `doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar`, built from
Apache Doris commit `7027772afcbf36972662ad0c71dfc9f47bb13f4e`. Its `META-INF/NOTICE`
contains the following notice:

```text
Doris FE SQL Parser
Copyright 2022 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).
```

## brikk-sql and brikk-sql-metadata 0.15.0 (bundled)

This plugin bundles the core SQL engine and function metadata libraries from brikk-house:

- `dev.brikk.house:brikk-sql-jvm:0.15.0`
- `dev.brikk.house:brikk-sql-metadata-jvm:0.15.0`
- Project: https://github.com/brikk/brikk-house
- Developers listed in the published POMs: Jayson Minard and Sortdev SRL
- License: Apache License, Version 2.0, with third-party-derived portions described below

The provenance below refers to the published 0.15.0 artifacts. Their Maven Central
source archives contain 119 core and 20 metadata Kotlin source files. The metadata
sources are unchanged from 0.14.0; 47 core source files changed, including generated
SQLGlot provenance headers, AST/typing/scope helpers, parser and generator fixes,
and additional Doris reserved identifiers.
Gradle module metadata names the bundled files
`brikk-sql-jvmMain-0.15.0.jar` and `brikk-sql-metadata-jvmMain-0.15.0.jar`, and the
source files `brikk-sql-jvmMain-0.15.0-sources.jar` and
`brikk-sql-metadata-jvmMain-0.15.0-sources.jar`. Maven download URLs use `jvm`
instead of `jvmMain`.
The SQLGlot source pin advances to `3ca82489`. Function catalogs and the other
third-party source pins are unchanged from 0.14.0.
Source artifacts are available from Maven Central:

- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-jvm/0.15.0/brikk-sql-jvm-0.15.0-sources.jar
- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-metadata-jvm/0.15.0/brikk-sql-metadata-jvm-0.15.0-sources.jar

## SQLGlot (ported and generated code in brikk-sql)

brikk-sql is a Kotlin port of SQLGlot by Toby Mao and contributors. It includes ported
tokenizer, parser, AST, SQL generator, optimizer, and dialect code, as well as generated
token tables, AST catalogs, and function registries. In the published 0.15.0 sources,
generated token tables, AST catalogs, and typing metadata identify SQLGlot upstream
version `v30.18.0-43-g3ca82489`, commit `3ca82489`. `GeneratedFunctionRegistry.kt`
and `Tokenizer.kt` still cite `v30.12.0-44-g93d16591`, commit `93d16591`; these older
stamps are retained here rather than asserting that every source header names the newer
pin. The function registry's contents changed after 0.9.0 despite its unchanged header.

- Project: https://github.com/tobymao/sqlglot
- License: MIT License
- License source: https://raw.githubusercontent.com/tobymao/sqlglot/3ca82489/LICENSE

```text
MIT License

Copyright (c) 2026 Toby Mao

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## polyglot (DataFusion dialect reference in brikk-sql)

The published 0.15.0 `DatafusionDialect.kt` and `DatafusionGenerator.kt` credit polyglot's
`crates/polyglot-sql/src/dialects/datafusion.rs` for dialect flags and SQL transformations.
Those headers do not identify the polyglot revision. This attribution concerns the bundled
dialect implementation, not brikk-house's test fixtures.

- Project: https://github.com/tobilg/polyglot
- License: MIT License
- License source: https://raw.githubusercontent.com/tobilg/polyglot/main/LICENSE

```text
MIT License

Copyright (c) 2026 TobiLG <github@tobilg.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache Doris (registry metadata in brikk-sql-metadata)

`GeneratedDorisFunctionCatalog.kt` in version 0.15.0 contains function names, aliases,
kinds, signatures, and nullability metadata extracted from Apache Doris's runtime
registry and function classes. Its header identifies upstream version
`v0.8.2-31011-gd8fd23f7f38` and the sources `fe/fe-core/.../catalog/Builtin*Functions.java`
and the function classes' `SIGNATURES` fields and `ComputeNullable` markers.
Its available `sinceVersion` values come from the oldest `apache/doris-website`
versioned documentation containing a function, meaning "first documented in", not
necessarily "introduced in".

The core library's Doris-specific DDL parser and generator extensions also reference
Apache Doris's `DorisParser.g4` grammar. These extensions are distinct from the
SQLGlot-derived Doris dialect and the separately bundled `fe-sql-parser` JAR.

- Projects: https://github.com/apache/doris and https://github.com/apache/doris-website
- Copyright: The Apache Software Foundation
- License: Apache License, Version 2.0

## Trino (registry metadata in brikk-sql-metadata)

`GeneratedTrinoFunctionCatalog.kt` in version 0.15.0 contains function names, signatures,
and kinds extracted from Trino 483 using `SHOW FUNCTIONS` in the official
`trinodb/trino:483` container. This is registry data, not bundled Trino engine code.

- Project: https://github.com/trinodb/trino
- License: Apache License, Version 2.0
- License source: https://github.com/trinodb/trino/blob/483/LICENSE

## DuckDB (registry metadata in brikk-sql-metadata)

`GeneratedDuckdbFunctionCatalog.kt` in version 0.15.0 contains function names, signatures,
kinds, and parameter names extracted using the Python DuckDB module's `duckdb_functions()`
view. Its header identifies DuckDB `v1.5.5`, commit `d8cdaa33fd`. This is registry data,
not bundled DuckDB engine code.

- Project: https://github.com/duckdb/duckdb
- License: MIT License
- License source: https://raw.githubusercontent.com/duckdb/duckdb/d8cdaa33fd/LICENSE

```text
Copyright 2018-2025 Stichting DuckDB Foundation

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## ClickHouse (registry metadata in brikk-sql-metadata)

`GeneratedClickhouseFunctionCatalog.kt` in version 0.15.0 contains function names,
kinds, and aliases extracted from ClickHouse 26.5.1.1's `system.functions` registry.
Its header identifies `vendor/data/clickhouse-functions-26.5.1.1.tsv` as the input.
The registry exposes no signatures, so overload lists are empty. This is registry
data, not bundled ClickHouse engine code.

- Project: https://github.com/ClickHouse/ClickHouse
- Copyright: 2016-2026 ClickHouse, Inc.
- License: Apache License, Version 2.0

## StarRocks (registry metadata in brikk-sql-metadata)

`GeneratedStarrocksFunctionCatalog.kt` in version 0.15.0 contains function names,
signatures, and kinds extracted from StarRocks 4.1.4 using `SHOW FULL BUILTIN FUNCTIONS`.
Its header identifies `current_version() = 4.1.4-4a9848e` and the Docker image
`starrocks/allin1-ubuntu:4.1.4` at digest
`sha256:faf7ce9c24d9c29c9431b4e8cbd4bb7a74cd169907c63f0c5ebaacc7f9df276b`.
Variadic markers come from the source registry's `functions.py`; window-function
classification comes from `FunctionSet.java`'s `onlyAnalyticUsedFunctions`.
This is extracted metadata, not bundled StarRocks engine code. The core StarRocks
dialect is part of the SQLGlot port described above.

- Project: https://github.com/StarRocks/starrocks
- Copyright: 2021-present StarRocks, Inc. All rights reserved.
- License: Apache License, Version 2.0
- License source: https://raw.githubusercontent.com/StarRocks/starrocks/4a9848e/LICENSE.txt

The source registry's `FunctionSet.java` also retains Apache Doris attribution.
The relevant StarRocks and Doris portions of StarRocks's `NOTICE.txt` at that revision
are reproduced below:

```text
StarRocks

Copyright 2021-present, StarRocks Inc.

---------------------------
apache-doris-incubating NOTICE
---------------------------
Apache Doris (incubating)
Copyright 2018-2021 The Apache Software Foundation

This product includes software developed at
The Apache Software Foundation (http://www.apache.org/).

Based on source code originally developed by
Baidu (http://www.baidu.com/).
```

## ANTLR 4 Runtime 4.13.1 (bundled)

This plugin bundles `org.antlr:antlr4-runtime:4.13.1`, used by the Apache Doris SQL parser.

- Project: https://github.com/antlr/antlr4
- License: BSD 3-Clause License
- License source: https://raw.githubusercontent.com/antlr/antlr4/4.13.1/LICENSE.txt

```text
Copyright (c) 2012-2022 The ANTLR Project. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1. Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
notice, this list of conditions and the following disclaimer in the
documentation and/or other materials provided with the distribution.

3. Neither name of copyright holders nor the names of its contributors
may be used to endorse or promote products derived from this software
without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE REGENTS OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## StarRocks Support (`ycyz97/starrocks-datagrip-plugin`) — adapted source

`dev.sort.doris.sql.DorisPsiParser` adapts the lenient statement-parsing approach (statement
dispatch by bounded keyword look-ahead, and helpers such as `wordAt` / `statementContainsAny` /
consume-to-`;`) from this project's `StarRocksParser.kt`. The code has been modified for Apache
Doris syntax.

- Project: StarRocks Support — https://github.com/ycyz97/starrocks-datagrip-plugin
- License: Apache License, Version 2.0

## Apache Doris logo (icon)

The plugin's data-source and marketplace icons (`icons/doris.svg`, `META-INF/pluginIcon.svg`) are the
Apache Doris logo, used to identify Apache Doris data sources.

- Source: Apache Doris — https://github.com/apache/doris (`.idea/icon.svg`)
- Copyright: The Apache Software Foundation
- License: Apache License, Version 2.0

---

The full text of the Apache License, Version 2.0 is included in the accompanying
`LICENSE` file and is also available at https://www.apache.org/licenses/LICENSE-2.0.
In the packaged plugin, `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md` are included
under `META-INF`.
