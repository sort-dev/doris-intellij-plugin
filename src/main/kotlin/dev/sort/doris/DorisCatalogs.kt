package dev.sort.doris

import com.intellij.openapi.diagnostic.Logger

/**
 * The multi-catalog model switch (RESEARCH-catalog-introspection.md, path (a)).
 *
 * ## What the flag does
 *
 * When **ON (the default since 0.3.0, M10)** the plugin uses the SQL-Server (`Ms*`) multi-database
 * meta-model, which exposes the `ROOT -> DATABASE -> SCHEMA -> TABLE` shape Doris external catalogs
 * need, and drives [dev.sort.doris.catalog.DorisIntrospector], which enumerates catalogs via
 * `SHOW CATALOGS` and each catalog's databases/tables over the MySQL protocol.
 *
 * When **OFF (the escape hatch: `-Ddoris.catalogs.experimental=false`)** the plugin behaves
 * bit-for-bit as the pre-catalog releases: [DorisModelFacade] returns the single-database MySQL
 * meta-model, the introspector is `MysqlBaseIntrospector` (plus the M2 default-scope fix), and
 * every editor helper resolves to its MySQL implementation. Every `dbms="DORIS"` extension is
 * written to **delegate to the MySQL implementation when this flag is off**.
 *
 * Parsing rule (M10): unset -> ON; any value other than (case-insensitive) `"false"` -> ON;
 * exactly `"false"` -> OFF. Toggling requires an IDE restart; the model-shape migration listener
 * ([dev.sort.doris.catalog.DorisModelMigrationListener]) silently clears mismatched persisted
 * models on the next start.
 *
 * ## Why one central, read-once flag
 *
 * Extension points in `plugin.xml` are static: a `dbms="DORIS"` bean is instantiated regardless of
 * the flag. The flag is therefore read **once per JVM session** (cached on first class load) so
 * every extension makes a single, consistent routing decision — there is no way for one extension
 * to see the flag on and another to see it off within a session, and no per-call cost. Tests may
 * pin a mode via [setForTests]; production code must never call it.
 */
object DorisCatalogs {

    /**
     * System property controlling the multi-catalog model. Default: **enabled**; set
     * `-Ddoris.catalogs.experimental=false` to fall back to the flat single-database model.
     */
    const val PROPERTY: String = "doris.catalogs.experimental"

    /** M10 parsing rule as a pure function (unit-tested): only an explicit `"false"` disables. */
    fun isEnabledValue(raw: String?): Boolean = !"false".equals(raw, ignoreCase = true)

    /**
     * The flag value every `dbms="DORIS"` extension branches on.
     *
     * Read **from the system property on each access** (M10). In production the property is a VM
     * option, set before startup and never changed, so the value is effectively session-constant —
     * the M1 consistency guarantee holds by usage, and the per-read property lookup is trivial.
     * The dynamic read exists for testability: the platform test fixture loads the plugin classes
     * in the *plugin classloader* while test code links against the test-classpath copy — two
     * separate statics — so only a JVM-global channel (the system property) lets a test pin the
     * mode for both. Tests use `System.setProperty(PROPERTY, "false")` in setUp and
     * `System.clearProperty(PROPERTY)` in tearDown (the `DorisReplayPocTest` pattern).
     */
    val enabled: Boolean
        get() = isEnabledValue(System.getProperty(PROPERTY))

    /** All experimental-path log lines carry this prefix so a runtime tester can `grep idea.log`. */
    const val LOG_PREFIX: String = "DorisCatalogs:"

    private val log = Logger.getInstance(DorisCatalogs::class.java)

    fun info(message: String) = log.info("$LOG_PREFIX $message")

    fun warn(message: String, t: Throwable? = null) =
        if (t != null) log.warn("$LOG_PREFIX $message", t) else log.warn("$LOG_PREFIX $message")
}
