# brikk-sql integration — ideas & north star

*Forward-looking thoughts list. Nothing here is committed work; it's the direction the plugin and
the brikk-sql (polyglot sqlglot-in-Kotlin) transpiler could grow into together. Ordered roughly by
how soon it's actionable.*

## 0. Artifacts (confirmed 2026-07 — `dev.brikk.house`, 0.1.0-SNAPSHOT)

Source read from `brikk/brikk-house` (private; `brikk-sql-metadata/`, `brikk-sql/`, `brikk-sql-verify/`).
- **`brikk-sql-metadata-jvm`** — the featherweight consumer contract (only dep:
  kotlinx-serialization-json). API in `FunctionCatalog.kt`: `FunctionCatalog` (case-insensitive
  `get`/`contains` by name+alias, `isTableFunction`, `.functions`, `.toJson()`), `FunctionDef`
  (name, `kind` ∈ SCALAR/AGGREGATE/WINDOW/TABLE_VALUED/TABLE_GENERATING, aliases, `overloads`
  [argTypes+returnType], `nativeKind`, `sinceVersion`), and generated per-dialect vals:
  **`DORIS_FUNCTION_CATALOG`** (826 names / 728 defs / 1434 overloads, from Doris's runtime
  registry via `tools/generate_doris_functions.py` — the generator already lives here, not in our
  repo), plus DUCKDB / TRINO catalogs. **This is our function-name source; it also subsumes TVF
  detection (`isTableFunction`) and holds the `sinceVersion` hook for version-gated completion.**
- **`brikk-sql-jvm`** — the transpiler engine (~3.4 MB). For Convert + Doris Pipes later (§2, §3).
- **`brikk-sql-verify`** — verification. NOTE (user): **will break out per-engine soon; a Doris one
  is coming and shares the same vendored `fe-sql-parser` we already embed** → the shared parser is
  the foundation for **pipes in Doris syntax** (§3). Keep on the radar.

**Consumption gotcha:** artifacts are in GitHub Packages (needs a read token even when public —
awkward for our public CI). Cleanest: publish `brikk-sql-metadata-jvm` to `brikk/public-maven`
(raw-git Maven repo, no auth; README says "coming soon") or Maven Central, so the plugin resolves
it credential-free. Decide before adding the dependency; the code change is tiny.

## 1. The trade — DONE (metadata integrated 2026-07-12)

**Shipped on branch `feat/brikk-metadata`:** the plugin now depends on
`dev.brikk.house:brikk-sql-metadata-jvm` and sources `DorisFunctions.NAMES` from
`DORIS_FUNCTION_CATALOG` (names + aliases). Our local `tools/generate_doris_functions.py` +
`doris-functions.txt` are deleted (the generator lives in brikk-house now). Bundling stays
featherweight — only the 117 KB metadata jar is added; its kotlin-stdlib + kotlinx-serialization
transitives are excluded because the IntelliJ platform provides them at runtime (verified 261+262).
Resolved from **Maven Central** (released `0.1.0`, plain `mavenCentral()`, no auth — GitHub
Packages credential dance and the interim snapshots repo both dropped). 204 tests green. Next
adoptions available from the same catalog: `isTableFunction` (cross-check TVF names), `kind`
(sharpen the completion allowlist), `sinceVersion` (version-gated completion, once populated).

### Original trade rationale (on-ramp)

brikk-sql needs the Doris function list; move the extraction **up** to brikk-sql as the single
source of truth. Our `DorisFunctions.NAMES` is already just a generated resource
(`doris-functions.txt` via `tools/generate_doris_functions.py`, sourced from Doris docs), so the
lift is clean: generator + data relocate; the plugin consumes a thin, offline-embeddable accessor.

In return: **transpilation in the plugin.** Verdict: fair and favorable (we hand over reference data
that wanted a shared home anyway; we get a feature we couldn't cheaply build). Conditions:
- Keep completion's dependency featherweight — and brikk-sql already ships the right split:
  **`brikk-sql-metadata` (99 KB, plugin-facing contract)** vs **`brikk-sql` (3.4 MB engine)**.
  Completion depends only on the 99 KB metadata artifact → plugin grows 1.7 MB → ~1.8 MB (a
  non-event; it's smaller than our own code). Works offline.
- The 3.4 MB engine is then purely a **packaging decision**, not a completion concern:
  (a) *bundle always* → ~5.1 MB base, simplest, everyone carries the engine; or (b) *optional
  companion plugin* (IntelliJ `<depends optional="true">`) → base stays ~1.8 MB, transpilation
  lights up only when the engine plugin is installed. **Lean: (b)** — matches the metadata/engine
  split, keeps the base snappy, makes Convert/virtual-syntax an opt-in install. (5 MB bundled is
  fine by Marketplace norms if simplicity wins for v1; (b) scales better as more brikk modules land.)
- Provenance (the generator + "from Doris docs") moves up intact so it's regenerable per Doris
  version. Natural home for the **signatures + since-version map** → quietly solves our long-standing
  "version-gated function completion" TODO.
- Stable accessor contract; license compatible with our Apache-2.0 (sqlglot is MIT).

## 2. Transpilation as a SEPARATE cross-dialect plugin (not a Doris feature)

The engine is dialect-agnostic — scoping it to Doris undersells it. Ship a standalone **brikk SQL
Transpiler** plugin (its own Marketplace listing) that converts between *all* supported dialects
(Doris is one node). Three-piece architecture:

```
brikk-sql-metadata (99 KB)  — shared contract: per-dialect function catalogs + accessors
        ├── Doris plugin (~1.8 MB): dialect, completion, catalogs, cancel, native parsing
        │                            depends ONLY on metadata → never carries the engine
        └── brikk SQL Transpiler plugin: bundles the 3.4 MB engine; Convert-between-dialects
                                         + (later) virtual-syntax transpile-on-run; standalone
```

Composition (why it's clean):
- Doris plugin never touches the engine → base install stays ~1.8 MB regardless of transpiler size.
- The transpiler owns the **Convert actions generically** (right-click → convert between any two
  recognized dialects); it reads the source dialect from the file's assigned dialect or the
  `-- dialect:` annotation (§4). No Doris-specific menu item, no cross-plugin wiring.
- Each stands alone; together = author Doris + convert to/from anything. Users compose.
- Virtual-syntax execution (§3) belongs to the transpiler — generic transpile-on-run against
  whatever the console connects to.

Honesty: transpilation is best-effort/lossy at the edges — the action says "review the result,"
never pretends exact.

**Repo homes** (boundaries mirror the architecture):
- `brikk` — owns the transpiler plugin + `brikk-sql` engine + `brikk-sql-metadata`. The transpiler
  plugin is the **flagship showcase of brikk-sql** (a transpiler demoing your transpiler = the best
  advert). Dependency arrows point outward *from* brikk (it publishes the contract + engine).
- `doris-intellij` (this repo) — the Doris plugin, a well-behaved *consumer* of the 99 KB
  `brikk-sql-metadata` contract. No inward coupling, no monorepo entanglement.

**Strategic note:** this is a *second product* (own listing, support surface, roadmap), not a
feature. The metadata/engine split + separate repos keep the two plugins independent, so neither
drags the other — but go in deliberately knowing it's a product line.

## 3. Virtual Doris syntax — author extended SQL, execute on vanilla Doris (north star)

Same trick as our lenient parser, one level up: accept syntax the **Doris** parser rejects (e.g.
GoogleSQL **pipe syntax** — `FROM t |> WHERE ... |> AGGREGATE ... BY ... |> ORDER BY ...`), treat it
as first-class in the editor (brikk-sql parses it → no red, real completion), and **transpile on Run**
to canonical Doris SQL sent to a cluster that never heard of pipe syntax. "Write pipe syntax, run it
on any Doris."

Two hard bits that decide magic-vs-janky:
- **Source mapping.** A server error on the transpiled SQL must point back to the user's
  virtual-syntax line (brikk-sql emits position provenance through the transform).
- **Trust surface.** A non-negotiable **"Show generated Doris SQL"** gutter action — nobody executes
  transpiled SQL blind; they see (and can run) exactly what hits the server.

Our foundation makes this tractable: lenient/masking/replay is already "survive syntax the server
won't take"; the cancel feature already taught us to intercept the statement/connection layer
(rewrite-outgoing-SQL-on-Run lives there); the golden-corpus method extends to pinning
pipe-in → Doris-SQL-out.

**Pipe + native fragments (down-the-road refinement).** You shouldn't have to rewrite a whole query
into *pure* pipe form. Let pipe operators wrap **native SQL fragments**: brikk-sql parses the pipe
*skeleton* (`FROM … |> … |> …`) and **delegates the SQL fragments to the native/target parser**,
transpiling only the pipe composition and passing the fragments through. This is the exact mirror of
our own Route B shadow-replay (structure by one grammar, expression fragments delegated to the MySQL
parser) — a pattern we already proved sound here. Payoff: incremental adoption — sprinkle pipe over
SQL you already have, fragments stay in the target dialect's own hands — and it works "over some
databases" without brikk-sql needing to fully own every dialect's expression grammar.

**"Doris Pipes" — a stage-aware feature IN the Doris plugin (the key insight).** brikk-sql exposes
the **pipe AST *before* transform** — each stage is a node carrying its original SQL. That makes the
plugin *stage-aware*, not a black-box transpile-and-run wrapper, unlocking:
- **Execute up to stage N** — run the first K pipe stages, see the intermediate result set, extend.
  Incremental query building with live feedback at each `|>`.
- **Per-stage completion** — completion at stage 4 knows the *shape* produced by stages 1–3, so it
  offers the right columns for an intermediate relation that exists nowhere in the DB.
- **Per-stage lineage/diagnostics** — "this column first appears at stage 2 / is dropped at stage 3."

These need *Doris-specific* knowledge (Doris completion, execution, result handling), so **Doris
Pipes lives in the Doris plugin** — while the *generic* cross-dialect Convert stays in the transpiler
plugin. Packaging: Doris Pipes is a Doris-plugin feature that **lights up when the engine is present**
(optional dependency on the transpiler/engine plugin). Base Doris plugin stays metadata-only and
featherweight; install the transpiler alongside → a "Doris Pipes" toggle appears and consumes
brikk-sql's pipe AST + transform-to-Doris. We *use* the engine when it's there; we never bundle it.

### Verified against brikk-sql 0.5.0 (2026-07-13; snapshot first, then re-verified byte-identical against the 0.5.0 Maven Central release)

Everything P0/P1 needs already exists in the engine — the eval found no missing API:

- **Doris dialect parses pipes natively**: `Dialects.DORIS.parseOne(pipeProgram)` → `PipeQuery` AST.
- **Run payload = 3 calls**: `parseOne` → `desugarPipes(expr)` → `expr.sql("doris")` → canonical
  CTE-form Doris SQL (`WITH __tmp1 AS (...) SELECT ...`). Note: plain
  `transpile(pipe, doris, doris)` **preserves** pipes (the Doris generator is pipe-capable) —
  desugar is explicit, so the plugin controls flatten-for-run vs keep-for-display.
- **`PipeStageSplitter.split(sql, "doris")`** returns each stage with operator + **exact character
  offsets** — the editor source map (masking spans, per-stage anchors) ships in the engine; the
  plugin doesn't build it.
- **`ParseError.errors[]`** carries per-error line/col/description/highlight — direct annotator feed.
- **Position provenance survives desugar**: semantic nodes in the desugared tree keep
  `meta={line,col,start,end}` pointing into the ORIGINAL pipe text, so server errors against the
  transpiled SQL map back to the right stage (synthetic CTE scaffolding correctly has no positions).
  Whole-program transpile + map-back therefore covers validation; per-stage prefix transpiles are
  only needed for execute-to-stage-N.
- **Cross-dialect paste** (§4) works: `transpile(clickhouse→doris)` converts functions
  (`toStartOfDay` → `DATE_TRUNC(...,'DAY')`). One fidelity bug found + filed upstream: zero-arg
  `count()` → `COUNT()` is invalid Doris (needs `COUNT(*)`); fix lands in brikk-sql **0.5.1**
  (building as of 2026-07-13 — re-verify the count case when it hits Central).
- metadata 0.5.0: same 728 defs as the plugin's pinned 0.1.0 — no migration pressure (bumping the
  plugin's metadata pin to 0.5.0 is optional ecosystem alignment, zero feature gain).

Net effect on phasing: **P0 shrinks to plugin-side plumbing** — run-path action override (the
cancel feature proved that seam), the annotator gate (skip fe-sql-parser when brikk-sql validates
the statement as a pipe program), and the review-before-run UI. P2's stage-shape completion still
wants lineage exposure from brikk-sql; the stage spans + surviving positions are its foundation.

## 4. Dialect-annotation execution (UX, folds into #3)

A leading comment marks a pasted/foreign query's dialect:

```sql
-- dialect: clickhouse
SELECT * FROM ...
```

→ that statement gets a **different run arrow**: transpile → **review** → run. On repeat runs with
**no edits since last review**, skip the review (cache the approved transpilation keyed on statement
text; invalidate on edit). Investigate: does the IntelliJ platform already **carry the source dialect
of copied/pasted SQL** (paste metadata)? If so, we could auto-populate the annotation on paste from a
non-Doris console.

## 4b. Editor error-suppression recipe for the transpiler plugin (proven in this plugin)

How this plugin makes a foreign syntax first-class inside a JetBrains SQL editor without owning
the platform grammar — the exact pattern the transpiler plugin needs for pipe syntax / annotated
foreign dialects. Three layers, in priority order:

1. **Prevent > suppress (lexer masking / replay).** Keep the foreign syntax out of the substrate
   parser entirely: mask it at the lexer into tokens the substrate never sees (`DorisLexer` masks
   `* EXCEPT(...)`; Route B replay is the industrial version). For pipes: mask the `|>` stage
   operators so the platform parses each stage as a plausible fragment — masking is what keeps
   **completion/resolve** working; suppression alone only removes red.
2. **Blanket syntax kill** — `com.intellij.highlightErrorFilter` EP (`DorisHighlightErrorFilter`,
   ~10 lines): refuse to highlight ANY `PsiErrorElement` in files you claim. Total, because the
   substrate's opinion of foreign syntax is worthless.
3. **Surgical semantic kill** — `com.intellij.daemon.highlightInfoFilter` EP
   (`DorisHighlightInfoFilter`): cheap `description.contains(<stable message substring>)`
   pre-gate, THEN a PSI-context gate proving the error structurally bogus — the genuine variant of
   the same inspection must stay red. Runs per-highlight: strings first, PSI walks second.

Non-obvious lessons (learned here, save the re-derivation):
- **The filters don't require owning the file's language** — they're plain predicates and can gate
  on a `-- dialect: X` annotation, `|>` presence, or user-data instead of `language.isKindOf`. So
  the transpiler plugin can suppress inside consoles assigned to ANY dialect (DorisSQL, stock
  MySQL, ...) with no Language registration — this is what makes §4 (annotated pasted queries)
  cheap.
- **Blanket filter and authoritative annotator ship TOGETHER, never apart.** Layer real
  diagnostics back via an annotator that runs the real parser (`DorisErrorAnnotator` runs
  fe-sql-parser; the transpiler runs brikk-sql's parse) at original offsets — otherwise the editor
  is permanently not-red and users lose all feedback.
- **Message-substring matching pins JetBrains bundle strings** — annotate every constant with the
  inspection class + bundle key (e.g. `SqlInsertValuesInspection` / `incorrect.values.number`) so
  a platform bump that rewords messages is a grep, not a mystery. Recheck per major.

## 5. Later brikk modules (pre-execution client-side resolution)

brikk's other modules — **virtual TVFs, parameterized views, full lineage across views-not-yet-written**
— resolve *before* SQL leaves the IDE, so Doris only ever sees plain executable SQL. This is the plugin
quietly becoming a **SQL authoring environment on top of Doris**, not just a dialect. Bigger, better
product identity — name it deliberately when the time comes.

---
*Placement: #1 is the concrete first step and the on-ramp (once we depend on brikk-sql for names,
#2 and #3 are increments on an already-present dependency). #3–#5 are the north star, not the next
sprint. We just shipped 0.5.0; this is where the road goes.*
