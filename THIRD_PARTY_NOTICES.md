# Third-Party Notices

This plugin includes, bundles, or is partly derived from third-party software. Each component
below is used under the terms of its own license.

## Apache Doris — `fe-sql-parser` (bundled)

This plugin bundles the `fe-sql-parser` library from Apache Doris, used for Doris-accurate SQL
parsing and validation.

- Project: Apache Doris — https://github.com/apache/doris (module `fe/fe-sql-parser`)
- Copyright: The Apache Software Foundation
- License: Apache License, Version 2.0

## StarRocks Support (`ycyz97/starrocks-datagrip-plugin`) — adapted source

`com.brikk.doris.sql.DorisPsiParser` adapts the lenient statement-parsing approach (statement
dispatch by bounded keyword look-ahead, and helpers such as `wordAt` / `statementContainsAny` /
consume-to-`;`) from this project's `StarRocksParser.kt`. The code has been modified for Apache
Doris syntax.

- Project: StarRocks Support — https://github.com/ycyz97/starrocks-datagrip-plugin
- License: Apache License, Version 2.0

---

The full text of the Apache License, Version 2.0 is available at
https://www.apache.org/licenses/LICENSE-2.0
