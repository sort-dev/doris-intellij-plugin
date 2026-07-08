package dev.sort.doris

import com.intellij.openapi.diagnostic.Logger

/**
 * Gate 1 / Milestone 1 opt-in switch for the experimental multi-catalog model
 * (RESEARCH-catalog-introspection.md, path (a)).
 *
 * ## What the flag does
 *
 * When **OFF (default)** the plugin behaves bit-for-bit as shipped: [DorisModelFacade] returns the
 * single-database MySQL meta-model, the introspector is `MysqlBaseIntrospector`, and every editor
 * helper resolves to its MySQL implementation (exactly today's `extensionFallback DORIS -> MYSQL`
 * behaviour). Every `dbms="DORIS"` extension we register for M1 is written to **delegate to the
 * MySQL implementation when this flag is off**, so registering them changes nothing flag-off.
 *
 * When **ON** the plugin swaps to the SQL-Server (`Ms*`) multi-database meta-model, which exposes
 * the `ROOT -> DATABASE -> SCHEMA -> TABLE` shape Doris external catalogs need, and drives a bespoke
 * [dev.sort.doris.catalog.DorisIntrospector] that enumerates catalogs via `SHOW CATALOGS` and each
 * catalog's databases/tables over the MySQL protocol.
 *
 * ## Why one central, read-once flag
 *
 * Extension points in `plugin.xml` are static: a `dbms="DORIS"` bean is instantiated regardless of
 * the flag. The flag is therefore read **once per JVM session** (a `val`, evaluated on first class
 * load) so every extension makes a single, consistent routing decision at construction time — there
 * is no way for one extension to see the flag on and another to see it off within a session, and no
 * per-call cost. Set it as a VM option: `-Ddoris.catalogs.experimental=true`.
 */
object DorisCatalogs {

    /** System property that opts in to the experimental multi-catalog model. Default: `false`. */
    const val PROPERTY: String = "doris.catalogs.experimental"

    /**
     * Read once, at first access, and cached for the JVM session. All `dbms="DORIS"` extensions
     * branch on this single value, guaranteeing a consistent flag-off / flag-on decision everywhere.
     */
    @JvmField
    val enabled: Boolean = java.lang.Boolean.getBoolean(PROPERTY)

    /** All experimental-path log lines carry this prefix so a runtime tester can `grep idea.log`. */
    const val LOG_PREFIX: String = "DorisCatalogs:"

    private val log = Logger.getInstance(DorisCatalogs::class.java)

    fun info(message: String) = log.info("$LOG_PREFIX $message")

    fun warn(message: String, t: Throwable? = null) =
        if (t != null) log.warn("$LOG_PREFIX $message", t) else log.warn("$LOG_PREFIX $message")
}
