# Vendored dependencies

## `lib/doris-fe-sql-parser-1.2-SNAPSHOT-g7027772afcb.jar`

The standalone Doris SQL parser (`fe-sql-parser`) from Apache Doris — the authoritative ANTLR
grammar the plugin uses for Doris-accurate parsing/validation. It is **pre-release and not
published to any public Maven repository** (not Maven Central, not Apache snapshots — Doris only
publishes its connectors/SDKs, not the FE core modules), so we vendor a locally-built jar here and
reference it directly from `build.gradle.kts` (`implementation(files(...))`).

**This jar is a point-in-time snapshot of unreleased Doris code.** Provenance:

| | |
|---|---|
| Source | Apache Doris — https://github.com/apache/doris, module `fe/fe-sql-parser` |
| Git SHA | `7027772afcbf36972662ad0c71dfc9f47bb13f4e` (short `g7027772afcb`) |
| `git describe` | `v20220306-27095-g7027772afcb` |
| Commit date | 2026-07-02 |
| Artifact version | `1.2-SNAPSHOT` (an internal Doris build version — NOT a stable coordinate; contents change under the same version) |
| License | Apache License 2.0 (see `../THIRD_PARTY_NOTICES.md`) |
| Runtime dependency | `org.antlr:antlr4-runtime:4.13.1` (resolved from Maven Central, not bundled here) |

The `fe-sql-parser` module was introduced in Doris commit `7fc8e27`
(`[feat](sql-parser) Split SQL grammar into standalone fe-sql-parser`).

### Rebuilding / refreshing this jar

From a Doris checkout, build the thin library jar with **JDK 17** (Doris FE targets 17; the default
JDK may be too new), then copy it here with the new SHA in the filename and update the reference in
`build.gradle.kts`:

```bash
# in the Doris repo
JAVA_HOME=/path/to/jdk-17 \
  mvn -f fe/pom.xml -pl fe-sql-parser -am -Pflatten install -DskipTests
# output: fe/fe-sql-parser/target/doris-fe-sql-parser.jar  (~1.3 MB)

cp fe/fe-sql-parser/target/doris-fe-sql-parser.jar \
   <plugin>/vendor/lib/doris-fe-sql-parser-1.2-SNAPSHOT-g<short-sha>.jar
```

The build takes ~15s and needs no Doris thirdparty (unlike `fe-core`); `fe-sql-parser`'s only
dependency is `antlr4-runtime`.

> Replace this with a normal Maven coordinate once Doris publishes `fe-sql-parser` to a public repo.
