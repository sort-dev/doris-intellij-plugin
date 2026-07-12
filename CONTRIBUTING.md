# Contributing

Thanks for your interest. This is an independent, third-party JetBrains plugin for Apache Doris
(see the [README](README.md) for the non-affiliation notice). Bug reports with **genericized** SQL
are especially welcome — real, hostile SQL is how this plugin got good.

## Build & test

```bash
./gradlew buildPlugin   # → build/distributions/doris-intellij-plugin.zip
./gradlew test          # the full suite (light platform fixture, no IDE install needed)
```

**One-time credential setup:** the plugin's Doris function catalog comes from
`dev.brikk.house:brikk-sql-metadata-jvm`, published to the `brikk/public-maven` GitHub Packages
repo — which requires authentication even for public artifacts (a GitHub limitation; Maven Central
is the eventual home). Create a GitHub [Personal Access Token](https://github.com/settings/tokens)
with the **`read:packages`** scope, then provide it via either:

- `~/.gradle/gradle.properties`: `brikk.gpr.user=<your-github-username>` and `brikk.gpr.key=<PAT>`, or
- environment: `BRIKK_GPR_USER=<username>` and `BRIKK_GPR_KEY=<PAT>`.

(CI uses the `BRIKK_GPR_USER` / `BRIKK_GPR_KEY` repository secrets.)

- **JVM 21**, **Kotlin 2.3.0**, JetBrains IntelliJ Platform Gradle plugin. The DataGrip 2026.1 SDK
  is downloaded automatically on first build.
- The suite runs against a headless platform fixture; it does **not** need a running Doris server.
- One artifact targets both the **261** (2026.1) and **262** (2026.2) platform lines. Before
  changing anything near the database metadata model, run `./gradlew verifyPlugin` — it checks the
  plugin against both generations and must report **zero compatibility problems**.

> Note: the Gradle `test` task can report *up-to-date* and skip re-running after some changes. When
> in doubt, `./gradlew test --rerun-tasks` and read the counts from `build/test-results/`.

## How it's built (the short version)

Doris speaks the MySQL wire protocol, so the dialect is built on the platform's MySQL foundation and
corrects what MySQL gets wrong for Doris. Three ideas do most of the work:

1. **Lexer masking** — Doris-only tokens the MySQL grammar would choke on (`* EXCEPT(…)`,
   `INSERT OVERWRITE`, cast targets like `LARGEINT`) are masked at the lexer so statement/run-block
   boundaries stay correct.
2. **The golden corpus** — `src/test/resources/corpus/**` holds real SQL constructs; for each we
   record the platform's own PSI tree as the specification (`src/test/resources/golden/**`).
   Re-record after an intentional change with `-Pgolden.record=true` and review the diff. This is
   how we keep parity with the platform and prove we never break ordinary SQL.
3. **Shadow-replay (native typed parsing)** — statements are parsed with Doris's own vendored ANTLR
   grammar and replayed onto the platform's PSI, with expression spans delegated back to the MySQL
   parser. It rolls back to the lenient path on anything it can't cleanly type, so it is never worse
   than the fallback. (Background: `RESEARCH-when-hell-freezes-over-parser.md`, `POSTERITY.md`.)

Multi-catalog introspection, table-valued-function completion, and reliable query cancel are layered
on top; each is behind a flag that defaults on (see the README's flag table for the escape hatches).

## Conventions

- **Genericize identifiers** in tests, corpus files, and issues — use `acme_*`, never real
  company/schema names.
- New parser behavior comes with a corpus file (both dialect goldens recorded) and, where it affects
  boundaries or resolution, a regression test.
- Keep flag-off behavior equivalent to the shipped fallback; new features go behind their flag.

## The bundled Doris parser

The plugin vendors a point-in-time build of Doris's `fe-sql-parser` (unpublished upstream); see
[`vendor/README.md`](vendor/README.md) for the pinned commit and rebuild steps.
