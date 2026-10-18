# How Doris got its dialect: when "impossible" became expensive

*A short account of a result that was supposed to be impossible. — 2026-07-07*

---

## The received wisdom

You cannot build a full-quality custom SQL dialect on DataGrip / IntelliJ. JetBrains doesn't
document the parser API, ships no grammar source, and gives you no sanctioned way in. Everyone who
looks concludes the same thing: delegate to a base dialect and live with the gaps. StarRocks did.
DuckDB did. Tarantool did. The verdict had hardened into folklore — *"that'll happen when hell
freezes over."*

## The concept

It started pragmatically: a MySQL-based Doris dialect with a lenient parser and error suppression,
published on the JetBrains Marketplace as v0.2.x and already serving real production SQL. That is
where most efforts stop.

This one didn't. The boundary kept getting tested — *is the effective version reachable? can the
tree go deeper?* — until the folklore itself was put on trial. The challenge became simple: **treat
"impossible" as a hypothesis to test**, and see where it actually failed.

## The reframe

The spike (`RESEARCH-when-hell-freezes-over-parser.md`) established the load-bearing fact by
decompiling the platform itself: **nothing is sealed.**

- Every class a generated dialect needs at runtime is public bytecode.
- The lexer is table-driven and reusable — this plugin already ran MySQL's, unmodified.
- Grammar-Kit, the grammar generator, is JetBrains open source.

The moat isn't access control. It is **two undocumented contracts**: the `.bnf` grammar source they
don't ship, and the implicit **PSI tree-shapes** that resolution, type-calculation, and completion
silently pattern-match against. Both are recoverable by observation.

> A labor moat, not a possibility moat.

## The method that cracked it

The golden-corpus technique was the turning point. Parse each construct with the existing MySQL
dialect, dump the PSI tree, and treat that dump as the undocumented specification. Every mismatch
becomes a failing test. **Instead of reverse-engineering an API, we reverse-engineered its
observable behavior** — and the platform's own working dialect served as the oracle for what
"correct" looks like.

Once the PSI became observable, every missing feature stopped being a research problem and became an
engineering backlog.

## The build — Route B (shadow-replay)

Parse each statement with the vendored Doris grammar, then replay the platform's own token stream
onto its `PsiBuilder`, mapping Doris parse-tree nodes onto the platform's real PSI vocabulary —
delegating expression spans back to the MySQL parser, and rolling back to the prior lenient parse on
any mismatch, so replay can never be *worse* than what shipped.

```
   Doris SQL text
         │
         ▼
   ANTLR parse  (vendored Doris grammar)
         │
         ▼
   Doris CST ─────────────────┐  expression spans
         │                     │  delegated to the
   node → PSI mapping           │  platform MySQL parser
         │                     │
         ▼                     ▼
   Platform PsiBuilder  ◀──────┘
         │
         ▼
   Platform PSI ── mismatch? ──▶  roll back to lenient parse
         │                        (correctness never regresses)
         ▼
   Typed Doris PSI
```

Parser replay solved syntax. Catalogs solved navigation — a real `catalog → database → table` tree
the inherited MySQL model never had: introspection, a stepped console switcher, search-path
read-back, and true Doris column types.

## The proof

Development was incremental, with every change validated against the golden corpus before landing.

- **32 of 32** query families whose dumped PSI trees are byte-for-byte identical to the platform's
  own — the drift alarm that guarantees ordinary SQL was never broken.
- Typed `CREATE VIEW` / `TABLE` / `MATERIALIZED VIEW` / `JOB`, DDL clause internals, `REFRESH`.
- Catalogs resolving three parts deep; switcher, read-back, and column types working.

Then the test against the four hardest production files in daily use — and the result: completion
and resolution held everywhere the parser was left to do its job.

Concretely, this means Doris users get the IDE behavior that previously required pretending Doris
was MySQL: inspections, completion, navigation, rename, find-references, and formatting that
understand Doris's own syntax and its catalog model instead of silently mis-parsing them.

The two halves — typed statements and catalogs — become one tree on a branch named for the prophecy
it disproves: **`froze-over`.**

## The methodology

The Doris plugin is the *demonstration*. The methodology is the *contribution*, and it is repeatable:

1. Establish that the target's PSI/tree-shape contracts are *observable*, not just documented.
2. Use an existing, working dialect as an **oracle** for correct output.
3. Capture its trees as a **golden corpus**.
4. Build a translator until every shared construct is **byte-identical** to the oracle.
5. **Delegate** to the existing parser wherever possible instead of re-authoring it.
6. **Roll back on failure**, so correctness never regresses below the prior baseline.

Nothing in that list is specific to Doris, or to SQL. The same approach applies to proprietary
query engines, custom DSLs, and languages well outside the database world — anywhere a capable host
exposes observable behavior it does not document.

## What was not done — plainly

A few things stay genuinely closed: semantic **type-calculation** for `* EXCEPT`, and output schemas
for file-valued functions like `s3()`. Those need either logic bolted on outside the parser, or
hooks only the platform vendor can ship. They were scoped out deliberately. The claim is exact: **a
first-class typed Doris dialect is possible and now exists** — not that every semantic corner is
solved.

---

The folklore was right about one thing: it took hell freezing over. It just turned out hell was a
labor estimate, not a law of nature.
