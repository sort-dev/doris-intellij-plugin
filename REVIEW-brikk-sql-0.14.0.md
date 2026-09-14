# brikk-sql 0.14.0 upgrade verification

## Scope

Plugin 1.3.5 embeds `dev.brikk.house:brikk-sql-jvm:0.14.0` and
`dev.brikk.house:brikk-sql-metadata-jvm:0.14.0` from Maven Central. The core POM
requires metadata 0.14.0. No SQL Transpiler library or database driver is bundled.

1.3.5 follows the repository's local patch-version sequence after 1.3.4. This is
an install-from-disk dogfood build, with no publication, release tag, or push.

On 2026-09-14, Maven Central returned HTTP 200 for both binary artifacts and
reported 0.14.0 as latest/release in these metadata documents:

- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-jvm/maven-metadata.xml
- https://repo.maven.apache.org/maven2/dev/brikk/house/brikk-sql-metadata-jvm/maven-metadata.xml

Their `lastUpdated` values were `20260914210758` and `20260914210757` respectively.

The published source archives contain 119 core and 20 metadata Kotlin files. The
metadata sources are unchanged from 0.13.0. The 8 changed core files are all in
the generator layer: window-clause elimination with inherited-window inlining,
UNNEST alias preservation for generate-series, HEX / LOWER(HEX) / SPACE
generation, byte-string cast handling, and dialect generator refinements for
BigQuery, DuckDB, Hive, Presto, and Trino. No Doris dialect, parser, shape, typing,
or metadata files changed.

Published binary SHA-256 values:

```text
954697488f9792ef55e1db439ddfe19f2fd033bbdb30086801af6a7e09725a4d  brikk-sql-jvm-0.14.0.jar
4648ab3e6057319eccecaa1178d2bc2629fcc1a1bf01ada35f11a3bf6c4b2d39  brikk-sql-metadata-jvm-0.14.0.jar
```

## Verification

- On DB-261.24374.56, all 439 tests passed in separate non-execution and execution
  workers: 414 plus 25 tests, with zero failures, errors, or skips. This passed with
  SQL Transpiler both absent and installed. Comparing testcase identities confirmed
  that each pair of workers covered exactly the same 439 tests as the full suite.
- Real plugin-classloader isolation passed in both companion modes on 261. Doris
  loaded its own 0.14.0 engine and metadata and transpiled through its plugin loader.
- With the test launcher explicitly set to Java 21.0.2, all 25 execution tests
  passed in their fresh worker. The mixed-worker run passed the other 438 tests
  but still hit the baseline console-setup failure described below.
- On the locally installed DataGrip 2026.2.5, DB-262.10315.132, all 136 targeted
  tests passed in separate workers: 111 action/settings/engine/upgrade/safety
  tests and 25 execution tests. Real plugin-classloader isolation also passed.
  Both companion modes passed with zero failures, errors, or skips. These checks
  used the SDK runtime and the 261-compiled classes with the opt-in 262 init script
  and six compile/instrument exclusions from the execution contract.
- `buildPlugin` and `verifyEmbeddedPipes` passed. The ZIP contains only the five
  expected JARs, packages the required notices, and meets the Java 21 bytecode ceiling.
  Both embedded brikk-sql JARs match the Maven Central SHA-256 values above.
- Plugin Verifier reports 1.3.5 Compatible with DB-261.24374.56 and IU-262.8665.81.
  It also reports the plugin's existing deprecated, internal, experimental, and
  scheduled-for-removal API usages.

### Existing mixed-worker fixture failure

The fixture lifecycle failure is fixed and the aggregate suite passes all 439 tests.
The root cause and verification are documented in
[Offline console fixture lifecycle](REVIEW-pipes-fixture-lifecycle.md).

At upgrade time, the plain single-worker `check` run was not green: 438 of 439
tests passed, and `DorisPipesExecutionTest.testRunToStageUsesConsoleParameterStorage` failed while
constructing its offline console at the `console.isValid` assertion, before the
test's parameter-storage/execution code runs.

The exact same failure reproduced in a clean `git archive` of unchanged commit
`dee67895`, still using 0.13.0. It reproduced with both the SDK-selected JBR 25.0.2
and an explicitly selected Java 21.0.2 test launcher, for both dependency versions.
`mise exec` alone selects Gradle's JVM; the IntelliJ test task can still select the
SDK's bundled JBR. The successful 261 companion-mode matrix above used that JBR 25.

[B20's report](REVIEW-pipes-B20.md) already documents an offline-console ordering
failure, and [the 0.13.0 review](REVIEW-brikk-sql-0.13.0.md) uses separate execution
workers. This upgrade follows that verification approach. The fixture's underlying
lifecycle problem was left for that follow-up. The speculative waits, facade
flushes, and diagnostic changes from the initial investigation were removed.

No live database or interactive install-from-disk session was used.

## Local reproduction

The archived logs, per-worker JUnit XML reports, baseline snapshot, and temporary
init scripts are in `/tmp/opencode/doris-014-reassessment/` on the build machine.
The split-worker script only selects test classes; it does not change the tests.
For either `absent` or `installed` companion mode, the 261 runs used:

```bash
mise exec -- ./gradlew cleanTest test -Ptest.sqlTranspiler=absent \
  -Pverification.testLane=non-execution \
  --init-script /tmp/opencode/doris-014-reassessment/test-lanes.init.gradle \
  --no-build-cache --console=plain
mise exec -- ./gradlew cleanTest test -Ptest.sqlTranspiler=absent \
  -Pverification.testLane=execution \
  --init-script /tmp/opencode/doris-014-reassessment/test-lanes.init.gradle \
  --no-build-cache --console=plain
mise exec -- ./gradlew cleanTest test -Ptest.sqlTranspiler=absent \
  -Ptest.pluginIsolation=true --no-build-cache --console=plain
mise exec -- ./gradlew buildPlugin verifyEmbeddedPipes verifyPlugin
```

`cleanTest` plus `--no-build-cache` ensured the selected tests actually ran.

The 262 commands followed the [runtime procedure](PIPE-EXECUTION-CONTRACT.md#verification-commands)
with `-Pdoris.localIde=/home/jayson/.local/share/JetBrains/Toolbox/apps/datagrip`.
Test filters named concrete test classes. An initial `pipes.*` wildcard also
collected the non-test nested `DorisPipesActionTest$PeerAction` helper, and mixing
the execution class with the other tests hit the same console fixture failure.
The final 111-test, 25-test, and isolation invocations used separate workers.

Installable artifact:

```text
/home/jayson/DEV/sortdev/doris-intellij-plugin/build/distributions/doris-intellij-plugin.zip
```

Its descriptor declares plugin version 1.3.5 and IDE builds 261 through 262.*.
The final ZIP's SHA-256 is:

```text
f4ad5934e661fa34fa59be7bae0d215eed7e2b12a43bb56c4027004a3ab1589a
```
