# brikk-sql integration — ideas & north star

*Forward-looking thoughts list. Nothing here is committed work; it's the direction the plugin and
the brikk-sql (polyglot sqlglot-in-Kotlin) transpiler could grow into together. Ordered roughly by
how soon it's actionable.*

## 1. The trade (on-ramp — actionable now)

brikk-sql needs the Doris function list; move the extraction **up** to brikk-sql as the single
source of truth. Our `DorisFunctions.NAMES` is already just a generated resource
(`doris-functions.txt` via `tools/generate_doris_functions.py`, sourced from Doris docs), so the
lift is clean: generator + data relocate; the plugin consumes a thin, offline-embeddable accessor.

In return: **transpilation in the plugin.** Verdict: fair and favorable (we hand over reference data
that wanted a shared home anyway; we get a feature we couldn't cheaply build). Conditions:
- Keep completion's dependency featherweight (small artifact / keep generating our `.txt` from the
  shared upstream); the heavy transpiler engine loads only when a transform is invoked. Plugin stays
  ~2 MB, works offline.
- Provenance (the generator + "from Doris docs") moves up intact so it's regenerable per Doris
  version. Natural home for the **signatures + since-version map** → quietly solves our long-standing
  "version-gated function completion" TODO.
- Stable accessor contract; license compatible with our Apache-2.0 (sqlglot is MIT).

## 2. Convert to / from Doris (first transpilation feature)

Right-click SQL → **Convert to Doris** / **Convert from Doris → pick dialect**. The plugin is the
IDE surface for brikk-sql's Doris support. Honesty: transpilation is best-effort/lossy at the edges
— the action says "review the result," never pretends exact.

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

## 5. Later brikk modules (pre-execution client-side resolution)

brikk's other modules — **virtual TVFs, parameterized views, full lineage across views-not-yet-written**
— resolve *before* SQL leaves the IDE, so Doris only ever sees plain executable SQL. This is the plugin
quietly becoming a **SQL authoring environment on top of Doris**, not just a dialect. Bigger, better
product identity — name it deliberately when the time comes.

---
*Placement: #1 is the concrete first step and the on-ramp (once we depend on brikk-sql for names,
#2 and #3 are increments on an already-present dependency). #3–#5 are the north star, not the next
sprint. We just shipped 0.5.0; this is where the road goes.*
