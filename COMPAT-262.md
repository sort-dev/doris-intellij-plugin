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

## Cancel feature — 3 compat problems on 262 (found 2026-07-13, RE-verify after cancel landed)

`./gradlew verifyPlugin` was NOT re-run after the query-cancel feature landed in 0.5.0, so these
262 problems shipped unnoticed (261 = Compatible; only 262 affected, and 262 is still EAP):

1. `DorisCancelRunningStatementsAction.resolveConnectionGuid(DatabaseSession)` references
   `com.intellij.database.datagrid.DataProducer` — unresolved on 262 (moved/renamed).
2. `com.intellij.database.datagrid.DataRequest.Owner` references `GridDataRequest.GridDataRequestOwner`
   — unresolved on 262.
3. Stock `com.intellij.database.actions.CancelRunningStatementsAction` (which we subclass) references
   `com.intellij.database.run.actions.GridAction` — unresolved on 262.

Risk: NoSuchClassError on 262 when cancel is invoked, and our action subclass may fail to load
(hierarchy shift). No 261 users affected; matters before 2026.2 GA. Fix like DorisMetaCompat: find
the 262 equivalents and bridge (reflection / version-agnostic API), acceptance = verifyPlugin zero
compat problems on BOTH 261 and 262. PROCESS FIX: add verifyPlugin to CI so this can't slip again.
