# REVIEW — risks & weaknesses (Kimi-K3, 2026-07-27)

Code review of the full main source tree (~9,700 LOC: `sql/`, `sql/replay/`, `catalog/`,
`cancel/`, `model/`, `pipes/`, `plan/`), the test suite, and the build. Tests at review time:
252 green. Overall codebase grade: **A−** — the items below are the exceptions, not the norm.

**Bad/risky first, by severity.** Each entry has file:line evidence and a one-line fix direction;
fixes themselves are the follow-up conversation.

---

## HIGH

### R1 — `SWITCH` fallback leaks catalog state on shared pooled connections
`catalog/DorisIntrospector.kt:416-434` (`runCatalogScopedOrFallback`)

On older Doris (the only case where this path fires) the introspector issues
`SWITCH <catalog>` on the pooled introspection connection and never restores the original
catalog. No `try/finally` re-switch exists — the KDoc admits the mutation. A completed or
failed fallback leaves the connection pointed at the wrong catalog for its **next borrower**,
which can be a console: wrong-catalog risk exactly on the older servers least likely to be
tested.

*Fix direction: wrap fallback in try/finally, re-`SWITCH` back.*

**FIXED** (branch `fix/kimi-R1-switch`): `runCatalogScopedOrFallback` now captures the session's
current catalog (`select current_catalog()`) before the `SWITCH` and re-`SWITCH`es in a
`finally` — best-effort, logged, and targeting the connect-time default `internal` when the
probe fails. Restore failures are logged but never mask the fallback result.

## MEDIUM

### R2 — `catch (Throwable)` swallows `ProcessCanceledException` in introspection
`catalog/DorisIntrospector.kt:153, 229, 345, 425-434`;
also `cancel/DorisCancelRunningStatementsAction.kt:182-187, 499-502`

Introspection runs under cancelable progress, but the retrievers catch bare `Throwable` →
"warn and skip per catalog." A user-cancelled refresh looks like a half-success and can persist
a partially-populated model. Breaks the platform cancellation contract.

*Fix direction: rethrow PCE before the generic catch.*

### R3 — silent failure via `runCatching` / empty catches — no diagnosability
`sql/DorisCompletionContributor.kt` — 17 `runCatching{...}.getOrNull()` (e.g. lines 90-93,
139-146, 146-172); `sql/DorisErrorAnnotator.kt:96-100` (`catch (t: Exception)` with a comment
but no log); `sql/replay/CstReplayer.kt:197` (broad catch → decline)

Graceful by design, but nothing is ever logged on these paths. A future "completion just stopped
working" report will have zero log trail. (Contrast `DorisDataTypes.kt:36`, which *does* log.)

*Fix direction: cheap logger.warn (throttled if needed) on the swallowed paths.*

### R4 — pipes shipped with spike-era simplifications that affect execution
`pipes/DorisPipes.kt:93-124, 136-146, 186-189`; `pipes/DorisPipesRunQueryAction.kt:108`;
`pipes/DorisPipesEngine.kt:89-103`

Three MVP heuristics reached the release (pipes is in the 0.7.0 change notes, plugin.xml:142):

1. **Naive `;` chunking drives the no-selection execute path.** The class KDoc
   (`DorisPipes.kt:16-18`) calls mis-splitting "cosmetic only ... never execution," but
   `PipesExecuteInterceptor` uses `chunkAt` for exactly that — a `;` inside a string literal
   **can mis-scope what runs**. The KDoc undersells it.
2. **`docHash` is `String.hashCode()`** for exec-mark invalidation — collision-prone; a
   collision paints a squiggle at a stale offset.
3. **Server-error map-back targets the first token occurrence** — repeated identifiers misplace
   the balloon line. (Exact SourceMap path exists only when the transpile result survived.)

*Fix direction: proper statement splitter aware of string literals; real hash; prefer source-map
path / document the heuristic.*

## LOW

### R5 — stale `AnActionEvent` + unguarded `invokeLater` continuations in cancel action
`cancel/DorisCancelRunningStatementsAction.kt:174, 193-211, 198-204, 341-343`

The event is captured at action-invocation time and replayed into `super.performAction`
potentially 6-11 s later (`AnActionEvent` lifetime is the invocation). EDT continuations lack
the `project.isDisposed` guard that `DorisIntrospector.kt:373` applies correctly — `offerDetach`
can show a modal dialog against a disposed project/session.

### R6 — stringly-typed coupling to two grammars' internals, failing open
`sql/replay/ReplayMapping.kt:56-184` (ANTLR context `simpleName` keys);
`sql/CstReplayer.kt:90,148,545`; `sql/DorisHighlightInfoFilter.kt:448-467` (platform inspection
message *substrings*, e.g. `" value(s) expected, got "`)

Documented, and the golden corpora pin current behavior — but a fe-sql-parser or platform
upgrade can silently rename keys/messages, and the probes fail *open* (replay quietly declines,
filters quietly stop matching): regressions surface as lost features, not crashes.

### R7 — `DorisCompletionContributor` god-file with 4× duplicated model walk
`sql/DorisCompletionContributor.kt` (611 lines)

The identical `children(o)` helper, console→dataSource→roots lookup, and namespace-chain walk
are duplicated near-verbatim four times (lines 89-103, 146-170, 229-267, 339-387). It also
reaches across package boundaries into `dev.sort.doris.pipes.*` via inline fully-qualified names
(no imports) — hidden coupling from the core SQL layer to an optional feature. Any fix to the
lookup has to be applied four times.

### R8 — pipes/plan runtime glue has almost no automated coverage
Untested: `PipesExecuteInterceptor`/`DorisPipesExecution` (submission, auditor, balloon, exec
marks — ~190 lines), `DorisPipesAutoIntrospect`, `DorisPipesNotificationProvider`,
`DorisExplainPlanAction.runExplain`/popup (only a wiring test verifies registration).
Pipes/plan test ratio ~0.14 (184 test LOC vs 1,271 main) vs 0.44 overall. Parser-side logic
(transpile, chunking, stage prefix) is covered; R4's `;`-in-literal case has no regression test
(`DorisPipesTest.kt:60-68` never exercises `;` inside a quote).

## MINOR

### R9 — misc polish
- **Per-keystroke O(file) ANTLR re-parse**: `sql/DorisErrorAnnotator.kt:32-34,97` re-parses the
  entire file text on every highlighting pass; registered as a plain `externalAnnotator`
  (plugin.xml:271) with no throttle. Latency risk on very large SQL scripts.
- **Stale/contradictory comments**: `DorisHighlightErrorFilter.kt:7-9` ("permissive SQL92 base
  parser") and `DorisKeywordHighlighter.kt:14-16` ("delegates to the SQL92 lexer") contradict the
  documented MySQL foundation in `DorisSqlDialect.kt`.
- **Documented dead code**: `ReplayMapping.kt:152-156` keeps two explicitly-UNREACHABLE mappings
  ("delete when the delegation surface is proven exhaustive").
- **`DorisMetaModel` proof-of-shape ships in `main`** (`model/DorisMetaModel.kt:77-83,104-106`):
  referenced only from tests; its reflective `require()` failures would surface as
  `ExceptionInInitializerError` if loaded on a future platform.
- **Unbounded session-scoped maps**: `DorisCancel.kt:200-248` `guidsByDataSource` (data sources
  never evicted — acknowledged, bounded by connection count), `DorisPipesAutoIntrospect.kt:23`
  `requested` set, exec-mark maps keyed by URL string. Bounded only by IDE session lifetime.
- **Fixed 1.5 s verify-after-kill window** (`DorisCancelRunningStatementsAction.kt:109,564-580`):
  a slow server ack yields a false `STILL_RUNNING` → spurious detach dialog. Self-healing, but
  the timing is a guess with no retry.

---

## For contrast — what the review found strong (one-liners)

- Decision documentation pinned to bytecode/verification evidence (`DorisSqlDialect.kt:73-83`,
  `DorisModelWrite.kt:11-46`, `CstReplayer.kt:310-322`).
- Fail-closed design everywhere: replay "never worse than today" contract (`CstReplayer.kt:46-49`),
  TVF `Schema.Open` never fabricates columns (`DorisTableFunctions.kt:41-56`), pipes engine
  isolation with classloading rationale (`DorisPipesEngine.kt:14-19`).
- Golden-corpus test discipline: 110 corpus files × 2 dialects = 240 goldens, record-mode with a
  review rule forbidding blind re-recording; both-ways suppression pinning
  (`DorisHighlightingSuppressionTest.kt:78-99`).
- Injection-safe SQL construction throughout (`DorisCancel.kt:94-124`, `DorisStringUtils.kt:4-18`,
  `DorisCatalogQueries.kt:101-108,243`); resource hygiene in `finally`/`use` on every JDBC path.
- Hygiene stats: 1 TODO/FIXME in all of `src/main`; 2 `@Suppress`.

*Full strength report intentionally trimmed here — this file exists to track the fix list.*
