# Newer IDE compatibility in 1.4.1

## Baseline failures

Marketplace's verifier 1.410 reported four binary problems for plugin 1.4.0 on
`IU-263.4732.28`. A local run against the exact released ZIP reproduced all four.
The same ZIP also has one of these defects on supported DataGrip 2026.2.5,
`DB-262.10315.132`: the missing `affectedMajorObjects()` implementation.

The earlier CI targets, DB-261.24374.56 and IU-262.8665.81, passed. Runtime tests on
later 262 exercised PIPE execution but did not call the affected portion retriever.
The verification matrix now includes current 262 and the reported 263 EAP.

## Fixes

### Partial-schema introspection

Later 262 made `AbstractDatabaseSchemasRetriever.affectedMajorObjects()` abstract.
`DorisIntrospector` now implements the JVM method in its portion retriever, returning
the tables and views successfully refreshed by that pass. Failed or unselected
schemas are excluded. A successful empty inventory prunes dropped objects and
returns an empty affected set; cancellation propagates.

The hook is absent in the 261 compile SDK, so the Kotlin method intentionally has
no `override` modifier. Its JVM signature implements the later abstract contract.
The stock newer SQL Server retriever also reports affected major objects, rather
than the containing catalogs or schemas.

`DorisCatalogFailurePreservationTest` now invokes the actual retriever with a real
model and a recording transaction. It checks successful table/view refreshes,
failed and untouched schemas, authoritative empty inventories, cancellation, and
the platform's abstract method dispatch on newer SDKs.

### Execute action promotion

263 makes the `DatabaseActionPromoter` implementation and constructor package-private,
which produced two verifier findings. `DorisPipesActionPromoter` now finds the
registered database promoter in `ActionPromoter.EP_NAME` and calls it through the
public `ActionPromoter` interface. There is no direct class linkage, constructor
call, or reflective access to that implementation.

The existing captured-delegate chain, cycle protection, original action ordering,
and mapping back to outer wrappers are retained. Discovery uses the implementation
name, which the runtime regression checks on each SDK. The B1 startup customizer
remains a separate API-usage justification for Marketplace review.

### Automatic targeted introspection

263 removes both `tryPerform` and `tryPerformAsync`. The replacement uses
`DataSourceSyncManager.tryPerformSync`, a suspending API present in 261, 262, and
263, from a project service with an injected `CoroutineScope`. Project disposal
cancels its work. The original one-element refresh, additive schema scope,
once-per-namespace guard, `stopRunning=true`, and `merge=false` are preserved.

The regression test exercises the real coroutine and sync queue. The platform's
test executor records the submitted task without opening JDBC, and the test waits
for executor entry and queue completion before removing its source. It verifies
the targeted catalog and preservation of the existing scope and one-shot behavior.
Real classloader checks also construct the service through the IDE container.

## Build and runtime fixtures

- IntelliJ Platform Gradle Plugin is upgraded from 2.10.2 to 2.19.0. The newer-SDK
  init script no longer overrides it with 2.18.1.
- The verifier task explicitly fails on binary compatibility problems/warnings,
  invalid plugins, missing dependencies, override-only violations, non-extendable
  API violations, and plugin-structure warnings. With 2.19.0's default policy the
  fixed artifact was Compatible on all four targets but the task failed on the
  existing 17 internal-API notices. Those notices remain fully reported under the
  explicit policy; no ignored-problems file is used and no Marketplace approval is
  implied. Internal/experimental/deprecated usages remain review items.
- Tests explicitly use headless AWT. On a desktop host, a plain JDK 21 worker can
  otherwise select IDE-managed HiDPI and fail platform startup before UI scale is
  precomputed. The SDK JBR uses a different HiDPI path.
- The 263 fixtures use the synchronous workspace-model SQL dialect setter. The
  old `SqlDialectMappings.setMapping` updates only persistence on that SDK. A
  test-only adapter selects the public newer setter reflectively while retaining
  261 compilation and the older SDK setup path, and explicitly reparses the fixture
  files after the workspace update. The documentation regression also
  adapts to 263's extra nullable `ScriptingOptions` parameter.
- The direct-submission guard records calls on the submitting thread. Unrelated
  background IDE readers can query the fake session's project on 263; those calls
  are not execution by the synchronous submission method. Submission requests and
  predecessor events are still checked separately.
- The recording producer now registers finished offline work before notifying
  auditors. 263's request gutter expects session work to exist when rendering a
  submitted request; returning null unconditionally could dereference an
  uninitialized last-work reference. This changes the fake session only.
- The classloader-isolation task excludes the generated test-resource descriptor:
  `prepareTest` copies it there on 263, and that core-loaded copy otherwise shadows
  the actual plugin distribution. The fixture's selected bundled plugins include
  Git, which supplies the DVCS module needed by IDEA 263's core collaboration module.
  No Git dependency is added to the shipped plugin descriptor or ZIP.

The advertised range is now **261–263**. Production classes remain compiled against
261 with Java 21 bytecode. The existing synchronous console-registry fixture fix
from `77cb17e` remains in place.

## Verified results

On 2026-09-18, the final 1.4.1 ZIP passed Plugin Verifier 1.410 with **zero binary
compatibility problems** on all four pinned targets:

| Target | Verdict |
| --- | --- |
| DataGrip DB-261.24374.56 | Compatible |
| IntelliJ IDEA IU-262.8665.81 | Compatible |
| DataGrip DB-262.10315.132 | Compatible |
| IntelliJ IDEA IU-263.4732.28 | Compatible |

The reports still contain 33 experimental and 17 internal API usages. The 17 are
the existing introspector/action-customizer usages discussed with Marketplace;
this change fixes the four binary failures rather than claiming those notices
have been approved or eliminated.

- All **445 aggregate tests** pass on the 261 SDK, including an explicit Java
  21.0.2 worker. The suite has three additional regression tests over 1.4.0.
- SQL Transpiler absent/installed checks pass on 261, both aggregate and the
  established 420 non-execution + 25 execution isolated workers.
- **220 catalog/PIPE/runtime tests** pass on current 262 with the companion absent
  and installed, and on 263 standalone. These include the actual portion retriever,
  coroutine scheduling path, action promotion, and real recording consoles.
- Execution and settings also pass in separate 25-test and 10-test workers on
  those runtime/mode combinations. Real plugin-classloader checks pass on 261/262
  in both modes and on 263 standalone, including service construction through the
  plugin's own loader.
- `buildPlugin`, `verifyEmbeddedPipes`, and the configured `verifyPlugin` task pass.
  The distribution contains exactly five JARs, with both brikk-sql 0.15.0 JARs
  byte-identical to Maven Central. Every bundled class meets the Java 21 bytecode
  ceiling. Packaged notices match the repository, and the descriptor is 1.4.1,
  `since-build=261`, `until-build=263.*`.

The core command is:

```bash
mise exec -- ./gradlew cleanTest test buildPlugin verifyEmbeddedPipes verifyPlugin \
  --no-build-cache --console=plain
```

Local installable ZIP:

```text
/home/jayson/DEV/sortdev/doris-intellij-plugin/build/distributions/doris-intellij-plugin.zip
SHA-256: 818351d6b6f343ca872305f74a084aa57346a2b33608c09d03176d4fe09f0108
```

## Optional companion limitation

The pinned test companion, SQL Transpiler 0.2.0, declares `until-build=262.*`.
263's real plugin loader rejects it. The attempted 263 installed-companion isolation
run is retained as an external verification limitation; its assertions were not
disabled and its descriptor was not altered. Coexistence is checked on 261/262,
and Doris is checked standalone on 263. A 263-compatible companion build is needed
to extend that coexistence check.

Verification commands and results are archived locally under
`/tmp/opencode/doris-1.4.1/`. The released 1.4.0 failure reports remain there alongside
the fixed candidate's reports. See the [runtime procedure](PIPE-EXECUTION-CONTRACT.md#verification-commands)
for compiling on 261 and running on a newer SDK with the six compile/instrument
exclusions. Native test execution uses recording fixtures, not a live Doris server.
