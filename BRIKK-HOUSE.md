# Brikk-house Kotlin editing

In IntelliJ IDEA with Kotlin K2 support, the Doris plugin automatically selects the
Brikk-house editing profile for a DorisSQL string passed directly to
`dev.brikk.house.sql.runtime.Sql.doris` inside a function annotated with
`dev.brikk.house.sql.runtime.BrikkSql`. Imports and aliases resolve by symbol.
No project PIPE setting is required. DataGrip and unrelated Doris SQL keep their
existing behavior. This plugin does not implement DuckDB support.

```kotlin
@BrikkSql
fun customers(tenant: String) = Sql.doris("""
    SELECT tenant, customer_id
    FROM customers
    WHERE tenant = $tenant
""".trimIndent())
```

The injected DorisSQL document contains `WHERE tenant = :tenant`. The right side
is a SQL parameter, not a second reference to the database column. The Kotlin
file still contains `$tenant`, so Kotlin owns navigation, usages and rename.

The profile supports:

- Scalar parameters, locals and nonconstant properties as named binds.
- `Rel` parameters as relation slots, keeping `$src()` as `src()`.
- Compiler-supported literal constants as SQL text, without evaluating getters.
- Plain SQL `:name` binds and pipe parsing in this host only.
- Kotlin's existing handling of escapes, multiline strings, `trimIndent()` and
  no-argument `trimMargin()`, with the platform's injection source mapping.

Complex interpolations and computed constant initializers produce a Brikk-house
diagnostic. Unresolved scalar symbols get a neutral inspection placeholder, not
a guessed column reference. The injection adapter does not cache classification.

An editor banner identifies the selected profile. Physical table and column
resolution still comes from the attached datasource, including DDL datasources.
There is no live-connection requirement for recognizing binds.

## Shared helper

Version 1.5.6 uses
`dev.brikk.house:brikk-engine-kotlin-intellij-support:0.1.0` from Maven Central.
The build does not use Maven Local.

`BrikkDorisTemplate` delegates recognition and classification to
`BrikkKotlinContext.inspect`. The hot injection path sets `includeShapes = false`.
The adapter consumes copied entry roles, replacement text, resolved trim names
and authored UTF-16 ranges. Pending entries do not become compiler errors. An
unavailable context defers injection and retries after resolution becomes ready,
rather than allowing Kotlin's native performer to invent database-column names.

Relation contracts are requested when SQL table-call types or PIPE completion
need them. Tests cover inherited trait columns, full/partial knowledge, property
navigation, function-local `src` contracts and trait edits without SQL edits.
Zero-argument relation slots supply columns to the platform's existing alias,
join and projection scopes. Known columns complete without a connection.

The earlier snapshot migration is recorded in
[consumer validation results](BRIKK-SHARED-HELPER-VALIDATION.md). This plugin does
not publish the shared library or execute relation templates.

## Limits

Custom wrappers and standalone SQL files do not activate this profile. Trim
calls with arguments are outside the current compiler contract.

Kotlin contracts provide column names, not exact native SQL types or ordinal
order. SQL types therefore remain unknown. Partial or unavailable contracts
must not produce confident missing-column errors. Declared properties navigate
to Kotlin; generated columns use the helper's relation-parameter fallback.

PIPE input contracts feed the bundled engine's existing stage analysis for
unqualified column completion. PIPE alias-qualified resolution/navigation and
precise SQL type/nullability diagnostics remain separate work. Mixed physical
datasource and Kotlin-slot PIPE inference is not promised by this increment.

The injected text is for inspections, not query execution. Doris PIPE run/preview
actions do not execute or present Brikk templates as compiled output. Execution
needs the compiled/composed relation, bindings, connection and pipe lowering.

## Verification

The regular suite runs without Kotlin loaded. Run the Kotlin injection suite with:

```sh
./gradlew test -Ptest.brikkKotlin=true --tests '*BrikkDoris*'
./gradlew testBrikk262 testBrikk263
```

The genuine generated-shape lane requires the reviewed IDE compiler and its
actual Brikk compiler-plugin artifact:

```sh
./gradlew testBrikkGenerated -Ptest.brikk.generatedIde=/path/to/idea-262.9437.185 -Ptest.brikk.compilerPlugin=/path/to/brikk-engine-kotlin-compiler-plugin-2.4.20-ij262-34-0.2.0.jar
```

This lane verifies compiler-generated `SourceOut` members on cold open and reads
them through the helper into the Doris SQL table type. It does not manufacture
an output interface or claim generation is implemented in the helper.

The plugin compiles against IDEA 2026.1.3 to make Kotlin APIs available. Kotlin
extensions register through an optional dependency descriptor, not a required
Kotlin dependency in DataGrip.

The adapter subclasses Kotlin's internal injection-performer base. The
no-argument `trimMargin()` adapter also uses Kotlin's indent-handler JVM accessor
through `BrikkKotlinIndentBridge.java`. Plugin verification reports these internal
API dependencies. Injection tests and binary verification cover the supported
IDEA generations; future Kotlin updates can require changes to this adapter.
