# brikk-sql 0.15.0 dogfood verification

## Scope and published artifacts

Plugin **1.3.6** embeds `dev.brikk.house:brikk-sql-jvm:0.15.0` and
`dev.brikk.house:brikk-sql-metadata-jvm:0.15.0` from Maven Central. This follows
the local patch-version sequence after 1.3.5. The core POM requires metadata
0.15.0. The IDE continues to supply Kotlin and serialization libraries.

Upstream release evidence:

- `release/0.15.0` at `4bc4ddc1e2d12a5bbf7fb3f359b334de3327cb0d`.
- [Release CI](https://github.com/brikk/brikk-house/actions/runs/34922495797).
- Post-release documentation commit `e48f168` and
  [its CI](https://github.com/brikk/brikk-house/actions/runs/34923922403).

Both JVM artifacts' Maven Central metadata reported 0.15.0 as latest/release.
Their binary JARs, source JARs, POMs, and Gradle module metadata were downloaded
and inspected directly. The published binary SHA-256 values are:

```text
2b0cc11f23bc9ca744c4f0f3c3b2a45d90ccc364880e470a0effed882c8d0b54  brikk-sql-jvm-0.15.0.jar
62be113cc5a75d97055c0bd717303cc5ad969f5d83e235dd358a71a07dafde01  brikk-sql-metadata-jvm-0.15.0.jar
```

The source archives still contain 119 core and 20 metadata Kotlin files. Compared
with the published 0.14.0 sources, 47 core files differ and metadata source contents
are identical. The core changes include updated generated provenance headers,
AST/typing/scope helpers, grouping-set parsing, arithmetic grouping and JSON-path
generation, MySQL index parsing, and dialect-specific fixes. Doris's generator
adds 15 reserved identifiers. No PIPE transform or shape source files changed.

`THIRD_PARTY_NOTICES.md` records the new artifact names and SQLGlot pin,
`v30.18.0-43-g3ca82489`. The MIT license at that pin was checked and matches the
existing notice. The older `93d16591` headers in `GeneratedFunctionRegistry.kt`
and `Tokenizer.kt` remain accurately attributed. Function catalog provenance and
the other bundled components' notices are retained.

## Verification

All completed test runs below passed with zero failures, errors, or skips.

| Runtime | Companion mode | Coverage |
| --- | --- | --- |
| Default DB-261.24374.56 | Default | Normal aggregate test task, 442 tests |
| DB-261.24374.56 | Absent and installed | 442 aggregate tests per mode; 417 non-execution and 25 execution tests in isolated workers per mode |
| DB-261 with explicit Java 21.0.2 test launcher | Default | 442 aggregate tests and 25 execution tests in a fresh worker |
| Installed DataGrip 2026.2.5, DB-262.10315.132 | Absent and installed | 114 targeted action/settings/engine/upgrade/safety tests and 25 execution tests per mode |
| DB-261 and DB-262 | Absent and installed | 10 settings tests repeated in separate workers in each combination |
| DB-261 and DB-262 | Absent and installed | Real plugin-classloader isolation in each combination |

The aggregate contains all 439 existing testcase identities plus three new upgrade
regressions. The isolated-worker testcase union exactly matches the aggregate in
each 261 companion mode. The fixture lifecycle fix from `77cb17e` is intact;
`DorisPipesExecutionTest.kt` has no changes in this upgrade.

The added regressions exercise:

- Automatic quoting of all 15 new Doris reserved words as output aliases, while
  retaining the inferred output column names.
- Parentheses around `MOD(b, c)` when used as the right operand of multiplication
  or division. Standalone probes confirm that 0.14.0 loses those parentheses and
  0.15.0 preserves them.
- GROUPING SETS, ROLLUP, and CUBE in a head query followed by PIPE ordering and
  limiting, with native Doris parsing of the generated SQL.

The existing Unicode offsets, source maps, DDL, typed refusals, parameter storage,
and execution-safety assertions all remain active. Classloader checks verify that
Doris owns the 0.15.0 engine and metadata and transpiles through its own loader,
including coexistence with SQL Transpiler's private engine.

The 262 runs used the SDK runtime and the already compiled/instrumented 261
classes with the six exclusions from the execution contract. Explicit Java 21
runs used `/home/jayson/.local/share/mise/installs/java/21.0.2/bin/java` as the
test launcher, not just as Gradle's JVM.

Packaging and compatibility checks passed:

- `buildPlugin` and `verifyEmbeddedPipes`.
- The ZIP contains exactly the expected five JARs. Both embedded brikk-sql JARs
  match the Maven Central downloads byte for byte.
- Every bundled class is at most Java 21 bytecode, major version 65.
- The packaged LICENSE, NOTICE, and THIRD_PARTY_NOTICES.md match the repository.
  The descriptor declares version 1.3.6 and IDE builds 261 through 262.*.
- Plugin Verifier reports **Compatible** with DB-261.24374.56 and IU-262.8665.81.
  Its existing API-usage notices remain.

These checks use offline fixtures and native syntax validation, not a live Doris
server or an interactive installed-plugin session.

## Remaining upstream arithmetic issue

An exploratory extension of the modulo regression exposed a pre-existing engine
limitation. For both published 0.14.0 and 0.15.0, this input:

```sql
FROM t |> SELECT a % MOD(b, c) AS calculated |> LIMIT 2
```

generates `a % b % c`, with no warning. That changes the requested right-hand
grouping. For example, with `a=10`, `b=7`, and `c=4`, the intended result is 1,
while the generated expression yields 3. The new multiplication/division fix does
not cover this same-operator case. Explicit `a % (b % c)` preserves the parentheses
in both versions. This limitation remains an upstream follow-up; the dogfood build
does not add a plugin-side workaround. Side-by-side published-artifact probes are
archived with the verification results.

## Reproduction and local artifact

The default aggregate invocation was:

```bash
mise exec -- ./gradlew cleanTest test --no-build-cache --console=plain
```

Companion aggregate checks add `-Ptest.sqlTranspiler=absent` or `installed`.
The isolated-worker and explicit Java 21 checks retain the init scripts described
in the [0.14.0 review](REVIEW-brikk-sql-0.14.0.md#local-reproduction).
The [execution contract](PIPE-EXECUTION-CONTRACT.md#verification-commands)
documents the 262 runtime and classloader commands. Packaging used:

```bash
mise exec -- ./gradlew buildPlugin verifyEmbeddedPipes verifyPlugin --console=plain
```

All matrix test invocations used `cleanTest` and `--no-build-cache`. The 261 batch
runner reached its tool timeout partway through; it resumed at the unfinished
worker, retaining the completed workers' results. Logs, exact commands, JUnit XML snapshots, source diffs,
published-artifact hashes, and the ZIP byte checks are under
`/tmp/opencode/doris-015-review/`.

Installable local ZIP:

```text
/home/jayson/DEV/sortdev/doris-intellij-plugin/build/distributions/doris-intellij-plugin.zip
```

SHA-256:

```text
75fea9894fa25a91f43ccc141b98154234be947e9220fcfe64fbf197d0857ab5
```

This is a local dogfood build. It has not been pushed, published, tagged, released,
or installed.
