# Contributing

Thanks for helping out. Bug reports with real-world SQL are the most useful thing you can send.
Please anonymize table and schema names first.

## Setup

- JDK 21 (`mise install` picks up the version from `mise.toml`).
- Nothing else. Gradle downloads the IntelliJ IDEA 2026.1 SDK on the first build.
- You don't need a Doris server for the normal test suite.

## Everyday commands

```bash
./gradlew buildPlugin          # zip in build/distributions/
./gradlew test                 # headless test suite
./gradlew test --rerun-tasks   # force a rerun when Gradle says "up-to-date"
./gradlew verifyPlugin         # binary compatibility check against 2026.1 to 2026.3
./gradlew runIde               # sandbox IDE with the plugin installed
```

Run `verifyPlugin` before touching anything near the database metadata model. It must report zero
compatibility problems. One plugin zip serves IDE builds 261, 262 and 263. `test262` and
`test263` run the suite on the newer IDE builds.

Other useful options:

- `-Pgolden.record=true` re-records the golden parse trees. Review the diff before committing.
- `-Ptest.kotlin=present` also runs the Kotlin-host tests for
  [Brikk-house editing](dev-docs/BRIKK-HOUSE.md).
- `scripts/doris-live.sh` starts a throwaway Doris container in Docker and runs the live
  introspection tests against it.
- `runIdeNoCatalogs`, `runIdeNoReplay` and friends start the sandbox IDE with a feature flag off.

## How the plugin works

Doris speaks the MySQL protocol, so the dialect builds on the IDE's MySQL support and fixes what
MySQL gets wrong.

1. **Lexer masking.** Doris-only tokens that would confuse the MySQL grammar (`* EXCEPT(…)`,
   `INSERT OVERWRITE`, `LARGEINT`, …) are masked in the lexer so statement boundaries stay right.
2. **Golden corpus.** `src/test/resources/corpus/` holds real SQL constructs. For each one we
   record the IDE's own parse tree in `src/test/resources/golden/` as the expected result. This
   proves ordinary SQL keeps parsing the way the IDE parses it.
3. **Shadow replay.** Statements are parsed with Doris's own ANTLR grammar and replayed onto the
   IDE's parse tree. Anything it can't type cleanly falls back to the lenient path.

Multi-catalog introspection, table function completion, query cancel and PIPE sit on top. Each
has a VM option to switch it off (see the README).

### Libraries

- Doris PIPE translation and the Doris function catalog come from the `dev.brikk.house:brikk-sql-*`
  libraries on Maven Central. Versions live in `build-logic/gradle/libs.versions.toml`.
- Doris's SQL parser isn't published upstream, so we vendor a pinned build in `vendor/lib/`.
  [vendor/README.md](vendor/README.md) has the commit and rebuild steps.
- The shared build setup in `build-logic/` is vendored from the conventions repo. See
  `build-logic/VENDORED-FROM`.

## Conventions

- Use made-up identifiers (`acme_*`) in tests, corpus files and issues, never real company or
  schema names.
- New parser behavior needs a corpus file with its golden recorded. If it affects statement
  boundaries or resolution, add a regression test too.
- New features go behind a flag. With the flag off, behavior must match what already shipped.

## Releasing

Maintainers only.

1. Set the version in `build.gradle.kts` and commit to `main`.
2. Create a `release/<version>` branch from that commit and push it. If the commit has `[skip ci]`,
   start the workflow by hand with `gh workflow run release.yml --ref release/<version>`.
3. The workflow builds with `-Prelease=true`, which keeps the version as-is and refuses snapshot
   libraries. It also runs API verification on IDE builds 262 and 263.
4. New internal or experimental IDE API usages fail the release unless they're listed in
   `api-usage-approvals.txt` with owner approval.
5. The owner uploads to the JetBrains Marketplace. Passing verification doesn't mean Marketplace
   will accept the plugin.

GitHub release 1.6.1 was withdrawn. Its local zips and tag are kept as audit evidence only.

## Developer docs

- [`dev-docs/`](dev-docs/) has design research, the
  [PIPE execution contract](dev-docs/PIPE-EXECUTION-CONTRACT.md) for other plugin authors, and
  IDE compatibility notes.
- [`dev-docs/archive/`](dev-docs/archive/) keeps old reviews, upgrade reports and dogfood notes.
