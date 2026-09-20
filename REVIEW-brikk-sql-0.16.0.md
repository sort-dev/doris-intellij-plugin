# brikk-sql 0.16.0 and Doris 4.1.4 verification

Plugin 1.5.0 embeds Maven Central's `brikk-sql-jvm:0.16.0` and
`brikk-sql-metadata-jvm:0.16.0`. The core POM requires metadata 0.16.0. The
published source archives contain 119 core and 20 metadata Kotlin files; all match
brikk-house commit `1d6161621b6f8ad7fdb1c6344a55f64474387b41` byte for byte.

## Integration

- Structured DEFAULT(column), nested-column ADD/MODIFY, tablet compaction, and
  SHOW COMPUTE GROUPS parsing/generation come from the upgraded engine.
- TIMESTAMPTZ remains timezone-aware during generation. The ISO nanosecond
  conversion generates TIMESTAMPTZ(6); Doris still cannot represent nanoseconds.
- Completion metadata gains PARSE_TO_VARIANT, TRY_PARSE_TO_VARIANT, and
  VECTOR_SEARCH. The plugin also registers VECTOR_SEARCH as an open-schema TVF
  with property keys and metric values from the pinned Doris implementation.
- Variant type/nullability inference is exercised through SqlFragment and the
  PIPE adapter. Qualified UDF calls retain unknown types and nullability.
- The plugin's separately bundled native parser now uses the exact 4.1.4 grammar,
  matching brikk-house's native oracle. The release grammar and standalone facade
  have separate source pins; [vendor/README.md](vendor/README.md) and
  [vendor/build-parser.sh](vendor/build-parser.sh) document the build.

The function catalog retains its earlier master snapshot plus the source-pinned
4.1.4 supplement. It is not relabeled as an exact release-only inventory.

## Verification

| Check | Result |
|---|---|
| Full default DB-261.24374.56 suite | 466 tests, zero failures/errors/skips |
| DB-262.10315.132 runtime | 44 targeted tests, all pass |
| IU-263.4732.28 runtime | 44 targeted tests, all pass |
| Real plugin classloaders, SQL Transpiler absent and installed | Pass in both modes |
| buildPlugin and verifyEmbeddedPipes | Pass |
| Plugin Verifier | Compatible with DB-261.24374.56, IU-262.8665.81, DB-262.10315.132, IU-263.4732.28 |

The targeted runtime suite includes Doris414UpgradeTest, Doris414EditorTest,
DorisExecutionBoundaryTest, and DorisTableFunctionsTest. It uses the platform's
caret execution chooser and production replay defaults, covering the newly
accepted statements as well as the prior temporary-partition range regression.
The TVF fixture now uses the existing 263-compatible dialect mapping helper and
loads the relocated SQL resolve inspection so completion/diagnostic tests exercise
the correct language on every runtime.

Before upgrading the engine, six new feature tests failed against 0.15.1. They
now pass against Central's 0.16.0. The seventh native-grammar test checks rejection
of malformed defaults, invalid nested-column defaults, incomplete tablet
compaction, and the removed PLAN REPLAYER PLAY command.

The distribution contains exactly five JARs. Embedded core and metadata match the
Central downloads. The descriptor declares 1.5.0 and IDE builds 261 through 263.*;
packaged license notices match the repository and all bundled class files meet
the Java 21 bytecode ceiling. Existing Plugin Verifier API-usage notices remain.

These checks use offline platform fixtures and native syntax validation, not
execution against a live Doris server. Evidence, sources and JUnit XML snapshots
are under `/tmp/opencode/doris-brikk-0160/`, with command logs in
`/tmp/opencode/doris-150-*.log`.

## Artifacts

```text
e69404edb6f6b25c250f73bd5abc477cc3b17c4d1dad9d4961f6c708edbe5c14  brikk-sql-jvm-0.16.0.jar
2569b6e675319afe1043a332ff97f7a57b51570427030da65448bd286d3e939d  brikk-sql-metadata-jvm-0.16.0.jar
358ecccb39e84c65e62dc72b4090191c445fad4be1fb64e79ddaf68787d8703a  doris-fe-sql-parser-4.1.4-gad35a140c7fd.jar
```

Local installation ZIP: `build/distributions/doris-intellij-plugin-1.5.0.zip`.
The stable `build/distributions/doris-intellij-plugin.zip` contains the same bytes.

```text
SHA-256: 1b9c1b1b8bacf8c3ebfd46d92a5a44488f97f505580b2d2c171dd7dfe73fe421
```

This build has not been installed, published, or pushed.
