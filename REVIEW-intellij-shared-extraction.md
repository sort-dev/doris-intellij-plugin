# Doris shared IntelliJ support backport

Plugin 1.5.7 backports the handoff from Trino's
`HANDOFF-intellij-shared-extraction-doris.md`. The installed plugin was not changed.
The existing versionless 1.5.6 ZIP remains intact; this build writes
`build/distributions/doris-intellij-plugin-1.5.7.zip`.

## Dependencies

All production Brikk dependencies resolve from Maven Central:

- `dev.brikk.house:brikk-sql-intellij-support:0.1.0`
- `dev.brikk.house:brikk-sql-jvm:0.19.0`
- `dev.brikk.house:brikk-sql-metadata-jvm:0.19.0`
- `dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0`

Kotlin stdlib is excluded from both helpers. The SQL engine's Kotlin serialization
dependencies remain excluded because the IDE supplies them. No oracle, verify,
JDBC driver or IDE SDK dependency was added to the distribution. Their separate
publication schedules do not affect this consumer.

## Implementation

`DorisPipesActionConfiguration` and `DorisPipesActionPromoter` now subclass the
shared configuration and promoter. The library creates all four registered Execute
wrappers and handles predecessor capture, live options, Selection defaults,
presentation, shortcut promotion and conditional detach/restore. Test constructors
subclass the shipped wrapper rather than retaining a second production implementation.

`DorisExecutionAdapter` supplies claims, native parameter ranges and checked
translations to `TranslatingScriptModel`. The helper owns immutable platform
iterator copies, substitution and document revision checks. The remaining
`PipeScriptModel` forwards the platform model and adds only Doris's run-to-stage
and auditor metadata.

Doris retains native generated-SQL validation, refusal/warning policy, settings,
catalog behavior, notifications, initiating-console submission and server coordinate
conversion. The newer core script-snapshot and parameter APIs did not replace the
Doris lexer/scanner. That replacement still requires dialect-corpus parity.

Parameterized statements wait for actual prompted values before lowering. Every
selected PIPE finalizes before the first mixed-script request. The error auditor
uses the exact rendering's authored map only when the helper provides it.
Parameter expansion changes the input and therefore withholds that map. Doris
does not reattach an earlier map or emit a guessed authored squiggle.

Ownership now accounts for smart selection expansion, inside chooser/batch settings
and outside whole-script/tail settings. A trailing-comment caret cannot send raw
PIPE syntax through stock whole-script execution. Tail checks preserve leading comments.
Once claimed, rejected or canceled preparation cannot fall through to the predecessor.

Stock Execute commits and saves documents before preparing its model. Saving content
that matches the on-disk file can reset the document's modification stamp without an
edit. Doris observes document edits during that phase, including edit-and-revert,
then captures the helper's revision after the save. Chooser callbacks, prompting and
final submission retain revision checks. The save-stamp regression keeps repeated
lossy/safe-query execution working without weakening actual edit rejection.

## Regression coverage

Existing execution, settings, boundary, safety, action, source-map and Kotlin relation
tests remain. New cases cover current-document input replacing cached SQL, stale
edit-and-revert, edits/cancellation during tracking, edits during stock save, mixed-script
auditor slot alignment, Unicode map-back and withheld parameter-expanded maps.
Recording-console tests cover trailing comments, leading-comment tails and smart
selection expansion. Core 0.19 tests exercise `rows()` as a used Kotlin slot with
unused function parameters, and preserve native ROWS window frames.

263 highlighting/completion fixtures use the existing synchronous workspace dialect
setter and reparse bridge. Repeated console fixtures use unique virtual-file URLs
and datasource names. Runtime task includes select top-level test classes rather
than treating nested action doubles as JUnit test suites.
The fake session's cleanup explicitly disposes console editors, including cancellation
paths that never query session state. It does not rely on the real session's UI ownership.

The distribution-classloader test identifies peers by plugin classloader and
`ActionWithDelegate`, not product wrapper class-name prefixes. With both peer ZIPs
present it registers the actual three customizers in all six orders, checks all
four delegate chains, removes the innermost customizer and verifies pass-through.
This tests retained-wrapper behavior, not a leak-free dynamic unload guarantee.

## Verification

The 261-compiled product passed these fixture lanes:

| Lane | Tests | Result |
| --- | ---: | --- |
| Full 261 regression suite, Kotlin enabled | 523 | Passed |
| PIPE tests on IDEA 262.8665.81 | 169 | Passed |
| PIPE tests on IDEA 263.5701.42 | 169 | Passed |
| Kotlin consumer tests on IDEA 262.8665.81 | 36 | Passed |
| Kotlin consumer tests on IDEA 263.5701.42 | 36 | Passed |
| Genuine generated-shape fixture on local IDEA 262 | 1 | Passed |

The generated fixture used the reviewed `2.4.20-ij262-34` compiler-plugin artifact.
No handwritten generated shape or relabelled compiler JAR replaced it. Final full
suite XML results were preserved in `/tmp/opencode/doris-shared-backport-tests-gptj5xmk`.

Commands:

```bash
./gradlew test testPipes262 testPipes263 -Ptest.brikkKotlin=true -Pkotlin.incremental=false --console=plain
./gradlew testBrikk262 testBrikk263 testBrikkGenerated -Ptest.brikk.generatedIde=/home/jayson/.local/share/JetBrains/Toolbox/apps/intellij-idea -Ptest.brikk.compilerPlugin=/home/jayson/DEV/brikk/brikk-house/build/plugin-ide/2.4.20-ij262-34/brikk-engine-kotlin-compiler-plugin-2.4.20-ij262-34-0.2.0.jar -Pkotlin.incremental=false --console=plain
./gradlew verifyEmbeddedPipes --console=plain
```

`verifyEmbeddedPipes` passed the exact library/notices inventory and Java 21 bytecode
ceiling for every bundled class. ZIP metadata is version 1.5.7, supporting 261 through
263. All four bundled Brikk JARs were also compared byte-for-byte to their release
download URLs on Maven Central.

ZIP SHA-256:

```text
96ea856c796db3c789a730dd8fb5d77b3eecfb8c92a73cf6d92bfd0e11daff2a  doris-intellij-plugin-1.5.7.zip
```

The original 1.5.6 ZIP retained SHA-256
`00f1da1aa00a6b8f3d28c498eb6a86f28f551865a16a00759f09a59840f37952`.

Actual distribution isolation passed twice, with Kotlin absent and present. Each lane
ran both tests without skips, including all six real Doris/Trino/DuckDB registration
orders and all four Execute variants. Results are preserved beside the full-suite
XML as `native-isolation.xml` and `kotlin-isolation.xml`.

```bash
./gradlew test -Ptest.pluginIsolation=true -Ptest.trinoPluginZip=/home/jayson/DEV/sortdev/trino-intellij-plugin/build/distributions/trino-intellij-plugin.zip -Ptest.duckdbPluginZip=/home/jayson/DEV/sortdev/duckdb-intellij-plugin/build/distributions/duckdb-intellij-plugin.zip -Pkotlin.incremental=false --console=plain
# Repeat that command with -Ptest.brikkKotlin=true for the Kotlin-present lane.
```

Peer ZIP SHA-256 values used for those runs:

```text
b09fae308046c6b2f2af5dffd268bfabbaefc56c28c0767670fb7ceb19aca100  trino-intellij-plugin.zip
66e46258024ae84715517ae9d45641a477758e754540a3d92daa07a6baa68b52  duckdb-intellij-plugin.zip
```

Plugin Verifier reported Compatible on every target for the same 1.5.7 artifact:

- DataGrip 261.24374.56
- IDEA 262.8665.81
- DataGrip 262.10315.132
- IDEA 263.4732.28
- IDEA 263.5701.42

```bash
./gradlew verifyPlugin -Pkotlin.incremental=false --init-script /tmp/opencode/doris-verifier.init.gradle --console=plain
```

The init script only selects an isolated verifier cache. No compatibility finding
was waived. Internal, experimental, deprecated and scheduled-removal API notices
remain visible under `build/reports/pluginVerifier/`. DataGrip's missing optional
Kotlin dependency does not prevent native SQL loading.

## Limits

Recording consoles stop at request creation and submission to a fake producer.
No live Doris server execution was performed for this backport. Existing relation
limits remain, including exact SQL typing/nullability and PIPE qualified navigation.
The Kotlin helper did not upgrade to the peer-owned 0.2.0 snapshot. No other product
checkout, installed plugin, publication or remote branch was changed.
