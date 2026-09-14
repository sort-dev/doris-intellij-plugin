# Offline console fixture lifecycle

## Root cause

CI run [34901788151](https://github.com/sort-dev/doris-intellij-plugin/actions/runs/34901788151)
on `1afc8475` failed `DorisPipesExecutionTest.testRunToStageUsesConsoleParameterStorage`
while constructing its fixture. The assertion was `console.isValid`; parameter
storage and execution had not started. Plain `./gradlew test` reproduced the same
failure locally, with 438 of 439 tests passing.

The fixture added a `LocalDataSource` and immediately built a real `JdbcConsole`.
The database plugin has two views of that registration:

- `LocalDataSourceManager` contains the new source immediately.
- `DbPsiFacadeImpl` caches its source list. Its `DataSourceManager.Listener`
  handles additions and removals by scheduling cache invalidation with
  `Application.invokeLater`, even on the EDT.

`JdbcConsole.isValid` calls `DataSourceUtil.isDataSourceValid`, which looks up the
source ID in `DbPsiFacade`. A previously cached list therefore makes a newly
registered source appear absent until invalidation runs. Earlier tests in the
shared light project had populated that cache. A fresh execution worker normally
reached registration without the cached snapshot, hiding the dependency. The
parameter-storage test was the first execution test in the failing worker.

The previous diagnostic trace showed an editor-open callback disposing the console
during `flushUpdates`. That was a consequence of the stale registry:
`DatabaseEditorHelper.restoreAttachedConsole` calls through to
`JdbcConsole.getActiveConsoles`, whose holder disposes consoles that fail
`isValid`. Pumping events after constructing the console could encounter that
lookup before the deferred invalidation. It did not establish a valid fixture.

These call paths were checked against the DB-261.24374.56 platform bytecode. The
failure also predates the engine upgrade, as recorded in the
[0.14.0 review](REVIEW-brikk-sql-0.14.0.md#existing-mixed-worker-fixture-failure).

## Fix and regression coverage

The test fixture calls the public `DbPsiFacade.clearCaches()` synchronously after
source registration, before building the console. It asserts that the source is
visible through the facade. Teardown disposes the console before removing the
source, then invalidates the registry synchronously again.

The existing parameter-storage test deliberately reads the facade's source list
before fixture creation. With that precondition and a diagnostic assertion, the
unfixed fixture failed in a worker running just this one test: the manager contained
the source, but the facade lookup returned null. The test now also checks that
active-console discovery retains the console, the parameter value reaches the
submitted request, and teardown restores the original source list, disposes the
console, and removes it from discovery. The suite still has 439 tests.

All code changes are in the test fixture. The fix uses cache invalidation at the
registration boundary, with no new sleeps, event pumping, or facade flushes.

## Verification

Verified on 2026-09-14. The normal aggregate command ran all 439 tests on
DB-261.24374.56 with zero failures, errors, or skips:

```bash
mise exec -- ./gradlew test
```

The warm-cache regression also passed alone after failing before the fixture fix:

```bash
mise exec -- ./gradlew cleanTest test \
  --tests dev.sort.doris.pipes.DorisPipesExecutionTest.testRunToStageUsesConsoleParameterStorage \
  --no-build-cache --console=plain
```

The retained verification matrix passed with zero failures, errors, or skips:

| Runtime | SQL Transpiler | Tests |
| --- | --- | --- |
| DB-261, SDK JBR | Absent and installed | 439 aggregate, plus 414 non-execution and 25 execution in isolated workers, per mode |
| DB-261, explicit Java 21.0.2 | Default, absent | 439 aggregate and 25 execution in an isolated worker |
| DB-262.10315.132, SDK JBR | Absent and installed | 111 targeted and 25 execution in isolated workers, per mode |
| Real plugin classloaders on DB-261 and DB-262 | Absent and installed | Isolation test passed in all four combinations |

The 261 split-worker testcase identities exactly matched each companion mode's
439-test aggregate set. All matrix test runs used `cleanTest` and
`--no-build-cache`; none reused cached test results. The explicit Java 21 test
launcher was `/home/jayson/.local/share/mise/installs/java/21.0.2/bin/java`.

The isolated-worker commands follow the
[upgrade reproduction procedure](REVIEW-brikk-sql-0.14.0.md#local-reproduction).
The 262 checks follow the
[runtime procedure](PIPE-EXECUTION-CONTRACT.md#verification-commands), retaining
the six compile/instrument exclusions and using the already compiled 261 classes.
The 111 targeted tests cover action wiring, settings, engine upgrades, boundaries,
and execution safety. The 25-test execution worker includes the warm-cache
regression added to the existing parameter-storage test.

`mise exec -- ./gradlew buildPlugin verifyEmbeddedPipes verifyPlugin` also passed.
Plugin Verifier reports Compatible with DB-261.24374.56 and IU-262.8665.81.

The baseline failure, red/green regression results, verification command lines,
logs, and JUnit XML snapshots are archived locally under
`/tmp/opencode/doris-fixture-lifecycle/`.
