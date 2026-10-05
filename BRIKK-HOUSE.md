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

## Limits

The plugin does not supply schemas for `Rel<T>` inputs or generated call-site
shapes. Custom wrappers and standalone SQL files do not activate this profile.
Trim calls with arguments are outside the current compiler contract.

The injected text is for inspections, not query execution. Doris PIPE run/preview
actions do not execute or present Brikk templates as compiled output. Execution
needs the compiled/composed relation, bindings, connection and pipe lowering.

## Verification

The regular suite runs without Kotlin loaded. Run the Kotlin injection suite with:

```sh
./gradlew test -Ptest.brikkKotlin=true --tests '*BrikkDoris*'
./gradlew testBrikk262 testBrikk263
```

The plugin compiles against IDEA 2026.1.3 to make Kotlin APIs available. Kotlin
extensions register through an optional dependency descriptor, not a required
Kotlin dependency in DataGrip.

The adapter subclasses Kotlin's internal injection-performer base. The
no-argument `trimMargin()` adapter also uses Kotlin's indent-handler JVM accessor
through `BrikkKotlinIndentBridge.java`. Plugin verification reports these internal
API dependencies. Injection tests and binary verification cover the supported
IDEA generations; future Kotlin updates can require changes to this adapter.
