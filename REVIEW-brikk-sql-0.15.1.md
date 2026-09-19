# brikk-sql 0.15.1 verification

## Scope

Plugin 1.4.2 embeds `dev.brikk.house:brikk-sql-jvm:0.15.1` and
`dev.brikk.house:brikk-sql-metadata-jvm:0.15.1` from Maven Central. Both artifact
metadata files report 0.15.1 as the latest release. The core POM requires metadata
0.15.1.

The upstream release branch and local checkout point to commit
`07fc4c6374072de6a7b22f39991c199380653b53`, which adds typed parsing and generation
for Doris `INSERT INTO ... TEMPORARY PARTITION(...)` targets.

Published binary SHA-256 values:

```text
5aa40f18ef8368cb939a9bc8f678dacc3b049cda96d819569df0e5692f2f6cf4  brikk-sql-jvm-0.15.1.jar
70a1704a002e8d521621e01f299a6aa7ca853ee27f34143afbe218a0ef85e211  brikk-sql-metadata-jvm-0.15.1.jar
```

The 0.15.1 source archives contain 119 core and 20 metadata Kotlin files. Metadata
is byte-identical to 0.15.0. Four core files differ: `DorisNodes.kt`, `PipeNodes.kt`,
`DorisParser.kt`, and `DorisGenerator.kt`.

## Verification

- The exact reported multiline INSERT parses as an `Insert` containing a
  `DorisTemporaryPartition`, generates Doris SQL, reparses through brikk, and passes
  the bundled authoritative Doris parser.
- The plugin PSI regression keeps the same SQL as one clean typed INSERT statement.
- `cleanTest test` passed all 450 tests with no failures, errors, or skips.
- `buildPlugin` and `verifyEmbeddedPipes` passed. The ZIP contains the expected five
  JARs, and both embedded brikk JARs match the Maven Central hashes above.
- Plugin Verifier reports 1.4.2 compatible with DB-261.24374.56,
  DB-262.10315.132, IU-262.8665.81, and IU-263.4732.28. Existing API-usage notices
  remain.

Installable local ZIP:

```text
/home/jayson/DEV/sortdev/doris-intellij-plugin/build/distributions/doris-intellij-plugin.zip
SHA-256: 191657ce539846121961737cc3148eddef79d7c08443fca434394df306592290
```

This build was not published, pushed, tagged, or installed.
