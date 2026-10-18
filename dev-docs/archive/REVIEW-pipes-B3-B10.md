# B3/B10 PIPE execution safety verification

Status: **B3 and B10 fixed within the scope below; verification passed**. This change is entirely in
the plugin, based on commit `77a34ac8b072b27eaf8ff56576078b2f51b7a1d9` and the
unchanged embedded brikk-sql 0.12.0. It requires no upstream release.

## B3: generated SQL is not execution approval

`DorisPipesEngine.Transpile.Ok` retains its generated SQL, original engine result,
source map and unsupported messages. Its `executionError` identifies a nonempty
diagnostic list, including an empty-string entry, as blocked. It does not erase
information needed for preview or silently change the query to avoid a warning.

The shared `dispatchPipeTranslation` prevents a warning-bearing result reaching
submission. Execute and both run-to-stage entry points use it. The lower-level
submission method independently checks warnings, requires the engine result, and
checks that it describes the same SQL before accessing the session or constructing
a request. This avoids a bypass if a future caller forgets the dispatch gate.

Preview shows the exact generated SQL in a read-only SQL editor and all diagnostics
in a separate read-only, scrollable text area. There is no execute-anyway option.
Markup-like text remains literal in preview; notification content is escaped at
the HTML boundary and preserves line breaks. Source text, selection and document
stamps are unchanged by preview or rejection.

Regression programs include LAST_DAY YEAR/QUARTER/WEEK and approximate-count accuracy.
Each is blocked on all four Execute variants and both run-to-stage entry points.
A safe prefix preceding a later lossy stage still executes; a subsequent warning-free
query does not inherit an earlier query's warnings.

## B10: failures never grant raw fallback

The interceptor no longer catches Throwable and returns false on failure. Only an
explicit unclaimed result allows predecessor execution. Translation, notification,
request setup, registration and producer exceptions propagate to the IDE. They
cannot be converted into a second execution of the original text.

Submission returning false for a missing attached client is now a reported, handled
failure. An exception during anchored request construction no longer retries with
an unanchored request. If a producer records acceptance and then throws, the plugin
does not retry: the tests distinguish one attempted submission from failure before
submission, which must produce zero requests. This is not a claim that the server
can undo an already accepted request.

Optional PIPE recovery catches only ordinary Exception and explicitly rethrows
ProcessCanceledException and Java CancellationException. JVM Error subclasses also
propagate. This applies to stage/source-map/completion fallbacks, preview construction,
console lookup and diagnostic recovery. Native lexing and submission poll the
platform cancellation indicator. A cancellation does not generate a secondary
failure notification or invoke a predecessor.

## Ownership and boundaries

The B2 native lexer pass now records actual PIPE-token presence and nontrivia SQL
content. Doris lexes `|>` as adjacent operator tokens. Quoted, commented and
dollar-string markers do not claim ordinary SQL. Whitespace/comments between `|`
and `>` do not make an operator; malformed longer `>`-prefixed operators remain
PIPE candidates instead of becoming raw fallback.

The existing stop-at-recovery boundary guard is retained. A PIPE already encountered
before an unterminated construct keeps its unresolved tail and rejects execution.
An apparent marker inside an unterminated ordinary literal/comment is not exposed
as a new operator or statement. Ordinary SQL, including malformed ordinary SQL,
remains delegated with the identical event when no actual PIPE candidate is present.

Selections with PIPE must contain one SQL statement. PIPE+SQL, SQL+PIPE and PIPE+PIPE
selections reject without submitting any part. Empty semicolon statements and
comment-only trailing trivia are tolerated. Automatic and selected ranges use the
same lexical guard. Parser/root classification can still return NotPipe; nested-only
PIPE interception is unchanged separate work.

The per-project default-off setting, legacy veto, non-Doris delegation, original
event/options, four variants and synchronous cooperative registration remain intact.
If stock preparation never reaches its Info hook, the existing B1 delegation rule
remains. No persistent claimed-state flag is stored on the registered action.

## Regression tests

`DorisPipesExecutionTest` constructs a real JdbcConsole with a registered test
LocalDataSource, real platform document/Info preparation and the default interceptor.
Its strict fake session and recording bus stop requests at `processRequest`. The
datasource uses `jdbc:offline:doris-pipes-test`, no real endpoint or connection, and
the predecessor is a recording sentinel rather than stock execution.

Coverage includes:

- Warning-free requests retain SQL, owner and editor coupling on all four variants.
- Actual lossy messages produce one error, no request and no predecessor calls.
- Ordinary SQL, malformed ordinary input, quoted identifiers, dollar strings,
  comments, disabled projects and non-Doris consoles delegate unchanged.
- Mixed/multiple selections and selected/unselected malformed PIPE tails reject.
  Hints, nested comments, dollar/backtick strings and carets through EOF are included.
- Missing clients are handled without fallback.
- Request setup, auditor registration and producer failures are injected across
  all four variants with ordinary exceptions, PCE and CancellationException.
  The exception object is preserved and the failing operation is reached once.
- Both run-to-stage intention and menu use the warning gate and preserve safe prefixes.
- Preview creates an actual read-only editor and separate literal warning text,
  including multiline and markup-like diagnostics, without source edits or requests.
- Direct submission rejects warning-bearing, missing-result and mismatched-SQL payloads
  before session access. Notification HTML encoding has an exact-content regression.

`DorisPipesSafetyTest` covers native token ownership, mixed/trivia statements, retained
SQL/source-map/warning identity, diagnostic list nonemptiness, missing-client dispatch,
and translation/notification/submission exceptions through the shared decision and
all action variants. Recovery-helper tests preserve cancellation and fatal errors.
The older assertion that submit(false) grants delegation was changed to require a
handled failure; ordinary NotPipe delegation remains asserted.

The execution tests use real production claim/preparation/submission code, rather
than replacing the interceptor. Translation/notification fault-injection tests are
separate shared-dispatch/wrapper tests; they do not pretend to inject faults into
the engine or platform notification implementation. Request-construction injection
fails its DBMS argument lookup, before entering the request constructor.

## Verification matrix

Final fresh runs have zero failures, errors or skips:

| Check | SQL Transpiler absent | SQL Transpiler installed |
| --- | --- | --- |
| Full suite, DB-261.24374.56 | 381/381 passed | 381/381 passed |
| 261-built code on DataGrip 2026.2.5, DB-262.10315.132 | 115/115 passed | 115/115 passed |
| Real PluginClassLoader isolation on 261, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Real PluginClassLoader isolation on 262, both sibling ZIPs | 1/1 passed | 1/1 passed |
| Settings on 261, five extra workers | 45/45 passed | 45/45 passed |
| Settings on 262, five extra workers | 45/45 passed | 45/45 passed |

There are 22 new tests: 15 execution/preview/lexer fixture tests and seven safety
tests, in addition to the preserved baseline. The initial 262 execution fixture
failed because that SDK's DatabaseSession now extends UserDataHolder. The strict
fake was given normal key/value storage; production code did not change. Both
complete 261 suites and both targeted 262 suites were then rerun successfully.
The initial failure reports are retained separately from the final reports.

An independent review found notification HTML interpreting raw diagnostics. The
notification boundary now escapes text and converts line separators to HTML breaks.
The model and preview stay plain text. Exact encoded-content and decoded-text tests
pass; an initial missing entity in the new test's expected string was corrected.

`verifyEmbeddedPipes --rerun-tasks` passes with fresh and reused configuration caches.
The exact five-JAR set, Java 21 classfile ceiling, packaged notices and absence of
a SQL Transpiler provider dependency remain unchanged and verified.

Plugin Verifier 1.410 completed clean-cache offline and online runs. Both targets
report Compatible in both runs, with zero reported compatibility problems:

| Target | API notices |
| --- | --- |
| DB-261.24374.56 | 4 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |
| IU-262.8665.81 | 5 scheduled-removal, 6 deprecated, 33 experimental, 17 internal usages |

The online task completed in 3m24s. Existing SDK layout warnings and optional
integration limitations remain visible; no failure levels or problems were suppressed.
Reports are in `build/reports/pluginVerifierB3B10-online/` and
`build/reports/pluginVerifierB3B10-offline/`.

Every Gradle run uses `--rerun-tasks`. The 262 targeted selection includes both new
test classes and retains the six compile/instrument exclusions in the execution
contract. Each companion mode runs in its own worker/sandbox. Existing Trino/DuckDB
ZIPs are used read-only; no peer is rebuilt or modified. The library dependency set
and plugin version are unchanged.

Final source/diff/history and whitespace review passed. Only intended fix files
are included in the commit. No push, release or remote CI result is claimed.

## Limits and other findings

- Tests do not execute JDBC, launch drivers or query production databases. They do
  not establish server cancellation, returned results or remote audit-event delivery.
- Real popup dialogs are not opened; preview components and notification HTML payloads
  are tested directly. Unit-test mode skips console result-view creation.
- B11/B12/B13 scope, parameter and request-owner findings are not closed here. Cancel
  and Explain are separate B21 work. B23 auto-enablement remains deferred.
- B3 cannot detect warning-free but semantically wrong generated SQL. The engine's
  inherited ORDER-after-LIMIT issue and broader B14 concerns remain.
- Native annotation filtering uses actual PIPE tokens here, but this does not close
  all B6/B7 highlighting or B9 server-error suppression findings. Diagnostic recovery
  now preserves cancellation and earlier errors, without claiming unrelated UI fixes.

The pre-existing untracked `BRIKK-SQL-NEXT-FIXES.md` is preserved unchanged and is
not part of this fix. Evidence, logs and archived XML are under
`/tmp/opencode/doris-b3-b10-audit/`.
