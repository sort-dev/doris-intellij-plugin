package dev.sort.doris.cancel

import com.intellij.database.actions.CancelRunningStatementsAction
import com.intellij.database.console.JdbcEngine
import com.intellij.database.console.client.SessionClient
import com.intellij.database.console.session.DatabaseSession
import com.intellij.database.dataSource.DatabaseConnection
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.connection.ConnectionRequestor
import com.intellij.database.datagrid.DataRequest
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.helpers.JdbcNativeUtil
import com.intellij.database.run.audit.CancelProgressAuditor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.Messages
import com.intellij.util.TimeoutUtil

/**
 * Replaces `Console.Jdbc.Cancel` (`overrides="true"` in plugin.xml) — cancel-time half of the
 * clean Doris cancel (RESEARCH-query-cancel.md; see [DorisCancel] for the design and the live
 * 4.1.2 verification table).
 *
 * For non-Doris sessions (or with `-Ddoris.cancel.experimental=false`, or on the second press
 * where the stock action escalates to deactivate-with-confirmation) this is byte-for-byte the
 * stock [CancelRunningStatementsAction] — `performAction` delegates straight to `super`.
 *
 * ## Ordering — ours first, stock only as a genuine fallback (P0b)
 *
 * The first cut ran stock **first**, ours after. Behind a k8s LB stock's `KILL QUERY <conn-id>`
 * hits the wrong FE and *fails*, so the stock "Cancelling of Running Statements Failed. Deactivate
 * the Data Source?" dialog popped even though our kill landed ~0.5s later and killed the query
 * (confirmed live on the user's prod multi-FE cluster: the red *"cancel query by user from
 * 10.244.7.112"* bar is our helper connection's cross-FE kill). The dialog was pure noise in the
 * success case.
 *
 * Now, for a Doris session with any tagged connection:
 * - this session's guid(s) are resolved **first, synchronously on the pooled thread** — the live
 *   request-owner match (below) only works while the statement is still executing, so it must
 *   happen before anything client-side unblocks the console;
 * - a **bare client-side cancel** ([DataRequest.Cancel] processed through the session's own
 *   [DataRequest.OwnerEx.getDataProducer]) is fired next. This is the stock cancel machinery —
 *   pending-request queue dropped, engine state flipped to cancelled, driver-side
 *   `Statement.cancel()` attempted, *"Cancelling..."* printed to the console — but **without**
 *   stock's failure dialog: the "Deactivate the Data Source?" dialog is not in the engine, it is an
 *   `onError` handler that stock's `createCancelRequest` attaches to the request promise (DB-261
 *   bytecode), and a request we mint ourselves simply carries no such handler. The earlier claim
 *   that the client cancel and the dialog "cannot be decoupled" looked only at reusing
 *   `createCancelRequest`; constructing the public `DataRequest.Cancel` directly decouples them.
 *   The queue drop is the P1 fix for multi-statement scripts: killing one statement server-side
 *   otherwise just lets the console march on to the next INSERT (whack-a-mole, observed live
 *   2026-07-22) — see `cancelPendingClientWork`.
 * - our server-side kill then proceeds as before and the stock action stays suppressed in the
 *   success path. Our `KILL QUERY` makes the running statement return *"cancel query by user"*,
 *   which unblocks anything the driver-side cancel missed (wrong-FE no-op behind the LB).
 * - the stock cancel runs **only when our server path definitively did nothing**
 *   ([DorisCancel.KillOutcome.NOTHING]: no guid resolvable, no running tagged row, an ambiguous
 *   multi-match, or the helper failed) *and* the session is still busy and not already cancelled
 *   by the bare client-side cancel; then its dialog is legitimate. That decision is only known
 *   after the async kill, so the stock fallback is posted back to the EDT.
 * - if we never tagged this data source at all (connected before the plugin/flag), there is no
 *   hope for our path, so we delegate to stock **immediately and synchronously** — no dispatch.
 * - a repeat Stop press while our kill is in flight is de-duped ([DorisCancel.beginCancel]) so it
 *   does not prematurely escalate to stock's deactivate dialog. The in-flight check runs *before*
 *   the `isCancelled` escalation check: the bare client-side cancel flips the session's cancelled
 *   state almost immediately, and a mash-press during the (observed 6-11s) kill round-trip must
 *   not fall through to stock's deactivate escalation.
 * - a Stop press on a session that is *already* cancelled with no Doris kill in flight is the
 *   genuine second press: pure stock, which escalates to deactivate-with-confirmation — unchanged
 *   stock semantics.
 *
 * ## Resolving *which* query to kill
 *
 * Cancel fires while the console's statement is executing. The guid for *this* console lives on
 * its connection, but `session.messageBus.dataProducer` is a one-method `DataProducer` JDK proxy,
 * so the connection (and its guid) cannot be reflected off it — the original cut tried and always
 * failed, falling through to the data-source-wide net below (which then refused whenever a second
 * query, e.g. a background copy in another console, was running on the same data source). Instead
 * we match **live at cancel time**: a console connection's requestor is its `JdbcEngine` (a public
 * class — it *is* the `DataProducer` behind that proxy), and while the console is executing, the
 * engine's `getRequestContextIfAny()` exposes the active request, whose public `owner` is the
 * session client that submitted it. Cancel fires exactly while the target session is mid-query, so
 * enumerating `DatabaseConnectionManager.getActiveConnections()` and keeping the connection(s)
 * whose live request-owner belongs to this session pins down *this console's* connection — and our
 * connect-time weak map turns it into the guid, with no remote call. Nothing is registered
 * per-session ahead of time, so reconnects can never leave the mapping stale. (Session-identity
 * keys that do NOT work, learned the hard way: `ConsoleRunConfiguration` and the audit handles are
 * shared/recreated across consoles of a data source; `JdbcEngine` holds no session field to
 * reflect.)
 *
 * The guid is then used only as the **`Info`-marker to match in `SHOW FULL PROCESSLIST`**, from
 * which we read the running query's real `QueryId` and `KILL QUERY "<QueryId>"`. We do *not* kill
 * by trace id directly: `KILL QUERY "<trace-id>"` returns success but is a silent no-op on Doris
 * 4.1.2 (the query keeps running), whereas the QueryId kill is live-proven.
 *
 * **Data-source safety net (last resort):** if no console guid resolves, we match against every
 * guid minted for the *data source* instead. More than one running match -> refuse (avoid the
 * wrong-kill this feature exists to prevent) and leave it to stock. With per-console resolution
 * above now working, this net is reached only when the config registry and active-connection
 * lookup both come up empty.
 *
 * Every resolution step logs under the `DorisCancel:` prefix so a real-IDE press leaves a
 * definitive trail (the flow is not headless-drivable).
 */
class DorisCancelRunningStatementsAction : CancelRunningStatementsAction() {

    private companion object {
        /** How long to wait after an accepted `KILL QUERY` before re-scanning to confirm it took. */
        const val VERIFY_DELAY_MS = 1500L
    }

    /** A running statement the FE would not actually kill (see [DorisCancel.KillOutcome.STILL_RUNNING]). */
    private data class StillRunning(val queryId: String, val kind: String?)

    /**
     * Result of our server-side cancel attempt: the [outcome], the [stillRunning] detail when the
     * kill was a no-op, and this console's [connections] (attached late, for the detach step).
     */
    private data class CancelResult(
        val outcome: DorisCancel.KillOutcome,
        val stillRunning: StillRunning? = null,
        val connections: List<DatabaseConnection> = emptyList(),
    ) {
        fun withConnections(c: List<DatabaseConnection>): CancelResult = copy(connections = c)
    }

    override fun performAction(e: AnActionEvent, session: DatabaseSession) {
        if (!DorisCancel.shouldHandle(session.connectionPoint.dbms)) {
            super.performAction(e, session)
            return
        }

        // Dedupe FIRST: our bare client-side cancel flips the session's cancelled state almost
        // immediately, so a mash-press during the kill round-trip would otherwise hit the
        // isCancelled branch below and prematurely escalate to stock's deactivate dialog.
        if (DorisCancel.isCancelInFlight(session.id)) {
            DorisCancel.info(
                "session '${session.title}': a Doris cancel is already in flight; ignoring repeat press",
            )
            return
        }

        if (session.isCancelled) {
            // Genuine second press after a completed cancel cycle: stock's deactivate escalation.
            super.performAction(e, session)
            return
        }

        val dataSourceId = dataSourceId(session)

        // No hope for our path (never tagged this data source: connected before the plugin/flag)?
        // Delegate to stock immediately and synchronously — its dialog is legitimate.
        if (!DorisCancel.hasAnyGuidForDataSource(dataSourceId)) {
            DorisCancel.info(
                "session '${session.title}' (ds=$dataSourceId): no trace id ever minted for this " +
                    "data source (connected before the plugin/flag?); delegating to stock",
            )
            super.performAction(e, session)
            return
        }

        // CAS backstop for the isCancelInFlight check above (racing presses).
        if (!DorisCancel.beginCancel(session.id)) {
            DorisCancel.info(
                "session '${session.title}': a Doris cancel is already in flight; ignoring repeat press",
            )
            return
        }

        DorisCancel.info(
            "session '${session.title}' (ds=$dataSourceId): our cancel dispatched, stock suppressed " +
                "unless our path finds nothing (success path)",
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                // Resolve BEFORE the client-side cancel: the live request-owner match only works
                // while the console's statement is still executing.
                val resolved = resolveConsole(session)
                cancelPendingClientWork(session)
                killOnServer(session, dataSourceId, resolved.guids)
                    .withConnections(resolved.connections)
            } catch (t: Throwable) {
                DorisCancel.warn("session '${session.title}': kill dispatch threw: ${t.message}")
                CancelResult(DorisCancel.KillOutcome.NOTHING)
            } finally {
                DorisCancel.endCancel(session.id)
            }
            when (result.outcome) {
                DorisCancel.KillOutcome.KILLED ->
                    DorisCancel.info("session '${session.title}': kill confirmed; console will unblock")

                DorisCancel.KillOutcome.STILL_RUNNING ->
                    ApplicationManager.getApplication().invokeLater {
                        offerDetach(session, result)
                    }

                DorisCancel.KillOutcome.NOTHING ->
                    ApplicationManager.getApplication().invokeLater {
                        if (!session.isCancelled && !session.isIdle) {
                            DorisCancel.info(
                                "session '${session.title}': our path found nothing to kill and the " +
                                    "session is still busy; delegating to stock as fallback",
                            )
                            runStockCancel(e, session)
                        } else {
                            DorisCancel.info(
                                "session '${session.title}': our server path found nothing to kill, but " +
                                    "the client-side cancel settled the session; no stock fallback needed",
                            )
                        }
                    }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Uncancellable statement -> offer the user a detach so they get their console back
    // ---------------------------------------------------------------------------------------

    /**
     * The FE accepted our `KILL QUERY` but the statement is still running (the upstream Nereids
     * INSERT/CTAS cancel bug — see [DorisCancel.KillOutcome.STILL_RUNNING]). Nothing we do stops it,
     * so tell the user plainly, hand them the `QueryId` to track it, and offer to detach *this
     * console* (leaving their other consoles on the same data source untouched) so they can keep
     * working while the statement finishes on the server. EDT-only (shows a modal dialog).
     */
    private fun offerDetach(session: DatabaseSession, result: CancelResult) {
        val still = result.stillRunning ?: return
        val kind = still.kind ?: "statement"
        val kindPhrase = if (still.kind != null) "this ${still.kind}" else "this statement"
        DorisCancel.warn(
            "session '${session.title}': KILL accepted but $kind still running as QueryId " +
                "${still.queryId}; offering detach (upstream Nereids INSERT/CTAS cancel bug)",
        )
        val message = buildString {
            append("Doris could not cancel $kindPhrase.\n\n")
            append("This version of Doris cannot stop a running INSERT / CTAS once it has started — ")
            append("the server accepted the cancel but the statement keeps running and will finish on its own.\n\n")
            append("It is running on the server as:\n    QueryId ${still.queryId}\n")
            append("Track it with  SHOW PROCESSLIST  or  SHOW LOAD.\n\n")
            append("Detach this console and reconnect so you can keep working? ")
            append("(Your other consoles on this data source are unaffected.)")
        }
        val choice = Messages.showYesNoDialog(
            session.project,
            message,
            "Doris Can't Cancel This ${if (still.kind != null) still.kind else "Statement"}",
            "Detach & Reconnect",
            "Keep Waiting",
            Messages.getWarningIcon(),
        )
        if (choice == Messages.YES) {
            detachThisConsole(session, result.connections)
        } else {
            DorisCancel.info("session '${session.title}': user chose to keep waiting on QueryId ${still.queryId}")
        }
    }

    /**
     * Give the console back to the user by dropping *this session's* connection.
     *
     * Default (graceful): process a [DataRequest.Disconnect] through the console's own data producer
     * — exactly what `SessionsUtil.disconnect` does — which tells the engine to disconnect just this
     * console; the console reconnects on its next run. If the bake shows the graceful disconnect
     * cannot force through while the console thread is blocked in the master-forward RPC, flip
     * `-Ddoris.cancel.detach.physical=true`: we then close this console's `RemoteConnection`
     * directly, whose socket close interrupts the blocked read. Both leave every other console alone.
     *
     * Either way the server statement keeps running (we cannot stop it — that is the whole reason
     * we are here); detaching only frees the client.
     */
    private fun detachThisConsole(session: DatabaseSession, connections: List<DatabaseConnection>) {
        if (DorisCancel.detachPhysical) {
            forcePhysicalClose(session, connections)
            return
        }
        try {
            session.messageBus.dataProducer.processRequest(DataRequest.Disconnect(session))
            DorisCancel.info("session '${session.title}': graceful disconnect requested (console detached)")
        } catch (t: Throwable) {
            DorisCancel.warn(
                "session '${session.title}': graceful disconnect failed (${t.message}); " +
                    "falling back to physical close",
            )
            forcePhysicalClose(session, connections)
        }
    }

    /** The hammer: close this console's remote connection(s) so a blocked socket read is interrupted. */
    private fun forcePhysicalClose(session: DatabaseSession, connections: List<DatabaseConnection>) {
        if (connections.isEmpty()) {
            DorisCancel.warn("session '${session.title}': no resolved connection to close physically")
            return
        }
        for (conn in connections) {
            try {
                val remote = conn.remoteConnection
                JdbcNativeUtil.performRemote { remote.close() }
                DorisCancel.info("session '${session.title}': physically closed a console connection")
            } catch (t: Throwable) {
                DorisCancel.warn("session '${session.title}': physical close failed: ${t.message}")
            }
        }
    }

    /**
     * Fire the stock cancel *machinery* without the stock cancel *dialog*: process a bare
     * [DataRequest.Cancel] through the session's own data producer. In the engine
     * (`AbstractEngine.visitCancel` -> `JdbcEngine.cancelPendingRequests`, DB-261 bytecode) this
     * drops every queued request (`myCancelled` set-then-reset marker: requests already in the
     * queue are skipped as they dequeue, the session stays usable afterwards), flips the engine
     * state to CANCELED (prints *"Cancelling..."*, marks the session cancelled), and attempts the
     * driver-side `Statement.cancel()` on the running statement. The queue drop is what fixes
     * multi-statement scripts: without it, our server-side kill errors the *current* statement and
     * the console simply runs the next one (live whack-a-mole, 2026-07-22 idea.log).
     *
     * The "Cancelling of Running Statements Failed. Deactivate the Data Source?" dialog is NOT
     * part of this machinery — stock attaches it as an `onError` handler on the request promise in
     * `createCancelRequest`; our request carries no handler, so a driver-side cancel failure
     * (wrong-FE `KILL <conn-id>` behind the LB) stays silent and our trace-id kill cleans up
     * server-side instead. `processRequest` offloads to a pool executor internally
     * (`performRequestDirect`), so this returns quickly wherever it is called from; stock calls the
     * same seam straight from the EDT.
     */
    private fun cancelPendingClientWork(session: DatabaseSession) {
        try {
            val request = DataRequest.Cancel(session)
            request.putUserData(CancelProgressAuditor.SHOW_CANCEL_PROGRESS, true)
            session.messageBus.dataProducer.processRequest(request)
            DorisCancel.info(
                "session '${session.title}': bare client-side cancel fired (queue dropped, no dialog wiring)",
            )
        } catch (t: Throwable) {
            // Best-effort: the server-side kill still lands; worst case is the old per-statement
            // whack-a-mole for scripts, never a broken cancel.
            DorisCancel.warn("session '${session.title}': bare client-side cancel failed: ${t.message}")
        }
    }

    /** Bridge so the stock (super) cancel can be invoked from an async continuation. */
    private fun runStockCancel(e: AnActionEvent, session: DatabaseSession) {
        super.performAction(e, session)
    }

    private fun dataSourceId(session: DatabaseSession): String? =
        try {
            session.connectionPoint.dataSource.uniqueId
        } catch (t: Throwable) {
            DorisCancel.warn("could not read data source id: ${t.message}")
            null
        }

    // ---------------------------------------------------------------------------------------
    // Session resolution: this console's guid(s) via the public active-connection registry
    // ---------------------------------------------------------------------------------------

    /**
     * This console's live connection(s) and their guid(s), resolved together in one pass.
     *
     * @property connections the [DatabaseConnection] object(s) backing THIS session — kept so the
     *   detach step ([detachThisConsole]) can close them physically if the graceful disconnect
     *   cannot force through the blocked console thread.
     * @property guids the trace-id marker(s) on those connections, for the processlist kill match.
     */
    private data class ResolvedConsole(
        val connections: List<DatabaseConnection>,
        val guids: Set<String>,
    )

    /**
     * Resolve THIS session's connection(s) and guid(s).
     *
     * Replaces the original proxy reflection (see the class KDoc): we enumerate the platform's
     * public active-connection list and keep only the connection(s) whose live request-owner is this
     * session — each console owns a distinct run-config instance, threaded by identity into the
     * connection it opens, so config identity isolates this console from other consoles on the same
     * data source. The guid is then read from our connect-time registry (or, on a miss, straight off
     * the driver via `getClientInfo` — a safe direct call now that we hold the real connection, not
     * the proxy). Usually one connection/guid; a session that reconnected can carry more than one
     * (stale guids simply never match a *running* processlist row).
     */
    private fun resolveConsole(session: DatabaseSession): ResolvedConsole {
        val id = session.id
        val clients = try {
            session.clients
        } catch (t: Throwable) {
            emptyArray()
        }
        val active = try {
            DatabaseConnectionManager.getInstance().activeConnections
        } catch (t: Throwable) {
            DorisCancel.warn("resolveConsole: activeConnections unavailable: ${t.message}")
            return ResolvedConsole(emptyList(), emptySet())
        }
        // Live match: a console connection's requestor is its JdbcEngine, and WHILE the console is
        // executing, the engine exposes the active request's context, whose request carries its
        // OWNER — the session client that submitted it. Cancel fires exactly while the target
        // session is mid-query, so its engine has a context and the owner names the session. All
        // public API, read fresh at every press — nothing registered ahead of time, so reconnects
        // can never leave this stale.
        val mine = active.filter { conn ->
            runCatching {
                val owner = (conn.requestor as? JdbcEngine)?.requestContextIfAny?.request?.owner
                    ?: return@runCatching false
                val ownerSessionId = ((owner as? SessionClient<*>)?.session as? DatabaseSession)?.id
                ownerSessionId == id || clients.any { it === owner }
            }.getOrDefault(false)
        }
        val guids = mine.mapNotNullTo(LinkedHashSet()) { guidFromConnection(it) }
        DorisCancel.info(
            "resolveConsole: '${session.title}' id=$id -> ${mine.size}/${active.size} live " +
                "request-owner match(es), ${guids.size} guid(s)",
        )
        return ResolvedConsole(mine, guids)
    }

    /**
     * Read the guid from a resolved connection: connect-time weak map first (instant, in-process),
     * driver client-info second. Order matters: `getClientInfo` is a remote call that can stall for
     * seconds behind the busy connection's running statement (observed 8s live), while the weak map
     * — populated at mint for every tagged connection — answers immediately.
     */
    private fun guidFromConnection(connection: DatabaseConnection): String? {
        val remote: RemoteConnection = try {
            connection.remoteConnection
        } catch (t: Throwable) {
            DorisCancel.warn("resolve: remoteConnection unavailable: ${t.message}")
            return null
        }
        val fromMap = DorisCancel.guidForConnection(remote)
        if (DorisCancel.isValidGuid(fromMap)) {
            DorisCancel.info("resolve: guid from connect-time weak map -> $fromMap")
            return fromMap
        }
        val fromDriver = try {
            JdbcNativeUtil.computeRemote { remote.getClientInfo(DorisCancel.CLIENT_INFO_KEY) }
        } catch (t: Throwable) {
            DorisCancel.info("resolve: getClientInfo(${DorisCancel.CLIENT_INFO_KEY}) failed: ${t.message}")
            null
        }
        if (DorisCancel.isValidGuid(fromDriver)) {
            DorisCancel.info("resolve: guid from driver client-info -> $fromDriver")
            return fromDriver
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Server-side kill (pooled thread; one short-lived helper connection)
    // ---------------------------------------------------------------------------------------

    /**
     * Resolve this session's guid(s) and kill, on a pooled thread through one short-lived helper
     * connection. We always kill via the **processlist -> real `QueryId`** path: it is the only one
     * live-proven on the user's cluster. (`KILL QUERY "<trace-id>"` returns success but is a silent
     * no-op on Doris 4.1.2 — the running query keeps going — so the trace id is used only as the
     * `Info`-marker we match in the processlist, never as a direct kill argument.)
     *
     * Guid scoping, best -> last resort:
     *  - **this console's** guids ([scoped], resolved by the caller *before* the client-side
     *    cancel unblocks the console — the live request-owner match needs the statement to still
     *    be executing) — kills only this console's running query, so a background copy in another
     *    console on the same data source is ignored;
     *  - if none resolve, **the data source's** guids — the old, ambiguity-prone net, which refuses
     *    on more than one running match and leaves it to stock.
     *
     * Returns [DorisCancel.KillOutcome.KILLED] when the server accepted a kill and a re-scan
     * confirms the row is gone; [DorisCancel.KillOutcome.STILL_RUNNING] when the kill was accepted
     * but the same `QueryId` is still running a moment later (the uncancellable INSERT/CTAS case);
     * and [DorisCancel.KillOutcome.NOTHING] when we did nothing — the caller then runs the stock
     * fallback.
     */
    private fun killOnServer(
        session: DatabaseSession,
        dataSourceId: String?,
        scoped: Set<String>,
    ): CancelResult {
        val guids = if (scoped.isNotEmpty()) scoped else DorisCancel.guidsForDataSource(dataSourceId)
        val context =
            if (scoped.isNotEmpty()) "session '${session.title}'"
            else "data-source safety net for '${session.title}' (ds=$dataSourceId)"
        if (guids.isEmpty()) {
            DorisCancel.info("$context: no guids to match; nothing to kill (stock will handle it)")
            return CancelResult(DorisCancel.KillOutcome.NOTHING)
        }
        return try {
            val ref = DatabaseConnectionManager.getInstance()
                .build(session.project, session.connectionPoint)
                .setRequestor(ConnectionRequestor.Anonymous())
                .createBlockingNonCancellable()
                ?: throw IllegalStateException("no helper connection")
            ref.use { r ->
                val helper = r.get()
                DorisCancel.info("$context: matching ${guids.size} guid(s) in the processlist")
                widenProcesslist(helper)
                val candidates = collectRunningCandidates(helper, guids)
                killSingleCandidate(helper, candidates, context, guids)
            }
        } catch (t: Throwable) {
            DorisCancel.warn("helper connection for kill failed: ${t.message}")
            CancelResult(DorisCancel.KillOutcome.NOTHING)
        }
    }

    /** Kill exactly one candidate; refuse (log) on zero or ambiguous multiple. */
    private fun killSingleCandidate(
        helper: DatabaseConnection,
        candidates: List<Candidate>,
        context: String,
        guids: Set<String>,
    ): CancelResult {
        when {
            candidates.isEmpty() -> {
                DorisCancel.info("$context: no running tagged query found; nothing to kill (stock will handle it)")
                return CancelResult(DorisCancel.KillOutcome.NOTHING)
            }
            candidates.size > 1 -> {
                val ids = candidates.joinToString { "${it.queryId}(${it.guid})" }
                DorisCancel.warn(
                    "$context: ${candidates.size} running tagged queries [$ids]; ambiguous, " +
                        "refusing to kill to avoid a wrong-kill; leaving it to stock",
                )
                return CancelResult(DorisCancel.KillOutcome.NOTHING)
            }
            else -> {
                val c = candidates.single()
                if (!DorisCancel.isValidQueryId(c.queryId)) {
                    DorisCancel.warn("$context: refusing to kill malformed query id '${c.queryId}'")
                    return CancelResult(DorisCancel.KillOutcome.NOTHING)
                }
                DorisCancel.info("$context: issuing ${DorisCancel.sqlKillQueryByQueryId(c.queryId)} (guid=${c.guid})")
                return try {
                    execute(helper, DorisCancel.sqlKillQueryByQueryId(c.queryId))
                    DorisCancel.info("$context: KILL QUERY accepted for ${c.queryId}; verifying it actually stopped")
                    verifyKill(helper, c, context, guids)
                } catch (t: Throwable) {
                    if (DorisCancel.isUnknownQueryId(t)) {
                        DorisCancel.info("$context: query id ${c.queryId} already gone")
                        CancelResult(DorisCancel.KillOutcome.KILLED)
                    } else {
                        DorisCancel.warn("$context: KILL QUERY \"${c.queryId}\" failed: ${t.message}")
                        CancelResult(DorisCancel.KillOutcome.NOTHING)
                    }
                }
            }
        }
    }

    /**
     * Verify-after-kill: `KILL QUERY` returns OK even when the FE cannot actually stop the statement
     * (the Nereids INSERT/CTAS bug — the kill only reaches the query coordinator, never the insert
     * coordinator). So after the kill we wait briefly and re-scan: if the same `QueryId` is gone the
     * kill was real ([DorisCancel.KillOutcome.KILLED]); if it is still running the kill was a
     * no-op ([DorisCancel.KillOutcome.STILL_RUNNING]) and the caller offers the user a detach. This
     * is behavioural — it makes no assumption about which statement kinds are broken, so it self-heals
     * the day the FE is patched (a working kill simply verifies as gone).
     */
    private fun verifyKill(
        helper: DatabaseConnection,
        killed: Candidate,
        context: String,
        guids: Set<String>,
    ): CancelResult {
        TimeoutUtil.sleep(VERIFY_DELAY_MS)
        val stillThere = collectRunningCandidates(helper, guids).any { it.queryId == killed.queryId }
        return if (!stillThere) {
            DorisCancel.info("$context: verified — query id ${killed.queryId} is gone (kill worked)")
            CancelResult(DorisCancel.KillOutcome.KILLED)
        } else {
            val kind = DorisCancel.describeStatementKind(killed.info)
            DorisCancel.warn(
                "$context: query id ${killed.queryId} STILL running ${VERIFY_DELAY_MS}ms after an " +
                    "accepted KILL (kind=${kind ?: "unknown"}); uncancellable — will offer detach",
            )
            CancelResult(
                DorisCancel.KillOutcome.STILL_RUNNING,
                StillRunning(killed.queryId, kind),
            )
        }
    }

    /** Widen the processlist to all FEs (both variable generations; failures ignored). */
    private fun widenProcesslist(helper: DatabaseConnection) {
        for (sql in listOf(DorisCancel.SQL_ALL_FE_PROCESSLIST_NEW, DorisCancel.SQL_ALL_FE_PROCESSLIST_OLD)) {
            try {
                execute(helper, sql)
                DorisCancel.info("processlist: '$sql' OK")
                return
            } catch (t: Throwable) {
                DorisCancel.info("processlist: '$sql' rejected (${t.message?.take(100)}); trying older name")
            }
        }
    }

    private data class Candidate(val guid: String, val queryId: String, val info: String)

    /**
     * `SHOW FULL PROCESSLIST` scan: every *running* row whose `Info` marker matches one of
     * [guids]. Idle (`Command=Sleep`) rows and the helper's own SHOW/KILL statements are excluded
     * by [DorisCancel.isRunningCancelCandidate].
     */
    private fun collectRunningCandidates(helper: DatabaseConnection, guids: Set<String>): List<Candidate> {
        val statement = JdbcNativeUtil.computeRemote {
            helper.remoteConnection.createStatement()
        } ?: return emptyList()
        try {
            val resultSet = JdbcNativeUtil.computeRemote {
                statement.executeQuery(DorisCancel.SQL_SHOW_FULL_PROCESSLIST)
            } ?: return emptyList()
            try {
                val metadata = resultSet.metaData
                var queryIdIndex = -1
                var infoIndex = -1
                var commandIndex = -1
                for (i in 1..metadata.columnCount) {
                    when (metadata.getColumnLabel(i).orEmpty().lowercase()) {
                        "queryid" -> queryIdIndex = i
                        "info" -> infoIndex = i
                        "command" -> commandIndex = i
                    }
                }
                if (queryIdIndex < 0 || infoIndex < 0) {
                    DorisCancel.info("processlist: result lacks QueryId/Info columns")
                    return emptyList()
                }
                val out = ArrayList<Candidate>()
                while (resultSet.next()) {
                    val info = resultSet.getString(infoIndex) ?: continue
                    val command = if (commandIndex > 0) resultSet.getString(commandIndex) else null
                    if (!DorisCancel.isRunningCancelCandidate(command, info)) continue
                    val guid = DorisCancel.matchedGuid(info, guids) ?: continue
                    val queryId = resultSet.getString(queryIdIndex) ?: continue
                    out.add(Candidate(guid, queryId, info))
                }
                DorisCancel.info("processlist: ${out.size} running tagged candidate(s)")
                return out
            } finally {
                JdbcNativeUtil.performSafe { resultSet.close() }
            }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }

    private fun execute(connection: DatabaseConnection, sql: String) {
        val statement = JdbcNativeUtil.computeRemote {
            connection.remoteConnection.createStatement()
        } ?: throw IllegalStateException("could not create statement")
        try {
            JdbcNativeUtil.computeRemote { statement.execute(sql) }
        } finally {
            JdbcNativeUtil.closeRemoteStatementSafe(statement)
        }
    }
}
