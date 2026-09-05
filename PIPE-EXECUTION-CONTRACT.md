# Cooperative pipe execution

This is the DORIS-B1 contract for dialect plugins that intercept the IDE's Execute
actions. It applies to the 261 and 262 platform generations. Use the qualified
name DORIS-B1 across repositories; other repositories have their own B1 findings.

## Registration

Do not copy the old four `<action overrides="true">` registrations. Register a
synchronous `ActionConfigurationCustomizer.SyncHeavyCustomizeStrategy` in the
optional transpiler-dependent descriptor instead. Capture the current action
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
- Retain the original `AnActionEvent` and `ExecOption` at the interception boundary.
  Do not reduce a shared handler contract to raw SQL or just a console reference.

Doris uses a per-invocation `RunQueryAction` preparation object for its own pipe
candidates. This reuses stock document/Info preparation without storing mutable
invocation state on a globally registered action. If stock preparation returns
without producing Info, the predecessor still gets its turn. Unclaimed execution
never uses `super.invokeImpl` as fallback.

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

The customizer extension point is non-dynamic. Installing, removing, or changing
the optional pipe integration requires an IDE restart. Keep it in the optional
descriptor so the dialect remains engine-free without the provider.

Do not add a naive hot-unload restoration callback. Restoring a predecessor can
clobber a later plugin's replacement, and immutable chains can retain removed
plugins. Arbitrary hot unload would require a different, centrally owned dispatcher
with removable contributors. That is not this contract.

## Scope

B1 fixes cooperation and preserves stock execution for unclaimed requests. It does
not fix Doris's existing claimed-pipe scope, parameter, or request-owner issues
identified as B11, B12, and B13. B10's internal catch-all failure fallback also
remains open, despite the new action wrapper itself not catching handler failures.
Do not copy those execution internals into siblings unchanged.

Cancel and Explain Plan remain separate B21 work. SQL Transpiler's explicit
`BrikkSql.ExecuteViaTranspile` action is not part of this automatic Execute chain.

## Verification commands

The normal build and test baseline remain unchanged. Each provider-mode invocation
uses a separate worker and sandbox. Absent mode excludes both the provider's sandbox
distribution and its descriptor-bearing JAR from the worker classpath; engine
libraries remain available to helper tests.

```bash
mise exec -- ./gradlew test
mise exec -- ./gradlew test -Pb1.provider=installed --tests dev.sort.doris.pipes.DorisPipesActionTest --tests dev.sort.doris.pipes.DorisPipesActionWiringTest
mise exec -- ./gradlew test -Pb1.provider=absent --tests dev.sort.doris.pipes.DorisPipesActionTest --tests dev.sort.doris.pipes.DorisPipesActionWiringTest
```

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
mise exec -- ./gradlew test -Pb1.provider=installed --tests dev.sort.doris.pipes.DorisPipesActionTest --tests dev.sort.doris.pipes.DorisPipesActionWiringTest
mise exec -- ./gradlew test -Pb1.provider=installed -Pdoris.localIde=/absolute/path/to/262-sdk --tests dev.sort.doris.pipes.DorisPipesActionTest --tests dev.sort.doris.pipes.DorisPipesActionWiringTest --init-script gradle/b1-test-sdk.init.gradle -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava -x instrumentCode -x instrumentTestCode
mise exec -- ./gradlew test -Pb1.provider=absent -Pdoris.localIde=/absolute/path/to/262-sdk --tests dev.sort.doris.pipes.DorisPipesActionTest --tests dev.sort.doris.pipes.DorisPipesActionWiringTest --init-script gradle/b1-test-sdk.init.gradle -x compileKotlin -x compileJava -x compileTestKotlin -x compileTestJava -x instrumentCode -x instrumentTestCode
```

These tests check action registration, all six simulated three-dialect installation
orders, all four variants, delegation/state/shortcut behavior, provider absence,
and restart restrictions. They do not execute SQL against a database or replace
end-to-end testing of the eventual Trino and DuckDB pipe implementations.
