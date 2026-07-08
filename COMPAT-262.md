# 2026.2 (build 262) compatibility homework

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
