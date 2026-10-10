# Shared Brikk helper consumer validation

Local validation on 2026-10-10, Doris plugin 1.5.5. No library publication,
Marketplace publication, remote CI run or push was performed.

## Artifact under test

`dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0-SNAPSHOT`

The helper bundled in `build/distributions/doris-intellij-plugin.zip` matches the
Maven Local JAR byte for byte. SHA-256:

```text
d4d0d3f45ba6c83fe081e459d383e7183657149b0b3a53c0dba7d51e0a889149
```

The published POM has no IntelliJ SDK or compiler dependency. The bundled helper
contains only its own classes, license and notice; no duplicate Kotlin stdlib or
IDE/compiler classes appear in the distribution.

## Consumer checks

| Check | Result |
| --- | --- |
| Native regression suite, Kotlin plugin not loaded | 477 passed |
| Doris injection consumer, IDEA 261.25134.95 | 27 passed |
| Same 261-compiled consumer, IDEA 262.8665.81 | 27 passed |
| Same 261-compiled consumer, IDEA 263.5701.42 | 27 passed |
| Real plugin-classloader isolation without Kotlin | 1 passed |
| Distribution library inventory, notices and Java 21 bytecode | Passed |

The consumer tests cover resolved aliases and stdlib trims, expanded `Rel` aliases,
source/binary constants, pending versus unsupported entries, distinct inspection
placeholders, authored ranges, rename, physical DDL datasource inspections,
execution refusal and context withdrawal/retry without SQL edits.

Explicit shape requests cover declared inherited traits, generic bounds,
full/partial knowledge, property navigation, function-local contracts and trait
edits without SQL edits. Injection recognition does not enumerate shapes.

These tests do not claim compiler-generated row integration or SQL slot-column
completion. Those remain separate work. The helper's own generated-shape fixtures
are described in its README; no handwritten row was relabelled as generated here.

## Binary verification

Plugin Verifier reported compatible on all five pinned targets:

- DataGrip 261.24374.56
- IDEA 262.8665.81
- DataGrip 262.10315.132
- IDEA 263.4732.28
- IDEA 263.5701.42

Internal, experimental and deprecated API notices remain in the reports. In
particular, Doris still uses Kotlin's internal injection-performer and indent
handling APIs. No compatibility problems or warnings were waived.

Commands used:

```sh
./gradlew test testBrikk262 testBrikk263 -Ptest.brikkKotlin=true -Pkotlin.incremental=false --tests '*BrikkDorisInjectionTest'
./gradlew test verifyEmbeddedPipes verifyPlugin -Pkotlin.incremental=false --init-script /tmp/opencode/doris-verifier.init.gradle
./gradlew test -Ptest.pluginIsolation=true -Pkotlin.incremental=false
```

The temporary verifier init script sets only `plugin.verifier.home.dir` to an
isolated cache. It does not change the compatibility gates or ignore findings.

## Release follow-up

The snapshot resolves exclusively from Maven Local. Before remote CI or a formal
release, publish the shared library through its reviewed release process, switch
the dependency to the released Central coordinate, update the bundled notices
and rerun these consumer gates against that released JAR.
