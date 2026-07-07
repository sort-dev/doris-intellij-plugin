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
