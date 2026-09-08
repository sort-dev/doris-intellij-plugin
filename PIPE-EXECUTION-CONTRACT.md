# Cooperative pipe execution

This is the DORIS-B1 contract for dialect plugins that intercept the IDE's Execute
actions. It applies to the 261 and 262 platform generations. Use the qualified
name DORIS-B1 across repositories; other repositories have their own B1 findings.

## Embedded library and enablement

[B22](REVIEW-pipes-2026-09-05.md#b22-embed-the-engine-and-add-a-pipe-toggle) established
the embedded engine with 0.9.0. The plugin now bundles
`dev.brikk.house:brikk-sql-jvm:0.12.0` and `brikk-sql-metadata-jvm:0.12.0`, both
confirmed on Maven Central. See [the upgrade report](REVIEW-brikk-sql-0.12.0.md)
for the verification scope and intentional refusals for unsafe input boundaries.
SQL Transpiler remains the
separate cross-dialect conversion, preview, and `.bsql` product. It is not a library
provider or a prerequisite for Doris PIPE support. No verification library or native
database engine is bundled just to obtain the core PIPE APIs.

The engine's stage offsets, source offsets, and generated-output columns use UTF-16.
Doris server errors report zero-based code-point columns. Before exact map-back,
the adapter converts the position on the generated line to a one-based UTF-16
column. It retains the original server position for reporting and rejects invalid
coordinates rather than allowing them to wrap onto another generated line.

The adapter converts the engine's intentional `UnsupportedError` lowering refusals
to `Transpile.Err`. The normal handled-error branch reports them without submission
or predecessor execution. Nonempty `unsupportedMessages` also block execution,
including run-to-stage. `Transpile.Ok` means SQL was generated, not permission to
submit it: the shared dispatch and the submission method independently enforce
the warning gate. Preview retains the original SQL/result and displays diagnostics
separately. Notification boundaries escape plain diagnostic text as HTML.

In 0.12.0, invalid pipe pagination uses these existing typed errors. Unexpanded
Doris PIPE RENAME also refuses instead of returning invalid SQL. The engine's
schema-aware rename API is tested separately; the plugin does not yet supply a
catalog to its execution path. The plugin-side B3/B10 safeguards are described in
[the execution safety report](REVIEW-pipes-B3-B10.md).

The per-project checkbox under Settings > Tools > Apache Doris PIPE defaults off.
It is stored in workspace.xml, not inferred from installed plugins. The legacy
`-Ddoris.pipes=false` veto remains; a true value does not force enablement. Setting
changes reparse cached/open Doris files and restart diagnostics without editing SQL.
Actions remain registered in either state, with interception gated on the owning
project. The captured-predecessor delegation rules below still apply.

Each product owns its embedded library through its plugin classloader. Do not pass
brikk AST, metadata, or transpilation-result objects between plugins. B1's shared
boundary uses platform action types, not brikk-specific types. Share the published
library code, not copies of SQL Transpiler's implementation.

[B23](REVIEW-pipes-2026-09-05.md#b23-project-dependency-driven-pipe-auto-enablement)
records later project-dependency-driven auto-enablement. Its dependency signal,
scope, and interaction with explicit user choices are TBD. It is not part of B22.

## Registration

Do not copy the old four `<action overrides="true">` registrations. Register a
synchronous `ActionConfigurationCustomizer.SyncHeavyCustomizeStrategy` in the
plugin descriptor instead. Doris unconditionally includes `doris-pipes.xml`.
Capture the current action
with the supplied manager's `getAction(id)` before calling `replaceAction`.

```kotlin
override fun customize(actionManager: ActionManager) {
    val previous = requireNotNull(actionManager.getAction(actionId))
    val replacement = DialectExecuteAction(previous)
    actionManager.replaceAction(actionId, replacement)
}
```

Apply this separately to all four IDs:

- `Console.Jdbc.Execute`
- `Console.Jdbc.Execute.2`
- `Console.Jdbc.Execute.3`
- `Console.Jdbc.Execute.Selection`

`getAction` must resolve the previous action's stub before replacement. Store that
exact object in the replacement. Do not look up the current action or the platform's
base-action slot during execution. The base slot stores only one action per ID,
not a per-wrapper chain. XML stubs also prevent reliable constructor-time capture.

Register synchronously, without delayed installation or suspension between capture
and replacement. Install once per customizer instance. For example, either startup
order produces a valid chain:

```text
Trino -> DuckDB -> Doris -> stock
Doris -> Trino -> DuckDB -> stock
```

Every participating plugin must delegate cooperatively. A later replacement that
calls a stock superclass instead of its captured predecessor can still break the
chain. Doris cannot repair a non-cooperating plugin's handler.

## Action behavior

- Keep a `RunQueryAction` superclass so platform consumers still recognize Execute.
  Its ordinary variant indices are 1, 2, and 3, not zero-based indices. Read the live
  settings through `getExecOption()` rather than caching a settings object.
- Selection uses the stock fixed `ExecOption` defaults with `execSelection = 1`.
  It is not a copy of the user's first Execute variant.
- Implement `ActionWithDelegate<AnAction>` and expose the captured predecessor.
- Copy the predecessor's template presentation and shortcuts. Forward `update`,
  update-thread policy, dumb awareness, injected-context policy, and document-commit
  policy. In particular, preserve Selection's hidden/disabled state without a selection.
- Decide whether the request belongs to the dialect before preparing its SQL.
  Non-owning requests must reach the predecessor's `actionPerformed` exactly once
  with the original event and without edits to the document or selection.
- A handled request includes a reported/rejected pipe query. Do not delegate after
  rejection. The action wrapper must not turn a thrown claimed failure into permission
  to run the original SQL.
- Missing submission clients are reported as a handled failure, not delegation.
  Translation, notification, request setup, registration and producer exceptions
  propagate to the IDE without retry. Cancellation is not a failure notification
  or permission to run raw SQL; optional PIPE recovery also rethrows cancellation.
- Retain the original `AnActionEvent` and `ExecOption` at the interception boundary.
  Do not reduce a shared handler contract to raw SQL or just a console reference.

Doris uses a per-invocation `RunQueryAction` preparation object for its own pipe
candidates. This reuses stock document/Info preparation without storing mutable
invocation state on a globally registered action. If stock preparation returns
without producing Info, the predecessor still gets its turn. Unclaimed execution
never uses `super.invokeImpl` as fallback.

Execution candidates require actual native-lexer PIPE operator tokens, not markers
inside literals, comments or quoted identifiers. Lexical recovery stops at the
existing B2 boundary error; it must not expose a later apparent operator inside an
unterminated construct. Ordinary input remains unclaimed even if it fails parsing.
Selections containing any real PIPE operator must contain exactly one SQL statement;
empty delimiters and comment-only trivia are not extra statements. Unsupported
mixed/multiple selections are reported without submitting any prefix or neighbor.

## Shortcut promotion

In 262, `DatabaseActionPromoter` first checks concrete action class packages.
Being a `RunQueryAction` subclass in a third-party package is not sufficient in all
editor and structure-view contexts.

Doris's `DorisPipesActionPromoter` follows `ActionWithDelegate` chains containing
Doris, asks the stock promoter about their underlying actions, and maps the result
back to the registered outer actions. It does not execute delegates. Peers should
provide equivalent promotion support for installations without Doris. Test with no
stock database-package action elsewhere in the candidate list, so that the test does
not accidentally bypass the 262 precheck.

## Lifecycle

The customizer extension point is non-dynamic. Installing, removing, or updating
the plugin requires an IDE restart. Changing the per-project PIPE setting does not:
keep registration fixed and gate handler participation rather than replacing actions
when a checkbox changes. SQL Transpiler installation/removal is independent of Doris.

Do not add a naive hot-unload restoration callback. Restoring a predecessor can
clobber a later plugin's replacement, and immutable chains can retain removed
plugins. Arbitrary hot unload would require a different, centrally owned dispatcher
with removable contributors. That is not this contract.

## Scope

B1 fixes cooperation and preserves stock execution for unclaimed requests. It does
not fix Doris's existing claimed-pipe scope, parameter, or request-owner issues
identified as B11, B12, and B13. B3/B10 now block warning-bearing translations and
remove the internal exception/raw-submission fallbacks. These changes do not prove
that warning-free generated SQL has correct semantics, and do not close B11-B14.
Do not copy the remaining execution limitations into siblings unchanged.

Cancel and Explain Plan remain separate B21 work. SQL Transpiler's explicit
`BrikkSql.ExecuteViaTranspile` action is not part of this automatic Execute chain.

## Verification commands

The normal build has no SQL Transpiler dependency. Each companion-mode invocation
uses a separate worker and sandbox. Installed mode adds the companion only for
verification; absent mode does not resolve or borrow any of its libraries.
`verifyEmbeddedPipes` checks the distribution's exact library set, bundled notices,
and removal of the provider dependency. It is also part of `check`.

```bash
mise exec -- ./gradlew test verifyEmbeddedPipes --rerun-tasks
mise exec -- ./gradlew test -Ptest.sqlTranspiler=installed --rerun-tasks
mise exec -- ./gradlew test -Ptest.sqlTranspiler=absent --rerun-tasks
mise exec -- ./gradlew test -Ptest.pluginIsolation=true -Ptest.sqlTranspiler=installed --rerun-tasks
mise exec -- ./gradlew test -Ptest.pluginIsolation=true -Ptest.sqlTranspiler=absent --rerun-tasks
```

The isolation lane keeps the platform test fixture core-loaded but loads the actual
product distributions with separate PluginClassLoaders. It checks class ownership,
different engine/metadata identities, and in-memory transpilation by both engines.
Other tests use the normal flattened fixture classpath and explicitly select Doris's
engine/metadata instead of the companion's private versions.

To include already-built sibling distributions, pass `-Ptest.trinoPluginZip=/path/to/trino.zip`
and `-Ptest.duckdbPluginZip=/path/to/duckdb.zip` in the isolation lane. Supplied paths
must exist. These are test-only inputs; the Doris build does not build or modify peers.

For a 262 runtime check, first compile and instrument with the default 261 SDK.
Then select a local 262 SDK and use the opt-in init script, which selects build
plugin 2.18.1 solely to read newer SDK metadata. Keep the six exclusions below:
the check runs the 261-compiled/instrumented classes on 262 rather than recompiling
the whole plugin against a different source API.
The init script also removes the provider's private Jackson 2.16.1 copies from
the flattened test classpath so they cannot shadow the 262 SDK's Jackson libraries.
This changes only the test worker, not the provider distribution or shipped plugin.
It seeds an empty Marketplace telemetry-ID cache in the test sandbox to avoid
depending on background downloads or stale partial cache files during registration.

```bash
mise exec -- ./gradlew test -Ptest.sqlTranspiler=installed
mise exec -- ./gradlew test -Ptest.sqlTranspiler=installed -Pdoris.localIde=/absolute/path/to/262-sdk --tests dev.sort.doris.pipes.DorisPipesActionTest --tests dev.sort.doris.pipes.DorisPipesActionWiringTest --tests dev.sort.doris.pipes.DorisPipesSettingsTest --tests dev.sort.doris.pipes.DorisPipesBoundaryTest --tests dev.sort.doris.pipes.DorisPipesTest --init-script gradle/b1-test-sdk.init.gradle -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava -x instrumentCode -x instrumentTestCode
mise exec -- ./gradlew test -Ptest.pluginIsolation=true -Ptest.sqlTranspiler=installed -Pdoris.localIde=/absolute/path/to/262-sdk --init-script gradle/b1-test-sdk.init.gradle -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava -x instrumentCode -x instrumentTestCode
```

Repeat the 262 commands with `-Ptest.sqlTranspiler=absent`. The former `b1.provider`
property is no longer a verification switch; use `test.sqlTranspiler`.

For engine upgrades, also select `DorisPipesUpgradeTest` and
`DorisEmbeddedDdlUpgradeTest` in the 262 targeted command and pass `--rerun-tasks`.
Also include `DorisPipesExecutionTest` and `DorisPipesSafetyTest` for warning,
claim/failure, cancellation and preview coverage. The execution fixture uses real
consoles and platform preparation with a recording session/producer, not JDBC.
Keep the Unicode regression assertions active. Repeat `DorisPipesSettingsTest` in
separate workers to check deferred reparse synchronization. A successful packaging
or classloader check does not override failed semantic/offset tests.
`verifyEmbeddedPipes` also checks every bundled class against the Java 21 bytecode
ceiling, since tests on a newer bundled JBR would not catch a raised runtime minimum.

These tests check action registration, all six simulated three-dialect installation
orders, all four variants, delegation/state/shortcut behavior, companion independence,
project enablement/persistence/reparsing, embedded-engine ownership, and restart
restrictions. They do not execute SQL against a database or replace
end-to-end testing of the eventual Trino and DuckDB pipe implementations.
