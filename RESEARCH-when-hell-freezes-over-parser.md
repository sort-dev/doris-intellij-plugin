# When Hell Freezes Over: A Native Doris Parser on the JetBrains SQL PSI

*Research spike, 2026-07-07. Evidence gathered by bytecode introspection of DataGrip 2026.1.3
(build DB-261.24374.56). Commands and raw outputs in the appendix.*

## Verdict up front

**It is not impossible.** Nothing in the platform *seals* the parser path — every class a
generated dialect grammar needs at runtime is public bytecode, the shared PSI vocabulary is public
constants, the lexer is table-driven and reusable, and the grammar generator (Grammar-Kit) is
JetBrains open source. JetBrains' moat is not access control; it is **two undocumented contracts**:

1. the **`.bnf` source** of the base SQL grammar (not shipped — only its generated output is), and
2. the **implicit PSI tree-shape contracts** that resolution, type calculation, completion,
   formatting, and inspections pattern-match against.

Both are recoverable by observation. (1) is recoverable because the generated code is a faithful,
readable image of the grammar. (2) is recoverable because we already built the tool that extracts
it: the PSI-shape harness (`DorisExceptParsingTest` et al.) dumps golden trees from the working
MySQL dialect for any construct. The moat is a *labor* moat, not a *possibility* moat.

Three routes, ranked by risk-adjusted return, are designed below. The recommended path is
**C → B → A-hybrid**, with go/no-go gates between them.

---

## 1. What the bytecode proves

Facts, each verified against DB-261 jars (commands in appendix):

| # | Fact | Evidence |
|---|------|----------|
| F1 | Each dialect grammar is **Grammar-Kit generated** static code | `MysqlGeneratedParser`, `MysqlDmlParsing`, `MysqlDdlParsing`, `MysqlExpressionParsing`, `MysqlOtherParsing`, `MysqlPlParsing` — classic GK output (`static boolean rule(PsiBuilder, int)`) |
| F2 | The handwritten dialect parser is a **thin façade** | `MysqlParser.parseQueryExpression` → `invokestatic MysqlDmlParsing.top_query_expression`; `parseSqlStatement` → `MysqlGeneratedParser.statement`; `getExtendsTokenSets` → `MysqlGeneratedParser.EXTENDS_SETS_`; root via `MysqlGeneratedParser.parse_root_` |
| F3 | The façade contract is **8 abstract methods** on `SqlParser` | `parseSqlStatement`, `parseQueryExpression`, `parseValueExpression`, `parseEvaluableExpression`, `parseDataType`, `parseForeignKeyRefList`, `parseExtraRoots`, `getExtendsTokenSets` |
| F4 | Generated code emits the **shared PSI vocabulary** | `getstatic SqlCompositeElementTypes.SQL_TABLE_REFERENCE/...` from inside `MysqlDmlParsing` — **354** shared composite types, all public |
| F5 | Dialects may **add their own element types** | `MysqlTypes` holds **1078** dialect constants; PSI classes come from `MysqlElementFactory extends SqlElementFactory` (`getStaticInfo()` registry pattern) |
| F6 | The grammar runtime is **public and subclassable** | `SqlGeneratedParserUtil` — 138 public statics (externals, `adapt_builder_`, hooks `COLLAPSE`, `UNWRAP_IF_SINGLE`, `remapStatementType`, ...); `MysqlGeneratedParserUtil extends SqlGeneratedParserUtil` proves the subclass pattern; base `GeneratedParserUtilBase` is platform OSS |
| F7 | The **lexer is table-driven**, not per-dialect flex | `SqlLexer(TokensHelper, FlexLexer)` — one shared flex core, keywords from the dialect's `TokensHelper`. (This plugin already runs `MysqlLexer` unmodified, wrapped by `DorisLexer`.) |
| F8 | Special-form parsing is **data-driven** per dialect | builtin-function map loaded from `functions.xml` next to the dialect class / `SqlFunctionsUtil.loadFunctionDefinition(dialect)` (we exploit this already) |
| F9 | Everything loads fine from a **plugin classloader** | this plugin already registers dialect, parser definition, lexer, element factory via public EPs; no package/classloader gate observed anywhere in the parse path |
| F10 | The **authoritative Doris grammar exists and is runnable** | `DorisParser.g4` = 2,389 lines, `DorisLexer.g4` = 763 lines (fe-sql-parser); we vendor a built jar and already run it per-file in the annotator |
| F11 | **No prior art** | no known third-party plugin ships a native-PSI SQL dialect; StarRocks/DuckDB/tarantool all delegate. Whoever does this first is first. |

The one thing bytecode can't give us: the `.bnf` sources and the tree-shape documentation. Section
2 is about recovering the shapes; the routes differ in how much grammar we author ourselves.

## 2. The real wall: implicit tree-shape contracts

The semantic machinery does not consume "a parse tree"; it consumes *specific shapes*. Documented
examples we hit in production, all discovered by decompilation + golden-tree diffing:

- `SqlSelectClauseImplKt.computeDasType` computes the projection from
  `SqlSelectClause.getExpressions()` and applies modifier clauses found via
  `PsiTreeUtil.getChildrenOfTypeAsList(selectClause, SqlSelectModifierClauseBase.class)` — a
  modifier clause nested one level too deep is silently invisible (the `* EXCEPT` saga).
- `SqlSelectExceptClause.adjustQueryType` resolves its **direct** `SqlReferenceExpression`
  children and calls `SqlTableType.subtract` — children of the wrong type are silently ignored.
- INSERT completion/scoping consults the `SQL_INSERT_DML_INSTRUCTION` shape (target table
  reference inside a `SQL_TABLE_COLUMN_LIST`) — a structurally valid but differently-shaped insert
  degrades completion to schema-wide fallback (the INSERT OVERWRITE saga).

**None of this is documented anywhere.** But all of it is *observable*: parse the construct with
the working MySQL dialect, dump the tree, and that dump **is** the contract.

### The golden-corpus method (the key enabler for every route)

We already own the extraction tool. The method:

1. Build a corpus of SQL constructs (start: the 40+ statements in our regression/harness suites;
   grow toward MySQL-dialect coverage).
2. For each, record the MySQL dialect's `DebugUtil.psiToString` tree → `golden/*.tree` files.
3. Any candidate parser (Route A/B/C output) must reproduce those trees **node-for-node for shared
   constructs**, and produce *stable, deliberate* shapes for Doris-only constructs.
4. Diffs are the work queue. Green corpus = shape contract satisfied to the extent our corpus
   exercises it.

This converts "undocumented contract" into "failing test list" — the difference between impossible
and expensive.

---

## 3. Route A — the full native dialect ("become JetBrains")

Author `Doris.bnf` in Grammar-Kit and generate a parser exactly the way JetBrains does, emitting
the shared PSI vocabulary.

### Design

```
grammar Doris.bnf {
  parserClass          = "dev.sort.doris.sql.gen.DorisGeneratedParser"
  parserUtilClass      = "dev.sort.doris.sql.gen.DorisGeneratedParserUtil"   // extends SqlGeneratedParserUtil (F6)
  elementTypeFactory   = "dev.sort.doris.sql.gen.DorisElementTypes.get"      // resolves names → EXISTING SqlCompositeElementTypes
  tokenTypeFactory     = "dev.sort.doris.sql.gen.DorisElementTypes.getToken" // resolves → existing SqlTokens/MysqlTokens
  extends(".*expression") = expression                                        // GK left-recursion/EXTENDS_SETS_ machinery
  ...
}
```

- **Element types**: the `elementTypeFactory` (a documented Grammar-Kit attribute) returns the
  *existing* `SqlCompositeElementTypes` constant for shared node names (reflective lookup over the
  354 constants) and mints `SqlCompositeElementType`s only for genuinely Doris-only nodes, which a
  `DorisElementFactory extends SqlElementFactory` maps to PSI classes (F5 pattern).
- **Tokens**: reuse `MysqlTokens` wholesale (we already do) — the lexer is table-driven (F7), so
  no flex work; keyword deltas go through our `TokensHelper` (already customized).
- **Façade**: `DorisParser : SqlParser` implementing the 8 abstracts (F3) by delegating to the
  generated rules — a 40-line class, structurally identical to `MysqlParser`.
- **Grammar authoring**: translate `DorisParser.g4` (2,389 lines) rule-by-rule to BNF, shaping
  each rule's emitted node against the golden corpus. ANTLR's left-recursive expression rules
  become GK `extends`-based precedence climbs — the mechanical part; the shape-matching is the
  craft part.

### The A-hybrid variant (what makes A tractable)

Generated GK code calls externals as plain static methods — and MySQL's generated rules **are
public static methods** (F1/F2). So `Doris.bnf` can declare external rules that delegate straight
to `MysqlExpressionParsing.value_expression`, `MysqlDmlParsing.top_query_expression`, etc.:

```
external mysqlValueExpr ::= <<delegateMysqlValueExpression>>   // DorisGeneratedParserUtil → MysqlExpressionParsing
```

That means Route A does **not** have to be big-bang: author Doris *statement-level* grammar first
(where Doris diverges most), delegating expressions/queries to MySQL's battle-tested rules, then
progressively pull rules in-house. Risk collapses from "rewrite 2,400 lines and match every shape"
to "own the statement layer, inherit the expression layer".

### Effort & risk

- **A-hybrid to useful parity** (all Doris statements natively typed, expressions delegated):
  ~4–8 focused weeks, corpus-driven.
- **Full A** (own everything): 2–4 months to MySQL-parity, then ongoing shape-drift maintenance
  per platform release (the golden corpus doubles as the drift detector — rerecord against each new
  DataGrip, diff, fix).
- **Risks**: internal statics (`MysqlDmlParsing.*`) are not API — signatures can move between
  releases (mitigated: our 261-pinned `since/until` means we recompile per platform generation
  anyway, and the corpus catches breakage in minutes); GK hook semantics (`COLLAPSE`,
  `UNWRAP_IF_SINGLE`) must be reverse-engineered from usage sites; PL/procedural blocks
  (`MysqlPlParsing`) are the hairiest subtree — defer, Doris barely has PL.

## 4. Route B — ANTLR shadow-replay bridge (reuse the authoritative grammar)

Don't author a grammar at all: we already ship the **real** Doris parser (fe-sql-parser). Use its
CST as a *shape oracle at parse time*.

### Design

1. Keep `DorisLexer`→`MysqlLexer` as the PsiBuilder token source (mandatory: the entire SQL
   ecosystem pattern-matches `SqlTokens` — comment sets, string handling, identifier logic. An
   ANTLR-token PSI would be rejected by everything downstream; this kills the naive
   `antlr4-intellij-adaptor` approach).
2. Per statement, run fe-sql-parser over the statement text (we already do this file-wide in the
   annotator — cost precedent exists) and collect the CST as **(startOffset, endOffset, rule)**
   events.
3. Replay: walk PsiBuilder token-by-token; before each token, open markers for CST nodes starting
   at this offset; after, close markers ending here. A **mapping table** `ANTLR rule →
   SqlCompositeElementTypes constant` (~80–120 entries to start, corpus-driven) decides which CST
   nodes materialize; unmapped nodes are transparent.
4. On ANTLR syntax errors: fall back to the current MySQL delegation for that statement
   (bounded-parse) — graceful degradation, never worse than today.

### Effort & risk

- Replay engine: 1–2 weeks. Mapping table to "better than today": +1–2 weeks, growable forever.
- **Risks**: offset alignment between MysqlLexer tokens and ANTLR tokens (both lex the same text;
  comments/whitespace differ in *tokenization* but not in *offsets* — markers are offset-driven,
  so alignment holds as long as CST boundaries land on Mysql token boundaries; corpus verifies);
  error recovery inside a broken statement is ANTLR's, which is weaker than GK's completion-aware
  recovery — **completion inside half-typed statements is the quality risk**, mitigated by the
  fallback in (4); double-parse cost per edit (annotator already pays it; PsiBuilder reparse is
  file-scoped, measure before optimizing).
- Unique advantage: the grammar is **always exactly Doris** — zero grammar-maintenance, tracks
  upstream by re-vendoring the jar.

### Gate 2 — PASSED ✅ (2026-07-07)

The replay bridge (`CstReplayer` + `ReplayMapping`, behind the `doris.replay.poc` flag) reproduces
the platform MySQL dialect's PSI tree **byte-for-byte** for a growing slice of the `mysql-core`
corpus, driven entirely by the authoritative Doris ANTLR CST. Beyond the original PoC (baseline
SELECT + JOIN) this now covers **expression nesting** (arithmetic precedence chains) and **query-tail
clauses** (LIMIT/OFFSET, DISTINCT), with GROUP BY / HAVING / ORDER BY mapped and correct. The
key flattening finding: the platform's single-child collapse (`UNWRAP_IF_SINGLE`) is reproduced *for
free* by materialising only the ANTLR **labeled** context classes (`ArithmeticBinary`, `Comparison`,
`ColumnReference`, …) and leaving the pass-through wrappers (`valueExpressionDefault`, `predicated`,
`expression`, `identifier`) transparent — no child-count heuristic needed; the grammar's labels
already encode "real node vs. pass-through". Resolver is now ~14 context-class rules + 4
context-sensitive rules + 3 synthetic wrappers (table-expression span, DISTINCT→SQL_SELECT_OPTION,
LIMIT integers→SQL_NUMERIC_LITERAL); the context-class scheme is holding up cleanly.

Byte-identical coverage (`DorisReplayPocTest` manifest): `04-baseline-select`, `05-baseline-join`,
`07-operator-precedence-chain`, `23-self-join`, `30-limit-offset-variants`, `31-distinct`. Blocked
(fail cleanly to delegation): `06`/`32` (COUNT(*) emits platform-internal `INFO:[expr:any*]`/`INFO:[0]`
empty-list marker nodes from the MySQL *generated* parser — not derivable from the CST), plus CASE /
BETWEEN / subquery / UNION / star-projection families (unmapped element types). Full suite green with
the flag both on and off.

### Gate 2.5 — PASSED ✅ (2026-07-07): mid-replay delegation (statement structure ⊕ expression handoff)

The Gate-2 wall was **function calls**: the platform renders `COUNT(*)` as `SQL_FUNCTION_CALL` carrying
platform-internal frame nodes (`INFO:[expr:any*]`, `INFO:[0]`) that are artifacts of the MySQL
*generated* parser's argument machinery — **not derivable from the ANTLR CST**. Synthesising them is
guesswork.

**The fix — stop synthesising expressions; delegate them.** The replayer runs inside `DorisPsiParser`
(a `MysqlParser`/`SqlParser` subclass), so it can call the platform's own
`parseValueExpression(builder, level, …)` → `MysqlExpressionParsing.value_expression` mid-replay. The
architecture splits cleanly:

- **Statement structure by replay.** The CST still drives clauses, joins, unions, CTEs, table refs and
  the synthetic table-expression — all shared PSI vocabulary.
- **Expressions by delegation.** At each **delegation point** — the OUTERMOST expression of a
  select-list item (`namedExpression`), WHERE / HAVING / JOIN-ON condition, GROUP BY item, or ORDER BY
  sort key — the replayer hands the builder to `parseValueExpression`, then **verifies greed**: the
  builder must advance to exactly the delegation span's end (skipping only whitespace), else the whole
  statement replay rolls back to delegation. Because that call *is* the platform code that emitted the
  golden, the function-call frames, operator nesting, CASE/BETWEEN/IN, scalar subqueries, INTERVAL
  arithmetic and window subtrees come out **byte-identical for free**.

Aliases and stars were derived from the goldens: an aliased select item wraps in `SQL_AS_EXPRESSION`
(delegated expr + bare `AS` + `SQL_IDENTIFIER` alias — *not* `SQL_COLUMN_ALIAS_DEFINITION`); a bare
`*` is modelled as `SQL_COLUMN_REFERENCE` (not a value expression), so it is emitted as structure
rather than delegated. New structural rules added to reach the whole query family: nested
`SQL_JOIN_EXPRESSION` per chained join, flat `SQL_UNION_EXPRESSION` (the ANTLR left-nested
`setOperation` collapses to one node spanning the whole `query`, so a union-level ORDER BY/LIMIT lands
inside it), `SQL_PARENTHESIZED_QUERY_EXPRESSION` for subquery/derived-table branches,
`SQL_WITH_QUERY_EXPRESSION`/`SQL_WITH_CLAUSE`/`SQL_NAMED_QUERY_DEFINITION` for CTEs, `SQL_USING_CLAUSE`
+ `SQL_REFERENCE_LIST`, and nested `SQL_REFERENCE` qualifiers for `db.tbl` table names.

**Coverage: 6 → 32 byte-identical files** — the *entire* query family of `mysql-core` (01–32), including
every file previously listed as blocked (06/32 group-by/having, 21 star+chained-joins, 08/09 CASE, 10
BETWEEN/IN/LIKE, 11–13 subqueries, 24 derived table, 25/26 UNION, 03/20 INTERVAL, 19 literal-zoo,
27/28 window, 01/02/29 CTE, 14–18 builtins, 22 USING). Greed-verification mismatches encountered during
bring-up: **zero** — delegation points are maximal expressions bounded by commas/clause keywords, so the
platform parser's greed matches the span every time. Out of replay scope (unchanged): DML/DDL
(mysql-core 33–44, non-query leads) and the error-recovery edge suite (ANTLR errors → delegation). Full
`./gradlew clean test` green with the flag on and off.

**Read on the architecture:** the *statement-structure-by-replay ⊕ expressions-by-delegation* split is
a strong candidate for the production Route B shape. Expressions are where the platform's undocumented
generated-parser frames live and where a hand-authored shape would be most fragile; delegating them to
`parseValueExpression` makes that surface exact-by-construction and maintenance-free, while the CST
drives only the statement skeleton (stable shared PSI vocabulary). The residual risk is the same one
Route B always carried — `MysqlExpressionParsing.value_expression` is an internal static, not API — but
it is the *narrowest possible* dependency on it, caught immediately by the golden corpus on any platform
bump.

### Route B UNPARKED — statement-lead replay (2026-07-07)

Gates 2 / 2.5 proved replay for the *query* family. This step extends the same
statement-structure-by-replay ⊕ expressions-by-delegation engine past `SELECT`/`WITH` to Doris
**statement leads**, replacing structureless lenient blobs with REAL typed platform PSI. Behind the same
`doris.replay.poc` flag; **flag-off is byte-identical** (`DorisGoldenCorpusTest`/`DorisRegressionTest`
untouched and green). New independent pin: `golden/replay/doris/*.tree`, recorded by the
`DorisReplayPocTest` **doris-statements manifest** (`testDorisStatementReplayShapes`), which additionally
asserts the honesty invariants per corpus file — **zero `PsiErrorElement`s**, expected top-level element
type(s), preserved statement count.

**Statement families now TYPED via replay** (all zero-error, boundary-preserving):

| Family | ANTLR context | Top PSI node | Inner structure materialised |
|---|---|---|---|
| `CREATE [OR REPLACE] VIEW … AS <q>` | `CreateViewContext` | `SQL_CREATE_VIEW_STATEMENT` | `SQL_VIEW_REFERENCE` name (+`SQL_REFERENCE` qualifier) · `SQL_AS_QUERY_CLAUSE` over the **full replayed query** (incl. lateral-view FROM, masked `* EXCEPT(...)` bodies) |
| `CREATE MATERIALIZED VIEW … AS <q>` | `CreateMTMVContext` | `SQL_CREATE_VIEW_STATEMENT` (best-fit) | as above; Doris options `BUILD/REFRESH/DISTRIBUTED` = token runs |
| Doris `CREATE TABLE` | `CreateTableContext` | `SQL_CREATE_TABLE_STATEMENT` | `SQL_TABLE_REFERENCE` name · `SQL_COLUMN_DEFINITION` per column (typed name) · `DISTRIBUTED BY HASH(cols)` → `SQL_REFERENCE_LIST`/`SQL_COLUMN_SHORT_REFERENCE` |
| `REFRESH TABLE <name>` | `RefreshTableContext` | `SQL_STATEMENT` (no platform kind fits) | `SQL_TABLE_REFERENCE` (nested `SQL_REFERENCE` for 3-part names) |
| `WARM UP … WITH TABLE <n> …` | `WarmUpClusterContext` | `SQL_STATEMENT` | `SQL_IDENTIFIER` compute group + one `SQL_TABLE_REFERENCE` per `WITH TABLE` item |
| `SWITCH <catalog>` | `SwitchCatalogContext` | `SQL_STATEMENT` | catalog `SQL_IDENTIFIER` |
| `SELECT … QUALIFY <cond>` | `QualifyClauseContext` | `SQL_SELECT_STATEMENT` | real `SQL_QUALIFY_CLAUSE` (sibling of the table-expr, like ORDER BY); its boolean/window expr **delegated** to `parseValueExpression`. Replaces the old bounded-parse path. |
| `CREATE JOB … DO <insert>` *(follow-up, 2026-07-07)* | `CreateScheduledJobContext` + `InsertTableContext` | `SQL_STATEMENT` (job wrapper; header `name ON SCHEDULE …` = token run) | DO-body insert is **REAL nested insert PSI**: `SQL_INSERT_STATEMENT` › `SQL_INSERT_DML_INSTRUCTION` › `SQL_TABLE_COLUMN_LIST` › qualified `SQL_TABLE_REFERENCE` + the source query replayed in full (`/*+ SET_VAR */` hint and masked `* EXCEPT(...)` come out as comments, exactly like the delegation goldens 09/11). Shape evidence: live dump of the platform's own MySQL `CREATE EVENT … DO INSERT` — the platform itself nests a full insert statement inside another statement. NB the constant is `SQL_TABLE_COLUMNS_LIST`; its tree debug name is `SQL_TABLE_COLUMN_LIST`. |

**Deliberately-DEFINED Doris-only shapes** (no MySQL golden exists; documented in `ReplayMapping`): the
CREATE-VIEW family reuses the platform's own MySQL CREATE VIEW skeleton (`SQL_VIEW_REFERENCE` +
`SQL_AS_QUERY_CLAUSE`) verified against a live MySQL-dialect dump; MTMV borrows it as best-fit since the
platform ships no MTMV element type. Doris CREATE TABLE **clauses** (`DISTRIBUTED BY`/`BUCKETS`/
`PROPERTIES`/key-model) are left as **plain token runs inside the typed statement** (no error elements);
column **data types** stay token runs too — `parseDataType` is a *protected* `SqlParser` method the
cross-class replay bridge cannot call, and Doris DDL type spellings (`LARGEINT`, agg-model `INT SUM`)
would be rejected by the MySQL data-type parser anyway, so the column *name* is typed/navigable while the
*type* is a stable span. REFRESH/WARM UP/SWITCH keep the generic `SQL_STATEMENT` top kind (boundary
identical to the lenient path) but now carry navigable inner references.

**Still LENIENT (with reasons):**

- **`CREATE JOB` non-plain insert bodies** — the CREATE JOB family itself is now typed (see table), but
  only the plain `INSERT INTO <name> <query>` DO-body gets the insert skeleton. The grammar's other
  `insertTable` variants (INSERT OVERWRITE TABLE, `PARTITION(...)`, `WITH LABEL`, explicit column list,
  per-insert hints, CTE-headed body) are DEFERRED behind an explicit variant gate in
  `CstReplayer.emitInsertSkeleton`: they keep the token-run body *with the query still replayed*
  (lenient-parity, zero errors) instead of a half-right typed shape. Verified: OVERWRITE-form and
  column-list-form jobs replay error-free through the gate's fallback.
- **`CANCEL` / `ADMIN` / most `SHOW` variants** — the grammar parses them (`SupportedCancel/Show/Other`
  contexts) but each has a distinct labelled context and no obviously-fitting shared statement kind; mapping
  the long tail is open-ended. They fall back cleanly to lenient (`SQL_STATEMENT`, boundary preserved).
- **`CREATE DATABASE … PROPERTIES`, routine load, resource/catalog DDL** — lenient; no shared element
  type and low navigation value today.

**Safety contract under statement replay:** unchanged and extended — any ANTLR parse error, boundary
misalignment, greed mismatch (QUALIFY/select-item expression delegation), or empty node set rolls the
builder back and returns `false`, so dispatch falls through to the existing lenient/delegation path. The
`wantsReplay` gate is a *superset* filter (query leads + REFRESH/WARM/SWITCH + the CREATE-family predicates);
the replayer itself is the real arbiter. Bring-up hit **zero** rollbacks on the corpus (all listed families
replay clean), so the fallback is a guard, not a load-bearing path here.

**Distance to flag graduation (turn replay on by default):** what I'd demand first — (1) **completion &
resolution dogfooding** inside these new shapes in a live IDE (the golden pins *tree shape*, not that
resolve/complete behave well on `SQL_VIEW_REFERENCE`/`SQL_QUALIFY_CLAUSE`/token-run DDL clauses); (2)
**half-typed / error-recovery** behavior — replay bails to lenient on ANTLR error, so completion *while
typing* a Doris statement still rides the weaker lenient path; measure it; (3) ~~CREATE JOB inner
INSERT~~ — **done** (follow-up 2026-07-07, plain-form DO-body; non-plain insertTable variants remain
gated-off and would want covering before graduation); (4) a **platform-bump rehearsal** — re-record
`golden/mysql` + `golden/replay` against the next DataGrip and confirm the diff is reviewable; (5)
performance: double-parse (ANTLR + PsiBuilder) per statement is unmeasured at file scale.

## 5. Route C — incremental mini-rules (already underway, keep going)

What this plugin already does, named honestly: hand-rolled PsiBuilder rules grafted onto MySQL
delegation. `parseBoundedQuery`, `parseLenientToQueryTail`, and the lexer masks *are* Route C.
The next increments replace lenient blobs with **properly-shaped** small rules — e.g. parse
`CREATE JOB` into a real statement node with a typed inner INSERT, model `PROPERTIES ("k"="v")` as
name-value PSI, emit `SQL_CREATE_STATEMENT`-family nodes instead of generic `SQL_STATEMENT` where a
golden tree tells us the expected shape.

- Cost: 0.5–2 days per statement family. Zero new infrastructure. Compounds forever.
- Ceiling: expressions and query internals stay MySQL's; Doris-only *semantic* modeling
  (catalogs, `* EXCEPT` projection subtraction) stays out of reach — same ceiling as today.

## 5.5 Decision matrix: fastest vs. best vs. most maintainable

*(Added after review discussion, 2026-07-07.)*

Key reframing that settles the route choice:

- **Doris drift is opt-in for B.** fe-sql-parser is vendored at a pinned SHA; upstream grammar
  churn touches us only when we choose to re-vendor. B converts "Doris moves fast" from a risk
  into scheduled maintenance — and new Doris syntax *parses for free* on re-vendor (only mapping
  entries for genuinely new statement shapes are needed).
- **Platform drift is loud for every route, given the corpus.** A dual golden corpus (trees
  recorded from the RAW MySQL dialect = upstream drift alarm; trees from DorisSQL = our
  regressions) turns shape drift into failing tests, and static-call drift into compile errors.
  Combined with the `since/until = 261.*` pin (we rebuild per platform generation anyway), all
  drift lands in a controlled upgrade window. Detection is solved; the differentiators are
  recurring costs and ceiling.

| | B (replay bridge) | A-hybrid | A-full (native GK) |
|---|---|---|---|
| Platform-drift surface | `SqlTokens` + 354 shared element types + PsiBuilder — the most stable trio in the SQL plugin (all JetBrains dialects sit on them) | **Worst:** MySQL generated statics (no API guarantee) plus everything B touches | GK externals + element types + shapes |
| Cost per platform bump | rerecord goldens, fix diffs (hours) | re-bind statics + shapes (days) | externals + shapes (hours–days) |
| Cost per Doris release | re-vendor + remap deltas; new syntax parses free | author new grammar rules | author new grammar rules, forever |
| Quality ceiling | Doris-exact parsing; weaker recovery/half-typed completion (mitigated by per-statement fallback to delegation) | transition state only | **highest:** GK completion-aware recovery, single parse |
| Time to step-change | ~3–6 weeks | ~4–8 weeks | 2–4 months |

**Conclusions:**

- *Fastest* and *most maintainable* **converge on B** — smallest dependency diet (no statics, no
  grammar authorship; grammar upkeep is outsourced to the Doris project permanently), biggest
  quality jump per unit work, per-statement fallback guarantees "never worse than today".
- *Best* (highest ceiling) is **A-full**, kept as a door with explicit entry triggers:
  (1) replay error-recovery/completion quality proves insufficient in real dogfooding,
  (2) fe-sql-parser stalls or diverges upstream, or (3) adoption justifies the ceiling.
- **A-hybrid is demoted to bridge-only.** It has the worst drift exposure (MySQL statics) and is
  never a destination — enter it only as the on-ramp to A-full, if and when A's triggers fire.
- **Nothing is thrown away on the B path:** the corpus is shared infrastructure for all routes,
  and B's rule→element-type mapping table *is* the documented shape contract that a future
  `Doris.bnf` would have to satisfy — B's artifacts are A's spec.

## 6. Recommended sequencing & gates

```
NOW ──► C: keep replacing lenient blobs with shaped mini-rules (per dogfooding pain)
  │
  ├─ Gate 1 (1-day spike): golden-corpus recorder — dump MySQL-dialect trees for the
  │   regression corpus into golden/*.tree; wire a diff assertion mode into the harness.
  │   (Prerequisite for everything; also hardens the existing suite.)
  │
  ├─ Gate 2 (2–3-day spike): Route B replay-engine PoC for ONE statement shape
  │   (`SELECT ... FROM t` → SQL_SELECT_STATEMENT/SELECT_CLAUSE/TABLE_EXPRESSION/FROM_CLAUSE).
  │   Go if: golden diff == 0 for the PoC corpus AND completion works in the sandbox IDE.
  │   No-go → stay on C, revisit after next platform release.
  │
  ├─ Gate 3 (1-week spike): A-hybrid PoC — a 30-rule Doris.bnf for CREATE TABLE
  │   (Doris clauses: DISTRIBUTED BY / BUCKETS / PROPERTIES / key types), expressions
  │   delegated to MysqlExpressionParsing externals, emitted via elementTypeFactory.
  │   Go if: GK generation works against shipped classes without source access AND the
  │   golden corpus stays green for everything MySQL already handled.
  │
  └─ Long term: whichever of B / A-hybrid survives its gate becomes the vehicle;
      full A only if JetBrains still ships no extension hooks and the plugin's user
      base justifies the maintenance tax.
```

**What would change the calculus overnight**: JetBrains exposing (a) a post-parse tree transform
hook, or (b) a type-calc/output-schema EP — the asks already documented on issue #1/#2. File the
YouTrack request before starting Gate 3; the answer might make Route A unnecessary.

## 7. Spike backlog (each ≤ 1 day, harness-verified)

1. Golden-corpus recorder + diff assertions (Gate 1).
2. Enumerate `MysqlDmlParsing`/`MysqlExpressionParsing` public rule signatures → the delegation
   menu for A-hybrid (javap script, checked into `tools/`).
3. Map `SqlGeneratedParserUtil`'s 138 externals to their `.bnf` external-rule names by diffing a
   known GK-generated OSS grammar (Grammar-Kit's own samples) against the Mysql bytecode idioms.
4. Route B offset-alignment probe: lex 50 corpus statements with both MysqlLexer and DorisLexer.g4;
   assert every ANTLR CST boundary lands on a Mysql token boundary; catalog exceptions.
5. `remapStatementType` + `COLLAPSE`/`UNWRAP_*` hook semantics: decompile call sites, write up.
6. Minimal `Doris.bnf` compile test: 5-rule grammar through Grammar-Kit against shipped
   `SqlGeneratedParserUtil` — proves the toolchain end-to-end before any real grammar work.

## Appendix — evidence log

All against `/Users/jminard/Applications/DataGrip.app` (DB-261.24374.56), `javap` from JBR 17.

```
# F1/F2 — generated classes + façade dispatch
unzip -l .../modules/intellij.database.dialects.mysqlbase.jar | grep Mysql...Parsing
javap -p -c com.intellij.sql.dialects.mysql.MysqlParser | grep invokestatic
#   parseQueryExpression → MysqlDmlParsing.top_query_expression
#   parseSqlStatement    → MysqlGeneratedParser.statement
#   getExtendsTokenSets  → MysqlGeneratedParser.EXTENDS_SETS_

# F3 — the façade contract
javap -p com.intellij.sql.dialects.base.SqlParser | grep abstract        # 8 methods

# F4 — shared vocabulary, emitted by generated code
javap -c com.intellij.sql.dialects.mysql.MysqlDmlParsing | grep 'SqlCompositeElementTypes\.'
javap -p com.intellij.sql.psi.SqlCompositeElementTypes | grep -c 'public static final'   # 354

# F5 — dialect-owned types + factory
javap -p com.intellij.sql.dialects.mysql.MysqlTypes | grep -c 'public static final'      # 1078
javap com.intellij.sql.dialects.mysql.MysqlElementFactory                                # extends SqlElementFactory

# F6 — public grammar runtime
javap -p com.intellij.sql.dialects.base.SqlGeneratedParserUtil | grep -c 'public static' # 138
javap com.intellij.sql.dialects.mysql.MysqlGeneratedParserUtil                           # extends SqlGeneratedParserUtil

# F7 — table-driven lexer
javap -p com.intellij.sql.dialects.base.SqlLexer | grep 'SqlLexer('
#   SqlLexer(SqlLanguageDialectBase, FlexLexer) / SqlLexer(TokensHelper, FlexLexer)

# F8 — function map (see DorisSqlDialect.createTokensHelper and git history for the saga)
# F10 — grammar sizing
wc -l doris/fe/fe-sql-parser/src/main/antlr4/org/apache/doris/nereids/DorisParser.g4    # 2389
wc -l .../DorisLexer.g4                                                                   # 763
```

Shape-contract evidence (from the production sagas, decompiled during fixes):
`SqlSelectClauseImplKt.computeDasType` (modifier scan on select-clause children),
`SqlSelectExceptClause.adjustQueryType` (direct-children `SqlReferenceExpression` → `subtract`),
`valid_cast_type_element` (generated, fixed token set), `MysqlBaseJdbcHelper` version probe —
see `project_version_gating_deadend` memory and commits `b608b78`, `8bb69ee`, `2d40dd5`.
