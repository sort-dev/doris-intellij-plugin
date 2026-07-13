# 2026.2 (build 262) compatibility — RESOLVED in 0.4.0

**Status: bridged.** `DorisMetaCompat` resolves the meta-model constructors reflectively (both
generations); `DorisPredicatesHelper` re-based onto `BasePredicatesHelper` (no ObjectFormatterMode
reference remains); `untilBuild = 262.*`; verifyPlugin = zero compatibility problems on both
DB-261.24374.56 and IU-262.8665.81. Remaining known-fine: deprecated `isSupported(Version)` (262's
3-arg successor routes into it; switch when 261 support drops), 11 internal-API usages (justified,
see the JetBrains thread). Original homework below for history.

# Original notes (2026-07-08, pre-fix)

Verified locally (plugin verifier vs IU-262.8665.81, 2026-07-08; `./gradlew verifyPlugin`).
0.3.0 is fenced by `untilBuild = "261.*"` — nobody on 262 gets the plugin. Fix before the
2026.2-line release:

1. `com.intellij.database.extractors.ObjectFormatterMode` unresolved in 262 —
   `DorisPredicatesHelper.getMode()` references it; find the moved/renamed replacement.
2. `BasicMetaModel.<init>(Dbms, BasicMetaObject, Class, Function)` ctor gone in 262 —
   `DorisMetaModel.buildMultiDatabaseSkeleton` must adapt to the new signature.
3. `BasicMetaObject.<init>(ObjectKind, Class, Function, BiConsumer, props, props, children)`
   ctor gone in 262 — `DorisMetaModel.node(...)` same.

Also at the same time:
- Deprecated: `DBIntrospector.Factory.isSupported(Version)` — replace with the successor.
- Re-record the golden corpus against 262 (drift alarm run: `testMysqlGoldenTrees` will name
  every construct whose platform tree moved).
- Internal-API usages (DBIntrospector.Factory, 11 hits) are expected/unavoidable — the
  introspection EP has no public variant; justification if JetBrains asks: multi-catalog
  introspection requires implementing it, as other third-party dialect plugins do.

---

## Cancel feature — RESOLVED (and the original "3 compat problems" were a false alarm)

**Correction (2026-07-13):** the section that stood here recorded 3 "unresolved grid class" compat
problems on 262 (`DataProducer`, `GridDataRequest.GridDataRequestOwner`, `GridAction`) found by a
local `verifyPlugin` run. That was a **local-verifier false positive**: JetBrains' own Marketplace
verifier (1.408) on 2026.2 **rc** (262.8665.176) reported *Compatible* with no such problems, its
IDE-run on 262.8665.81 reported *Success*, and the user ran 0.5.0's cancel live on 262 — it worked.
We chased a module-declaration fix for a non-problem; that plugin.xml change was reverted, nothing
shipped.

**What re-verifying DID surface (real, fixed same day):** the cancel action's per-console query
resolution was dead in production — `session.messageBus.dataProducer` is a `DataProducer` JDK
proxy, so reflecting `JdbcEngine.getCurrentConnection()` off it always failed, and every cancel
fell to the data-source-wide processlist net, which *refuses* when two consoles of the same data
source run concurrently (wrong-kill protection). Fixed by matching **live at cancel time**:
`connection.requestor` (the public `JdbcEngine`) → `getRequestContextIfAny()` → `request.owner` →
session client → `session.id`. All public API, no field reflection, nothing cached between presses.
Live-verified: 3 concurrent consoles, repeated stops, every kill `1 guid via 1/N live
request-owner match` — right query every time. `verifyPlugin` now exits clean on BOTH
DB-261.24374.56 and IU-262.8665.81 (the removed proxy path also removed the referenced-class flags).

Session-identity dead ends, so nobody retries them: `ConsoleRunConfiguration` is shared across a
data source's consoles (and recreated over time); the session's/requestor's `AuditService` handles
are different objects; `JdbcEngine` retains no session-typed field to reflect.

PROCESS FIX still stands: add verifyPlugin to CI (release-* branches) — and treat local
unresolved-class reports skeptically until cross-checked against the Marketplace verifier.
