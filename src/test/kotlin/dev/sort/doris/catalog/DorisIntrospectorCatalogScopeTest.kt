package dev.sort.doris.catalog

import com.intellij.database.layoutedQueries.DBCommandRunner
import com.intellij.database.layoutedQueries.DBQueryRunner
import com.intellij.database.layoutedQueries.DBScriptRunner
import com.intellij.database.layoutedQueries.DBTransaction
import com.intellij.database.remote.jdba.core.ResultLayout
import com.intellij.database.remote.jdba.sql.SqlCommand
import com.intellij.database.remote.jdba.sql.SqlQuery
import com.intellij.database.remote.jdba.sql.SqlScript
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Behavioural coverage for the R1 catalog-restore contract (REVIEW-kimi3.md), the fix originally
 * shipped on `fix/kimi-R1-switch` with only a query-text assertion. These drive
 * [runCatalogScopedOrFallback] through a recording fake [DBTransaction] and assert the *sequence of
 * SQL* issued to the (pooled) connection — the thing that actually matters: the fallback must
 * `SWITCH` into the target catalog and always `SWITCH` back, even when the fallback query throws,
 * and it must never touch session state at all when the primary (qualified) path succeeds.
 *
 * The functions under test are top-level `internal` (not introspector instance methods) precisely so
 * they can be exercised without standing up the platform introspector; see the note in
 * `DorisIntrospector.kt`.
 */
class DorisIntrospectorCatalogScopeTest : BasePlatformTestCase() {

    private fun switch(catalog: String) = DorisCatalogQueries.switchCatalog(catalog)

    /** Primary succeeds → the SWITCH fallback never runs, so session state is left untouched. */
    fun testPrimarySuccessNeverSwitches() {
        val tx = RecordingTransaction(currentCatalog = { arrayOf("orig_cat") })
        val result = runCatalogScopedOrFallback(
            tx, "target_cat", "SHOW DATABASES",
            primary = { "PRIMARY" },
            fallback = { fail("fallback must not run when the primary query succeeds"); "" },
        )
        assertEquals("PRIMARY", result)
        assertTrue("no SWITCH should be issued on the success path", tx.commands.isEmpty())
        assertTrue("no probe should be issued on the success path", tx.events.isEmpty())
    }

    /** Fallback path: read current catalog, SWITCH in, run fallback, SWITCH back — in that order. */
    fun testFallbackSwitchesInAndRestores() {
        val tx = RecordingTransaction(currentCatalog = { arrayOf("orig_cat") })
        val result = runCatalogScopedOrFallback(
            tx, "target_cat", "SHOW DATABASES",
            primary = { throw RuntimeException("qualified form unsupported (older Doris)") },
            fallback = { tx.events.add("FALLBACK"); "OK" },
        )
        assertEquals("OK", result)
        // The two session-state mutations, in order: into the target, then back to the original.
        assertEquals(listOf(switch("target_cat"), switch("orig_cat")), tx.commands)
        // Full ordering: probe → switch-in → fallback → switch-back.
        assertEquals(
            listOf("QUERY:${DorisCatalogQueries.SELECT_CURRENT_CATALOG}", switch("target_cat"), "FALLBACK", switch("orig_cat")),
            tx.events,
        )
    }

    /** The restore runs from the `finally`, so a throwing fallback still leaves the catalog restored. */
    fun testRestoreRunsEvenWhenFallbackThrows() {
        val tx = RecordingTransaction(currentCatalog = { arrayOf("orig_cat") })
        var thrown: RuntimeException? = null
        try {
            runCatalogScopedOrFallback<String>(
                tx, "target_cat", "SHOW DATABASES",
                primary = { throw RuntimeException("qualified form unsupported") },
                fallback = { throw RuntimeException("fallback boom") },
            )
            fail("expected the fallback's exception to propagate")
        } catch (e: RuntimeException) {
            thrown = e
        }
        assertEquals("fallback boom", thrown?.message)
        // Restore still happened despite the fallback throwing.
        assertEquals(listOf(switch("target_cat"), switch("orig_cat")), tx.commands)
    }

    /** Probe fails → the original catalog is unknown → restore targets the connect-time default. */
    fun testRestoreTargetsInternalWhenProbeFails() {
        val tx = RecordingTransaction(currentCatalog = { throw RuntimeException("current_catalog() unsupported") })
        val result = runCatalogScopedOrFallback(
            tx, "target_cat", "SHOW DATABASES",
            primary = { throw RuntimeException("qualified form unsupported") },
            fallback = { "OK" },
        )
        assertEquals("OK", result)
        assertEquals(listOf(switch("target_cat"), switch(DorisCatalogScopes.INTERNAL_CATALOG)), tx.commands)
    }

    /** A failing restore is swallowed — it must never mask the fallback's own (successful) result. */
    fun testRestoreFailureDoesNotMaskFallbackResult() {
        // Fail only the restore SWITCH (the one that targets the original catalog).
        val tx = RecordingTransaction(currentCatalog = { arrayOf("orig_cat") }, failCommandSubstring = "orig_cat")
        val result = runCatalogScopedOrFallback(
            tx, "target_cat", "SHOW DATABASES",
            primary = { throw RuntimeException("qualified form unsupported") },
            fallback = { "OK" },
        )
        assertEquals("OK", result)
    }

    /**
     * Minimal recording [DBTransaction]: captures every `command(String)` (the SWITCHes) and the
     * one `query(SqlQuery)` (the current-catalog probe) in order. [currentCatalog] supplies the
     * probe result (or throws to simulate an old server without `current_catalog()`);
     * [failCommandSubstring] makes any command whose SQL contains it throw, to simulate a failing
     * restore. Only the two overloads the code under test uses are functional.
     */
    private class RecordingTransaction(
        private val currentCatalog: () -> Array<String>,
        private val failCommandSubstring: String? = null,
    ) : DBTransaction {
        /** SWITCH commands only, in order. */
        val commands = mutableListOf<String>()
        /** Every event (probe queries + SWITCH commands + test-injected markers), in order. */
        val events = mutableListOf<String>()

        override fun command(command: String): DBCommandRunner = object : DBCommandRunner {
            override fun withParams(vararg params: Any?): DBCommandRunner = this
            override fun run(): DBCommandRunner {
                commands.add(command)
                events.add(command)
                if (failCommandSubstring != null && command.contains(failCommandSubstring)) {
                    throw RuntimeException("injected command failure: $command")
                }
                return this
            }
            override fun close() {}
        }

        @Suppress("UNCHECKED_CAST")
        override fun <S : Any?> query(query: SqlQuery<S>): DBQueryRunner<S> = object : DBQueryRunner<S> {
            override fun withParams(vararg params: Any?): DBQueryRunner<S> = this
            override fun packBy(packSize: Int): DBQueryRunner<S> = this
            override fun run(): S {
                events.add("QUERY:${query.sourceText}")
                return currentCatalog() as S
            }
            override fun start() {}
            override fun nextPack(): S = unsupported()
            override fun close() {}
            override fun <I : Any?> getSpecificService(serviceClass: Class<I>, serviceName: String): I = unsupported()
        }

        override fun command(command: SqlCommand): DBCommandRunner = unsupported()
        override fun <S : Any?> query(queryText: String, layout: ResultLayout<S>): DBQueryRunner<S> = unsupported()
        override fun script(script: SqlScript): DBScriptRunner = unsupported()

        private fun unsupported(): Nothing =
            throw UnsupportedOperationException("not exercised by the R1 catalog-scope tests")
    }
}
