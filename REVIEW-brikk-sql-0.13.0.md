# brikk-sql 0.13.0 upgrade verification

## Scope

Plugin 1.3.3 embeds `dev.brikk.house:brikk-sql-jvm:0.13.0` and
`dev.brikk.house:brikk-sql-metadata-jvm:0.13.0` from Maven Central. The core POM
requires metadata 0.13.0. No SQL Transpiler library or database driver is bundled.

The published source archives contain 119 core and 20 metadata Kotlin files. The
metadata sources are unchanged from 0.12.0. The 23 changed core files cover shape,
typing, parser, schema, and generator handling; Doris-relevant changes include quoted
identifier preservation in inferred shapes and the `LAST_DAY` generator override.

Published binary SHA-256 values:

```text
89244b480d6dceabfc2d15d082dd19668f31ef9ae4fc23faf85f958238791d7f  brikk-sql-jvm-0.13.0.jar
1d473c8b5ee0ed4f7dbada336b505fd4f10b754ea5522fa37829a5949343f340  brikk-sql-metadata-jvm-0.13.0.jar
```

## Verification

- All 436 tests passed on DB-261.24374.56 in fresh non-execution and execution
  workers, with SQL Transpiler both absent and installed.
- Real plugin-classloader isolation passed on DB-261.24374.56 with SQL Transpiler
  both absent and installed. Doris loaded its own 0.13.0 core and metadata JARs.
- `verifyEmbeddedPipes` passed: the distribution contains only the expected engine,
  metadata, ANTLR, Doris parser, and plugin JARs; required notices are packaged; all
  class files remain Java 21 compatible.
- Plugin Verifier 1.410 reports the plugin artifact Compatible with both
  DB-261.24374.56 and IU-262.8665.81.

The IU-262.8665.81 runtime isolation fixture is currently blocked during IDE setup,
before the Doris test executes: IU's bundled Java plugin registers
`UsedIconsListingAction` into an unregistered `Internal.Java` group. The failure is
unchanged after a clean sandbox retry under the prescribed JDK 21 and does not mention
the Doris plugin or either brikk library. The 262 binary compatibility gate remains
green, but this fixture limitation means the 262 runtime lane is not claimed as passed.

No live Doris database was used for this upgrade.
