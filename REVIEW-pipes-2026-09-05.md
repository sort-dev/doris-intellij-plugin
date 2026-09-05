# Doris plugin review, 2026-09-05

Review baseline: `28b56ea5007649db30fe56c267284b48bbf7a27c`, plugin 1.3.0.
Scope: pipe highlighting, diagnostics, execution, coexistence with planned Trino
and DuckDB pipe implementations, and independent Doris defects.

## Referencing findings

- Use `B1`, `B2`, etc. in requests, branches, tests, and follow-up discussion.
  For example, "do B2" means implement B2 and verify its completion criteria.
- IDs are permanent. Do not renumber them when priorities change or fixes land.
- B1 and B2 are FIXED. Other findings remain OPEN unless their entries say otherwise.
  Update each finding's status and record verification when fixed.
- B21 is a conditional coexistence risk, not an observed failure with the current siblings.
- B22 is a planned architecture change. B23 is deferred design work, not part of B22.
- V1 through V3 identify review observations and verification limits, not fix requests.
- Source line numbers refer to the review baseline and may move after fixes.
- This document does not supersede `REVIEW-kimi3.md` or reuse that review's R IDs.

## Current TODOs

This queue includes the dependency-direction decision made after B1 was completed.
The original review priorities below remain a historical snapshot.

| Order | ID | Work | Status |
| --- | --- | --- | --- |
| 1 | B22 | Embed the shared transpilation library and add a separate PIPE UI toggle | High priority, planned |
| 2 | B3 | Block lossy translations and expose warnings | Open |
| 3 | B4 | Replace or constrain SQL Server DDL generation | Open |
| 4 | B5 | Preserve cached catalogs when refresh fails | Open |
| 5 | B10 | Stop execution after a claimed pipe failure | Open |
| Later | B23 | Auto-enable PIPE for projects that require it through a dependency | Deferred, design TBD |

## Finding index

| ID | Severity | Finding | Original response |
| --- | --- | --- | --- |
| B1 | P1 | Competing global Execute overrides, FIXED | Finding 1 |
| B2 | P1 | Raw semicolon splitting truncates executable queries, FIXED | Finding 2 |
| B3 | P1 | Lossy translation warnings are ignored | Finding 3 |
| B4 | P1 | Catalog-mode DDL uses SQL Server generation | Finding 4 |
| B5 | P1 | Failed catalog listing removes cached metadata | Finding 5 |
| B6 | P2 | Literal pipe markers suppress ordinary semantic errors | Finding 6, literal case |
| B7 | P2 | Line-based suppression hides neighboring statement errors | Finding 6, same-line case |
| B8 | P2 | One lexical failure clears all pipe diagnostics | Finding 7 |
| B9 | P2 | Mapped server errors are suppressed by the plugin's filter | Finding 8 |
| B10 | P2 | Claimed pipe failures fall back to raw execution | Finding 9 |
| B11 | P2 | Execute scope and variant settings are bypassed | Finding 10, scope |
| B12 | P2 | Pipe execution bypasses user-parameter processing | Finding 10, parameters |
| B13 | P2 | Pipe requests can belong to the wrong console client | Finding 10, ownership |
| B14 | P2 | Successful generation can produce invalid Doris SQL | Finding 11 |
| B15 | P2 | Definition retrieval loses catalog identity | Finding 12 |
| B16 | P2 | Run-to-caret cannot execute the initial FROM stage | Further findings, row 1 |
| B17 | P2 | Hash-only completion cache returns another pipeline's columns | Further findings, row 2 |
| B18 | P2 | Completion exposes aliases from future stages | Further findings, row 3 |
| B19 | P2 | Valid multi-host JDBC URLs fail validation | Further findings, row 4 |
| B20 | P3 | EXTEND lacks pipe keyword coloring | Further findings, row 5 |
| B21 | P2, conditional | Cancel and Explain share the override conflict pattern | Porting implications |
| B22 | High priority, planned | Embed the engine and add a PIPE toggle | Follow-up architecture decision |
| B23 | Deferred, TBD | Project-dependency-driven PIPE auto-enablement | Follow-up architecture decision |

## B22: Embed the engine and add a PIPE toggle

Status: PLANNED, high priority. Implementation has not started.

The agreed direction is to bundle a versioned shared transpilation library in each
dialect plugin, instead of using the SQL Transpiler plugin as the library provider.
Share released library code, not copies of SQL Transpiler's implementation across
the Doris, Trino, and DuckDB repositories. The Maven artifacts already exist:
SQL Transpiler currently imports `dev.brikk.house:brikk-sql-jvm:0.6.0` alongside
the metadata and verification artifacts. Use its build as the dependency reference
and its actions as the behavioral reference for the integrations being adapted.
Select and verify the required modules and compatible versions during implementation;
creating a new shared library or publishing new artifacts is not a prerequisite.

SQL Transpiler remains an independent product for cross-dialect conversion,
previews, and `.bsql` workspaces. Installing it must no longer be a prerequisite
for a dialect plugin's PIPE feature, nor the switch that enables that feature.

Scope and completion criteria:

- Bundle the shared versioned library directly and remove the provider-plugin
  dependency and classloader-presence gate once the library replacement is working.
- Add a dedicated UI control to turn PIPE on and off independently of SQL
  Transpiler. UI placement, default state, persistence scope, and the existing VM
  property's future role remain to be decided, not assumed by this TODO.
- Apply the feature setting consistently to pipe parsing/highlighting, diagnostics,
  completion, preview, and execution. Disabling PIPE must preserve ordinary SQL
  behavior and delegation to other dialect handlers.
- Preserve B1's captured-predecessor execution contract. Adapt startup registration
  so it no longer depends on the optional provider descriptor; keep handler
  registration/lifecycle separate from whether PIPE is enabled. Revisit the current
  provider-based lifecycle documentation and test setup as part of this migration.
- Audit bundled transitive dependencies, licensing, artifact size, and classloader
  isolation when multiple dialect plugins each carry the library. Do not introduce
  an equivalent shared IDE-plugin dependency under a different name.
- Verify PIPE enabled/disabled with SQL Transpiler both installed and absent, and
  verify coexistence with the sibling dialect plugins. Retain B1/B2 regressions and
  test the actual bundled library version.
- Keep the enablement decision centralized so a later project-requirement policy
  can feed it. Do not implement dependency detection or automatic enablement here;
  that is B23.

## B23: Project-dependency-driven PIPE auto-enablement

Status: DEFERRED. Design TBD; not included in B22 implementation.

Later, projects that require PIPE through a dependency should be able to enable
the feature automatically. What declares that requirement, how dependencies are
detected, project-versus-global scope, and precedence relative to an explicit user
choice are all TBD. Record those decisions before implementation; do not substitute
SQL text heuristics or SQL Transpiler installation for a project dependency signal.

Keep this future requirement in mind when designing B22's setting and enablement
logic, without selecting a dependency format or implementing an auto-enable policy now.

## Initial top five priorities

This order weights current query correctness and the planned multi-plugin rollout.
It is a recommended work order, not a change to the stable finding IDs.
B1 and B2 were completed on 2026-09-05. The current queue above now leads with B22;
this original list predates the decision to embed the transpilation library.

Complexity includes implementation and regression verification, not just patch size.
Low means a local behavior change with focused tests. Medium means shared consumers
or platform-fixture work. High means platform integration or several model/IDE modes
with broader integration tests. These are initial estimates, not delivery promises.

| Rank | ID | Work | Complexity | Reason and main cost |
| --- | --- | --- | --- | --- |
| 1 | B2 | Replace unsafe statement splitting | Medium | Already capable of silently removing filters. Requires one SQL-aware range implementation and agreement across its consumers. |
| 2 | B1 | Make Execute handlers coexist | High | Blocks safe Trino/DuckDB rollout. Requires a verified dispatch/delegation contract and tests with competing handlers and optional dependencies. |
| 3 | B3 | Block lossy translations and expose warnings | Low to medium | Known semantic changes currently execute silently. Mostly result handling, both execution entry points, preview, and regression cases. |
| 4 | B4 | Replace or constrain SQL Server DDL generation | High | Can generate the wrong operation/object kind. Full remediation requires Doris-specific DDL over the catalog model and verification on both IDE generations. |
| 5 | B5 | Preserve cached catalogs on refresh failure | Medium | A transient query failure wipes the local metadata tree. The production change may be small, but the failure/reconciliation lifecycle needs a populated-model test. |

B1 must preserve the contracts called out separately in B11, B12, and B13; its
redesign must not lock in those defects. A request for B1 alone does not silently
close those findings without their own verification. B2 supplies the boundaries
needed by B7 and should be coordinated with B6. B10 is the next execution-safety
item after this top five. For B4, disabling unsafe operations is a smaller containment
change than full Doris DDL support, but it must be recorded as containment, not a
complete implementation of the missing operations.

## B1: Competing global Execute overrides

Status: FIXED, 2026-09-05. Original severity: P1.

Review-baseline evidence: [doris-pipes.xml:26](src/main/resources/META-INF/doris-pipes.xml#L26)
replaces four global Execute actions. Unclaimed queries call the stock superclass
at [DorisPipesRunQueryAction.kt:39](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L39),
not the previously registered handler. Independent Trino or DuckDB replacements
would bypass one another according to registration order. Setting `doris.pipes=false`
does not unregister the replacements.

Completion criteria: coordinated dispatch or verified delegation preserves each
dialect's handling and stock handling for unclaimed SQL. Test competing handlers,
all four actions, feature-disabled behavior, and optional dependency absence.
The current sibling plugins were not observed registering competing Execute handlers.

Implementation and verification:

- Removed the four XML Execute replacements. The optional descriptor now registers
  a synchronous startup customizer that resolves and captures the immediate previous
  action for each ID before replacement. The platform's single base-action slot is
  not used as a delegation chain.
- Unclaimed requests call the captured predecessor with the original event. The
  wrapper preserves presentation, shortcuts, update-thread policy, dumb awareness,
  injected context, document-commit policy, live variant settings, and Selection's
  fixed options. Non-Doris contexts and statements without a pipe marker delegate
  before Doris's preparation step.
- Own pipe candidates reuse stock preparation through a per-invocation action,
  avoiding shared mutable invocation state. Structure-view FILE_EDITOR fallback
  is preserved. Returning from preparation without reaching the hook still delegates.
- A delegate-aware promoter preserves Execute priority under 262's concrete-package
  precheck, including when another cooperating wrapper is above Doris.
- The non-dynamic customizer makes installing/removing optional pipe integration
  restart-bound. This is intentional; arbitrary hot removal from immutable action
  chains is not supported.
- [PIPE-EXECUTION-CONTRACT.md](PIPE-EXECUTION-CONTRACT.md) documents the rules for
  Trino/DuckDB and reproduction commands. Every peer must cooperate; a later plugin
  that still calls stock superclasses can break its own delegation chain. No sibling
  repository changes or new shared plugin dependency were required.
- `mise exec -- ./gradlew test`: 298 tests passed.
- Full tests with `-Pb1.provider=installed` and `-Pb1.provider=absent`: 298 passed
  in each mode. Absent mode excludes the provider distribution and descriptor-bearing
  JAR, rather than merely toggling its enabled flag.
- The 10 B1 tests passed on DataGrip DB-262.10315.24 with provider installed and
  absent, running the 261-compiled/instrumented classes. Tests cover all four actions
  and all six simulated Doris/Trino/DuckDB registration orders, plus UI-state,
  option, selection, structure-editor, promotion, and restart behavior.
- `mise exec -- ./gradlew test verifyPlugin -Pb1.provider=installed`: compatible
  with DB-261.24374.56 and IU-262.8665.81, with no binary compatibility problems
  or override-only violations. Internal/experimental/deprecated API notices remain;
  the customizer API must be rechecked when upgrading supported SDK generations.
- The opt-in 262 test init script uses a newer build plugin for SDK metadata,
  removes old provider-private Jackson copies from the flattened test classpath,
  and seeds only the sandbox's Marketplace telemetry cache. Normal build dependencies
  and the user's IDE installation are unchanged. Direct recompilation against the
  latest 262 SDK still encounters the existing DorisIntrospector abstract-method
  source-API difference; the runtime matrix intentionally tests the supported
  261-built artifact instead.
- No live JDBC execution or end-to-end Trino/DuckDB pipe feature test was performed.
  These peers have not implemented the feature yet. The matrix verifies the action
  cooperation contract, not production plugin-classloader isolation or all execution
  semantics. B10, B11, B12, B13, and B21 remain open.

## B2: Raw semicolon splitting truncates executable queries

Status: FIXED, 2026-09-05. Original severity: P1.

Review-baseline evidence: [DorisPipes.kt:94](src/main/kotlin/dev/sort/doris/pipes/DorisPipes.kt#L94)
splits every semicolon. Execution uses those chunks at
[DorisPipesRunQueryAction.kt:108](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L108).
With the caret on the first line, this selects only the prefix ending inside the comment:

```sql
FROM t |> SELECT * -- ;
|> WHERE tenant_id = 7;
```

Engine probes confirm that the truncated prefix generates valid SQL without the
tenant filter. The class comment claiming chunking affects cosmetics only is stale.

Completion criteria: SQL-aware boundaries preserve literals, quoted identifiers,
comments, and complete statements. Execution, preview, stage execution, completion,
and diagnostics agree on ranges. Add quoted-semicolon and commented-semicolon
regressions, including the omitted-filter case and caret boundary cases.

Implementation and verification:

- `DorisPipes.chunks` now uses the bundled Doris tokenizer. Only delimiter tokens
  split statements; quoted text, dollar strings, and comments remain intact.
- Ranges preserve original text, line numbers, and UTF-16 editor offsets. They
  remain shared by automatic Execute, preview, stage execution, completion, and
  pipe syntax diagnostics without depending on the optional transpiler for lexing.
- Unterminated quotes/comments, unbalanced nested comments, and ambiguous unclosed
  dollar strings retain the unresolved remainder and block automatic transpilation.
  Bare identifiers starting with `$$` are handled conservatively; the diagnostic
  explains that they must be backtick-quoted to distinguish them from unfinished strings.
- Adjacent statements use half-open caret membership, so the next statement wins
  at its first character. Immediately after the final delimiter still selects the
  last statement, including when trailing whitespace follows.
- Explicit Execute selections remain unchanged. Run-to-stage keeps its existing
  stage-splitting behavior for bounded input and rejects ambiguous statement boundaries.
- Native diagnostic filtering computes the tokenized ranges once per pass rather
  than once per native error. This preserves the existing suppression predicate;
  it does not close B6 or B7.
- Regressions assert exact statement text and offsets, full generated SQL retaining
  the tenant filter at every caret position, quoted/escaped semicolons, nested and
  continued comments, dollar strings, malformed tails, UTF-16 offsets, transactions,
  and CREATE JOB headers. Fixture tests check preview ranges and common pipe/PSI
  boundary agreement with replay both on and off.
- `mise exec -- ./gradlew test --tests 'dev.sort.doris.pipes.*'`: 23 tests passed.
- `mise exec -- ./gradlew test`: 288 tests passed, no failures or skips.
- No live database request submission was exercised. Tests verify the selected
  execution input and its generated SQL, not a live JDBC round trip. This is not
  a replacement for the PSI parser or its general error-recovery behavior.
- B10's generic exception/raw-fallback issue remains open. B8's specific unclosed
  quote case is now guarded, but its catch-all can still discard diagnostics on
  other engine failures, so B8 also remains open.

## B3: Lossy translation warnings are ignored

Status: OPEN. Severity: P1.

Evidence: [DorisPipesEngine.kt:44-50](src/main/kotlin/dev/sort/doris/pipes/DorisPipesEngine.kt#L44)
returns success without checking `unsupportedMessages`. The bundled engine turns
`FROM t |> SELECT LAST_DAY(d, YEAR)` into an expression using `LAST_DAY(d)` and
reports `Date parts are not supported in LAST_DAY.` Execution and preview discard
that warning. An `APPROX_COUNT_DISTINCT` accuracy argument is another confirmed case.

Completion criteria: nonempty unsupported diagnostics prevent automatic execution
and remain visible in preview. Cover normal Execute and run-to-stage, and retain
ordinary warning-free execution. Do not assume this fixes B14.

## B4: Catalog-mode DDL uses SQL Server generation

Status: OPEN. Severity: P1.

Evidence: [DorisEditorHelpers.kt:59-62](src/main/kotlin/dev/sort/doris/catalog/DorisEditorHelpers.kt#L59)
delegates to `MsScriptGenerator` for the `Ms*` catalog model. Platform inspection
confirms SQL Server column-renaming logic using `sp_rename` and a `DROP DATABASE`
path for catalog nodes represented as `MsDatabase`. Java model compatibility does
not establish Doris SQL compatibility. A same-named database could be targeted
instead of the intended catalog; no destructive operation was executed in review.

Completion criteria: supported catalog-mode operations generate Doris SQL for the
correct object kind and qualification. Cover catalog/database/table/column DDL,
especially drop and rename. Disable unsupported operations explicitly rather than
silently using SQL Server output. Verify both supported IDE generations.

## B5: Failed catalog listing removes cached metadata

Status: OPEN. Severity: P1.

Evidence: [DorisIntrospector.kt:153-159](src/main/kotlin/dev/sort/doris/catalog/DorisIntrospector.kt#L153)
returns an empty inventory on a failed `SHOW CATALOGS`. Platform database-list
reconciliation removes existing children not renewed by that inventory. A transient
failure therefore removes local cached catalogs and their descendants. It does not
delete server data.

Completion criteria: failed enumeration preserves the last successful model and
reports failure. A successful empty inventory still reconciles normally. Test a
populated model with failed enumeration, successful enumeration, and cancellation.

## B6: Literal pipe markers suppress ordinary semantic errors

Status: OPEN. Severity: P2.

Evidence: [DorisHighlightInfoFilter.kt:123-126](src/main/kotlin/dev/sort/doris/sql/DorisHighlightInfoFilter.kt#L123)
uses raw statement text while the parser uses token-aware detection. A fixture probe
confirmed that the following ordinary query loses its unresolved-column diagnostic:

```sql
SELECT missing, '|>' FROM (SELECT 1 AS x) t;
```

Completion criteria: only actual pipe syntax activates pipe suppression. Test
markers in strings, comments, and quoted identifiers, plus real pipe statements.
Use the same classification as parsing and statement range handling.

## B7: Line-based suppression hides neighboring statement errors

Status: OPEN. Severity: P2.

Evidence: [DorisErrorAnnotator.kt:38-41](src/main/kotlin/dev/sort/doris/sql/DorisErrorAnnotator.kt#L38)
suppresses native errors by line instead of statement offsets. A fixture probe
confirmed that `SELECT FROM; FROM t |> LIMIT 1;` loses the first statement's error.

Completion criteria: an error is suppressed only inside the actual pipe statement's
range. Test ordinary and pipe statements on the same line in both orders. Reuse B2
boundaries rather than introducing another statement splitter.

## B8: One lexical failure clears all pipe diagnostics

Status: OPEN. Severity: P2.

Evidence: [DorisPipesEngine.kt:163](src/main/kotlin/dev/sort/doris/pipes/DorisPipesEngine.kt#L163)
returns an empty list from the whole diagnostic pass when any chunk throws. A fixture
probe confirmed that an unterminated string in a second pipeline removes the error
already collected for `FROM t |> WHERE;` in the first pipeline.

Completion criteria: lexical and other expected input failures produce local
diagnostics without removing earlier results. Unexpected engine failures remain
observable, and cancellation propagates. Test multiple erroneous pipe statements.

## B9: Mapped server errors are suppressed

Status: OPEN. Severity: P2.

Evidence: [DorisErrorAnnotator.kt:50](src/main/kotlin/dev/sort/doris/sql/DorisErrorAnnotator.kt#L50)
emits `Doris (server):` annotations, but
[DorisHighlightInfoFilter.kt:57-61](src/main/kotlin/dev/sort/doris/sql/DorisHighlightInfoFilter.kt#L57)
only exempts `Doris Pipes:` messages from pipe suppression. A fixture probe using
real Doris PSI confirmed that the server annotation is rejected.

Completion criteria: mapped server annotations survive filtering while irrelevant
native pipe diagnostics remain suppressed. Test both message paths and preserve
existing mark invalidation on edits or subsequent runs.

## B10: Claimed pipe failures fall back to raw execution

Status: OPEN. Severity: P2.

Evidence: [DorisPipesRunQueryAction.kt:88-92](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L88)
maps any thrown failure to "not handled." An unterminated string raises `TokenError`;
a selection containing two pipe statements raises `ShapeError`. Both escape the
engine wrapper's `ParseError` catch and reach stock execution with raw pipe input.

Completion criteria: distinguish unclaimed SQL from claimed-but-failed pipe SQL.
The latter never invokes raw fallback, including submission setup failures. Handle
multi-statement selections explicitly and propagate cancellation. Test that no
request is submitted after a claimed translation failure.

## B11: Execute scope and variant settings are bypassed

Status: OPEN. Severity: P2.

Evidence: [DorisPipesRunQueryAction.kt:101-129](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L101)
uses selection-or-caret text without honoring the requested script scope. A whole-script
run with the caret in a pipe statement can skip preceding `SET` statements and
following queries. All four Execute variants feed the same interceptor.

Completion criteria: resolve the platform's requested scope before translating,
preserve order in mixed SQL/pipe scripts, and retain variant options such as new-tab
behavior. Test current statement, selection, whole script, remaining statements,
and selection-as-one-statement semantics. Coordinate implementation with B1.

## B12: User-parameter processing is bypassed

Status: OPEN. Severity: P2.

Evidence: [DorisPipesRunQueryAction.kt:142-148](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L142)
and [submission at line 186](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L186)
submit generated SQL directly. Platform inspection shows stock execution prompts
for parameters and produces substituted console SQL before submission. Doris does
neither. The engine preserves `:x` in `FROM t |> WHERE x = :x`.

Completion criteria: recognized user parameters retain normal prompting, values,
and substitution in pipe execution, including run-to-stage. Verify actual submitted
SQL rather than merely checking the request's parameter field.

## B13: Requests can belong to the wrong console client

Status: OPEN. Severity: P2.

Evidence: [DorisPipesRunQueryAction.kt:195](src/main/kotlin/dev/sort/doris/pipes/DorisPipesRunQueryAction.kt#L195)
selects the session's first client rather than the initiating console.
[DorisPipesIntentions.kt:44-48](src/main/kotlin/dev/sort/doris/pipes/DorisPipesIntentions.kt#L44)
also matches any file belonging to any client in a console's shared session.
Platform routing is owner-filtered, so this can assign a request to the wrong client.
The exact visible tab behavior was not exercised in a live IDE.

Completion criteria: the submitting console owns the request and matches the
actual editor file. Test two consoles sharing one session, normal Execute, and
run-to-stage; verify result routing and editor anchoring.

## B14: Successful generation can produce invalid Doris SQL

Status: OPEN. Severity: P2.

Evidence: [DorisPipesEngine.kt:49-50](src/main/kotlin/dev/sort/doris/pipes/DorisPipesEngine.kt#L49)
accepts the output of `FROM t |> RENAME x AS y`. The bundled engine generates
`SELECT * RENAME (x AS y)` with no unsupported warning. The plugin's vendored Doris
grammar rejects that SQL. This was not tested against a live Doris server.

Completion criteria: unsupported stages are rejected clearly or expanded correctly;
generated SQL must pass the chosen Doris capability/output-validation gate before
execution. Include this warning-free failure and warning-bearing B3 cases.

## B15: Definition retrieval loses catalog identity

Status: OPEN. Severity: P2.

Evidence: [DorisDefinitionProvider.kt:75-85](src/main/kotlin/dev/sort/doris/DorisDefinitionProvider.kt#L75)
builds only `schema.table` names. A definition request for `hive.sales.orders` can
read `internal.sales.orders` under the connection's current catalog.
[Line 55](src/main/kotlin/dev/sort/doris/DorisDefinitionProvider.kt#L55) also treats
catalog-level `DATABASE` nodes as Doris databases for `SHOW CREATE DATABASE`.

Completion criteria: definition requests preserve full catalog/database/object
identity and choose the correct SHOW CREATE operation. Test identical object names
in different catalogs and batches spanning catalogs without leaking connection state.

## B16: The initial FROM stage cannot run alone

Status: OPEN. Severity: P2.

Evidence: [DorisPipesIntentions.kt:85-90](src/main/kotlin/dev/sort/doris/pipes/DorisPipesIntentions.kt#L85)
passes the first-stage prefix to a wrapper requiring a top-level `PipeQuery`.
`FROM t` becomes `NotPipe`, even though the engine can generate its SELECT equivalent.

Completion criteria: run-to-caret in stage 1 executes the known pipeline's FROM
prefix. Do not broaden normal interception to arbitrary FROM-first SQL in other
dialects. Test stage 1 and later stages.

## B17: Hash-only completion cache returns the wrong columns

Status: OPEN. Severity: P2.

Evidence: [DorisPipesEngine.kt:127-139](src/main/kotlin/dev/sort/doris/pipes/DorisPipesEngine.kt#L127)
uses a combined integer hash without comparing the inputs. These pipelines collide:

```sql
FROM t |> EXTEND 1 AS FB |> SELECT *
FROM t |> EXTEND 1 AS Ea |> SELECT *
```

With base column `id`, a sequential probe returned `[id, FB]` for both statements.

Completion criteria: cache keys compare all inputs used to compute shapes,
including base-table identity. Add the FB/Ea collision regression and preserve
bounded cache size.

## B18: Completion exposes aliases from future stages

Status: OPEN. Severity: P2.

Evidence: [DorisCompletionContributor.kt:184-206](src/main/kotlin/dev/sort/doris/sql/DorisCompletionContributor.kt#L184)
searches the entire pipeline for `|> AS` and JOIN aliases. Completion for `future.`
in an earlier WHERE stage can use an alias declared only in a later `|> AS future`.

Completion criteria: qualified completion only uses aliases visible at the caret's
stage. Test before and after AS and JOIN declarations, and do not interpret alias-like
text in comments or literals as declarations.

## B19: Valid multi-host JDBC URLs fail validation

Status: OPEN. Severity: P2.

Evidence: [DorisConfigValidator.kt:48-70](src/main/kotlin/dev/sort/doris/DorisConfigValidator.kt#L48)
uses `java.net.URI.host`, which is null for valid Connector/J forms such as
`jdbc:mysql://fe1:9030,fe2:9030/db`. Load-balancing and `address=(host=...)` forms
are affected too.

Completion criteria: accept supported Connector/J connection-string forms without
false missing-host errors. Test multi-host, load-balancing, host-property, and
ordinary single-host URLs, plus genuinely malformed input.

## B20: EXTEND lacks keyword coloring

Status: OPEN. Severity: P3.

Evidence: [DorisKeywordHighlighter.kt:35-36](src/main/kotlin/dev/sort/doris/sql/DorisKeywordHighlighter.kt#L35)
uses the native Doris keyword lists. A fixture probe emitted `EXTEND` as `SQL_IDENT`
with no keyword attributes, while `AGGREGATE` received keyword coloring.

Completion criteria: supported pipe-stage keywords receive intended coloring in
stage position. Preserve literal/comment coloring and test normal identifier uses
instead of globally promoting every matching word without checking context.

## B21: Cancel and Explain have the same coexistence risk

Status: OPEN, conditional. Severity: P2.

Evidence: [plugin.xml:388-404](src/main/resources/META-INF/plugin.xml#L388)
replaces global Cancel and Explain Plan actions.
[DorisCancelRunningStatementsAction.kt:128-131](src/main/kotlin/dev/sort/doris/cancel/DorisCancelRunningStatementsAction.kt#L128)
and [DorisExplainPlanAction.kt:47-51](src/main/kotlin/dev/sort/doris/plan/DorisExplainPlanAction.kt#L47)
delegate non-Doris handling to stock superclasses. Siblings copying these overrides
would repeat B1's competing-handler pattern. No current sibling conflict was established.

Completion criteria: before adding competing sibling handlers, establish coordinated
dispatch or verified delegation and test non-Doris behavior. Do not expand B1's
implementation scope to Cancel/Explain without explicitly including B21.

## Observations and verification

### V1: Highlighting registrations are dialect-scoped

No direct takeover of Trino/DuckDB syntax coloring was found. Parser, highlighter,
and annotator registrations use `DorisSQL`, and global highlight filters check the
Doris language. The shared action IDs in B1 and B21 are the coexistence concern.
Doris's bare-FROM authoring heuristic is dialect-specific and must not be copied
as a rule for intercepting ordinary DuckDB FROM-first SQL.

### V2: This review does not establish a newer engine minimum

Engine probes used brikk-sql 0.6.0. The build declares transpiler plugin 0.2.0.
These findings do not establish a requirement to upgrade that dependency to 0.3.0.
Test the actual bundled engine and each target dialect's output rather than relying
on a version-number assumption.

### V3: Verification performed and remaining gaps

- `mise exec -- ./gradlew test --rerun-tasks` passed all 278 existing tests.
- A subsequent normal `mise exec -- ./gradlew test` also passed all 278 tests.
- Five temporary fixture probes confirmed B6, B7, B8, B9, and B20. They asserted
  current defective behavior for review purposes, not successful fixes.
- Temporary probes and reports were outside the repository under
  `/tmp/opencode/doris-pipe-review-20260905/`. They are not durable regression tests.
- Engine probes confirmed the examples in B2, B3, B10, B14, B16, and B17.
- Platform bytecode inspection supplied execution and catalog lifecycle evidence.
  Inspection included DataGrip DB-262.10315.24; the Gradle fixture uses 2026.1.3.
- No live database executions, destructive operations, or three-plugin coexistence
  tests were performed. UI-specific ownership/routing behavior still needs testing.
- The test configuration excludes the transpiler from its plugin-ID allowlist and
  uses replay-off as its baseline. Helper tests do not prove optional action loading
  or all shipping-default behavior. See [build.gradle.kts:106-123](build.gradle.kts#L106).

At completion of the original code review, the worktree was clean. V3 records that
review's checks. Subsequent implementation and verification are recorded under B1 and B2.
