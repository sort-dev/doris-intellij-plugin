# RESEARCH: Cancelling a running Doris query from DataGrip

*Read-only research, 2026-07-08. Evidence: MySQL Connector/J 9.7.0 sources (gradle cache), DataGrip DB-261.24374.56 bytecode (`/Users/jminard/Applications/DataGrip.app`), apache/doris FE source at master `c09ebd73c13` (2026-07-06) + branch-3.0, doris-website checkout (versioned docs 2.1/3.x/4.x). All local; no cluster touched.*

## TL;DR

The hypothesis is confirmed and it is worse than a silent no-op. `Statement.cancel()` in MySQL Connector/J opens a **second connection to the same JDBC URL** and issues `KILL QUERY <connection-id-of-original-session>`. Behind a k8s LB that second connection can land on a different FE, where that connection id either (a) doesn't exist → error 1094, which DataGrip **swallows without reporting**, or (b) **belongs to somebody else's session** — Doris FEs allocate connection ids from a per-FE `AtomicInteger(0)`, so ids collide across FEs, and if the colliding session is the same user (one shared service account: likely), the **wrong query gets silently killed**.

The fix is cheap because Doris ≥ 4.0 has exactly the primitive we need: `KILL QUERY "<string>"` accepts a **query id or a user-chosen trace id** and is **forwarded to every alive FE** until one owns it. And we can *choose* the trace id per console connection at connect time (`SET session_context='trace_id:...'`), so at cancel time we don't need to find anything — we already know the handle. Recommended recipe in [Leg 3](#leg-3--what-we-can-ship).

---

## Leg 1 — What actually happens today

### 1a. MySQL Connector/J `Statement.cancel()`: second connection + `KILL QUERY <thread-id>`

Source: `mysql-connector-j-9.7.0-sources.jar` at
`/Users/jminard/.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j/9.7.0/9a250ab1efab4f10c694fc98c9bdffbe0d931e11/` (extracted to scratchpad `cj-src/`). Identical logic exists in 8.x (the plugin's `doris-drivers.xml` pins DataGrip's rolling "MySQL Connector/J" artifact — 9.5.0/8.2.0/8.0.25 in the user's `jdbc-drivers` caches; all behave the same).

`com/mysql/cj/jdbc/StatementImpl.java`, `cancel()` (lines ~277–330):

```java
newSession = new NativeSession(this.session.getHostInfo(), this.session.getPropertySet());
...
newSession.connect(hostInfo, user, password, database, 30000, ...);
newSession.getProtocol().sendCommand(new NativeMessageBuilder(...)
        .buildComQuery(..., "KILL QUERY " + this.session.getThreadId()), false, 0);
```

Facts that matter:

- **Second connection, same `HostInfo`** — i.e. the same hostname:port from the JDBC URL. Behind a k8s Service/LB the DNS name re-resolves / the LB re-balances → any FE.
- **`this.session.getThreadId()`** is the connection id handed out in the MySQL handshake packet. Doris sends its FE-local connection id there (`MysqlHandshakePacket.java:55` — `serializer.writeInt4(connectionId)`), the same value `SELECT CONNECTION_ID()` returns. So the kill targets *"whatever session has this integer id on whichever FE I happened to reach"*.
- **Always `KILL QUERY`** (kills the statement, not the connection) — no URL property changes the SQL or the routing.
- If the server rejects the kill, `sendCommand` throws and `cancel()` propagates the exception to the caller.

**Query-timeout path is the same code.** `com/mysql/cj/CancelQueryTaskImpl.java` (used when `Statement.setQueryTimeout()` is set, e.g. DataGrip's data-source query timeout): new `NativeSession` to the same `HostInfo`, `"KILL QUERY " + origConnId`. The `queryTimeoutKillsConnection=true` URL property does **not** change the kill routing — it only additionally marks the *local* original connection dead afterwards (`session.invokeCleanupListeners(...)`). So DataGrip timeouts are broken behind the LB in exactly the same way, and no driver property fixes it.

### 1b. DataGrip's cancel seam — there is no dialect kill-SQL hook

Traced through DB-261.24374.56 bytecode (`javap` from Temurin 25; jars under `/Users/jminard/Applications/DataGrip.app/Contents/plugins/DatabaseTools/lib/`):

| Step | Class (jar) | Evidence |
|---|---|---|
| Stop button | `<action id="Console.Jdbc.Cancel" class="com.intellij.database.actions.CancelRunningStatementsAction" use-shortcut-of="Stop">` | `database-plugin.jar!META-INF/plugin.xml:892` |
| Session interrupt | `DatabaseSessionManager$ConnectionWithClient.interrupt()` posts `DataRequest$Cancel` to the session `DataProducer` | `intellij.database.connectivity.jar`, bytecode |
| Engine | `AbstractEngine.visitCancel(DataRequest$Cancel)` → `cancelPendingRequests` → `cancelProgressIndicator()` (`ProgressIndicator.cancel()`) | `intellij.database.connectivity.jar` |
| Indicator → JDBC | `CancellingDelegate.cancel()` (registered by `withCancelling(...)` in `InterruptibleDatabaseConnectionKt`) → connection interrupt | `intellij.database.connectivity.jar` |
| Statement | `JdbcBasedSmartStatement.cancel()` → `SmartStatementsUtil.runWithoutReporting(connection, Stage.CANCEL, ..., () -> RemoteStatement.cancel())` | `intellij.database.core.impl.jar`, bytecode above |
| Remote process | `RemoteStatement.cancel()` → driver `Statement.cancel()` | `intellij.database.jdbcConsole.jar` (RMI interface) |

Two conclusions:

1. **The chain ends in JDBC `Statement.cancel()`. Nothing dialect-specific is issued.** `DatabaseDialectEx` (full `javap -p` dump, `intellij.database.dialects.base.jar`) contains *no* cancel/kill/terminate methods — its seams are DDL/search-path only (the ones `DorisDatabaseDialect.kt` already overrides). The `pg_cancel_backend` hits in `postgresbase.jar` are just `<name>` entries in the bundled function-catalog XML, not cancel machinery. There is **no overridable "cancel SQL" hook on the dbms-keyed dialect** — the cancel seam must be taken at the *action* level (below).
2. **Failures are swallowed.** `runWithoutReporting` converts the driver exception into an `Either` and `cancel()` returns a boolean nobody surfaces. This is why the user experiences "cancel does nothing" with no error, and ends up deactivating the data source (DataGrip's own escalation when a statement won't die is to close/kill the connection — which is what deactivation forces).

### 1c. Where `/* ApplicationName=IntelliJ IDEA 2026.1.4 */` comes from (addendum §1)

Two halves, both verified:

- **IDE half** — `DatabaseConnectionEstablisher.prepareConnection(RemoteConnection)` (`intellij.database.connectivity.jar`, bytecode): if the driver option `DatabaseDriver.OPTION_SEND_APP_INFO` is on (the data-source driver "send application info" option, on by default) **and** current client info is empty, it calls
  `RemoteConnection.setClientInfo("ApplicationName", ApplicationInfo.getFullApplicationName())` once per established connection.
- **Driver half** — Connector/J's default `clientInfoProvider` is `com.mysql.cj.jdbc.CommentClientInfoProvider` (sources in `cj-src/com/mysql/cj/jdbc/CommentClientInfoProvider.java`): it renders **all** client-info entries as `key=value, key2=value2`, calls `JdbcConnection.setStatementComment(...)`, and `NativeMessageBuilder.buildComQuery` (lines 112–113, 259–263) **prepends `/* ... */` to every outgoing COM_QUERY on that connection**.

So the comment is a *per-connection* property rendered into *every statement*, server-visible in the processlist `Info` column (the Doris docs' own SHOW PROCESSLIST example shows DBeaver's `/* ApplicationName=... */` — Doris stores the full received text including the comment; `ThreadInfo.toRow` returns `executor.getOriginStmtInString()`).

**Can a plugin extend it? Yes, trivially and additively.** `RemoteConnection.setClientInfo(String,String)` is public API (`javap` on `com.intellij.database.remote.jdbc.RemoteConnection`), and `CommentClientInfoProvider` merges keys. Calling `connection.getRemoteConnection().setClientInfo("DorisTraceId", guid)` from a `DatabaseConnectionInterceptor.handleConnected(...)` yields a comment like:

```
/* ApplicationName=DataGrip 2026.1.4, DorisTraceId=dg-9f2c41d87ab34e0b */ SELECT ... FROM acme_orders ...
```

on **every** statement of that connection — an exactly-greppable fingerprint in `SHOW PROCESSLIST` `Info` on any FE. Because the comment is prepended, it survives even non-FULL processlist truncation (Info is cut at 100 chars — the marker sits in the first ~60). Per-STATEMENT markers would need a per-execution hook; they're unnecessary — a console connection executes one user statement at a time, so a per-connection GUID uniquely identifies "the query this console is running right now".

The EP is `com.intellij.database.connectionInterceptor` (see registrations in `intellij.database.connectivity.xml:81-117`); `DatabaseConnectionInterceptor` exposes `handleConnected(DatabaseConnectionCore, ProtoConnection)` — post-connect, perfect for both marker injection and identity capture.

---

## Leg 2 — What Doris supports (FE source, master = 4.x dev)

### KILL grammar and semantics

`DorisParser.g4:530-531`:

```antlr
: KILL (CONNECTION)? INTEGER_VALUE                 #killConnection
| KILL QUERY (INTEGER_VALUE | STRING_LITERAL)      #killQuery
```

Behavior — `fe-core/.../nereids/trees/plans/commands/utils/KillUtils.java` (all line refs current master):

| Statement | Resolution | Cross-FE? |
|---|---|---|
| `KILL <n>` / `KILL CONNECTION <n>` | `ctx.getConnectScheduler().getContext(n)` — **local FE map only**; kills the whole connection | **No.** `ERR_NO_SUCH_THREAD` (1094, *"Unknown thread id"*) if absent |
| `KILL QUERY <n>` | same local map; kills only the running query (`killCtx.kill(false)`) | **No** (same 1094 if absent) |
| `KILL QUERY "<string>"` | `getContextWithQueryId(str)` matches **query id OR trace id** (`ConnectPoolMgr.java:103-110`: `queryId.equals(DebugUtil.printId(context.queryId)) \|\| queryId.equals(context.traceId())`); kills query only | **Yes (4.0+).** If not found locally, the *original KILL statement* is forwarded via `FEOpExecutor` to **every alive FE** (`KillUtils.killQueryByQueryId`, lines 101–128); first FE that owns it kills it and returns OK. `ERR_NO_SUCH_QUERY` (1097) only if no FE has it |

- Auth: killing your **own** user's query/connection needs no privilege; other users' need ADMIN (`KillUtils.java:152-156, 181-186`). The killer connection uses the same data-source credentials as the console → same user → always allowed.
- `KILL QUERY` (any form) does **not** drop the connection — the console session survives; the running statement returns an error to the client. Exactly what a cancel should do.

**Since-version:** the cross-FE forwarding + kill-by-trace-id landed in commit `4f1aa7db923` — *"[fix/feature](kill) fix kill operation and support kill by trace id (#50791)"*, 2025-05-26; first release tags `4.0.0-rc01+`; **not** on `branch-2.1`/`branch-3.0`.
**Pre-4.0 behavior** (verified in `branch-3.0` `StmtExecutor.handleKill`, lines ~1646–1680): `KILL QUERY "<query_id>"` not found on the local FE → parse the string as a `TUniqueId` and **broadcast a cancel to every alive BE**. So kill-by-*query-id* still works cross-FE on 2.1/3.x (fragments die on the BEs, the coordinating FE aborts the query), but kill-by-*trace-id* does not (a trace id doesn't parse as `TUniqueId` → `UserException`).

### Connection ids: per-FE, colliding, and *why the stock cancel can kill the wrong query*

`ConnectScheduler.java:59,84` — `nextConnectionId = new AtomicInteger(0); ... context.setConnectionId(nextConnectionId.getAndAdd(1))`. Every FE starts at 0. Behind an LB, id `48` almost certainly exists on several FEs simultaneously, owned by different sessions. Consequences for `KILL QUERY <conn-id>` landing on the wrong FE (addendum §2, "loud or silent?"):

- id absent there → **loud at the wire** (error 1094), but Connector/J's exception is then **swallowed by DataGrip** (`runWithoutReporting`) → *observed as silent no-op*.
- id present, owned by another user → error 1095 (`ERR_KILL_DENIED_ERROR`) → same swallowed outcome.
- id present, owned by the **same user** (shared service account, other teammate's console) → **their query is killed, silently, and yours keeps running**. This is the sharpest argument for never letting the stock path be the only cancel.

### Finding/naming the query from another connection

- **`SHOW [FULL] PROCESSLIST`** — columns in master (`SchemaTable.java:538-556`, mirrored by `information_schema.processlist`):
  `CurrentConnected, Id, User, Host, LoginTime, Catalog, Db, Command, Time, State, QueryId, TraceId, Info, FE, CloudCluster`.
  - **`QueryId`** — present since 2.x (2.1 docs show it).
  - **`FE`** (owning frontend host) and **`TraceId`** — in master/4.x source (`ThreadInfo.toRow` adds `Env.getCurrentEnv().getSelfNode().getHost()`); the FE column was added mid-2024 (`2917124ebd7`, 4.0 tags), so pre-4.0 rows don't say which FE owns them — there you match by the Info-comment marker instead.
  - **Scope:** local FE by default. `SET show_all_fe_connection = true` (documented in 2.1+) makes SHOW PROCESSLIST RPC all FEs and merge (`ShowProcessListCommand.doRun`). In Sep 2025 (`8ce0d2642f9`, #55700) the variable was **renamed to `fetch_all_fe_for_system_table` with default `true`** — current 4.x shows all FEs out of the box; on older versions set the old name.
  - `Info` = the full received statement **including the driver-prepended `/* ... */` comment**; non-FULL truncates to 100 chars (comment-first, so markers survive).
- **`information_schema.active_queries`** (`SchemaTable.java:498`): `QUERY_ID, QUERY_START_TIME, QUERY_TIME_MS, ..., FRONTEND_INSTANCE, QUERY_STATUS, USER, SQL` — cluster-wide running queries with the owning FE. Nice for a picker UI.
- **`SELECT LAST_QUERY_ID()`** — exists (2.1+ docs; nereids `LastQueryId`), but is only meaningful **on the same session, after the query has started** — the console connection is busy executing, so we cannot ask it. Useless for pre-capture; the user's instinct here is right.
- **Pre-chosen identity — trace id**: `SET session_context = 'trace_id:<anything>'` (parsed in `VariableVarCallbacks.SessionContextCallback`) binds the string to the connection's `ConnectContext`; **every subsequent query start maps it to that query** (`ConnectContext.java:1046-1047`). This is the only identity we can know *before* execution — because we invent it.
- **Which-FE-am-I** (addendum §2): `SHOW FRONTENDS` has a `CurrentConnected` column (`FrontendsProcNode.java:110-116`); also 4.x processlist row with `CurrentConnected=Yes` carries the `FE` host. So (FE-host, connection-id) is capturable once at connect time.
- **REST**: `POST /rest/v2/manager/query/kill/{query_id}?is_all_node=true` fans out to all FEs (`QueryProfileAction.java:521-540`) — but requires ADMIN and HTTP access to an FE, which is exactly what's unreachable behind this k8s setup. SQL path is strictly better for us.

### The clean recipe (server side)

On Doris ≥ 4.0, given a killer connection **on any FE**:

```sql
KILL QUERY "dg-9f2c41d87ab34e0b";   -- trace id chosen by us at connect time; forwarded FE→FE
```

One statement, no search, kills only the query, safe against id collisions (the string is a GUID). On 2.1/3.x: find the `QueryId` first (processlist marker match), then `KILL QUERY "<query_id>"` — local hit or BE-broadcast both end the query.

---

## Synthesis — why today's UX is "deactivate the data source"

1. Stop button → JDBC `Statement.cancel()` → driver opens a *new* connection to the LB → usually a different FE.
2. `KILL QUERY <conn-id>` there: unknown thread id (or a same-user collision — worse).
3. The SQL error is swallowed (`runWithoutReporting`); the console still shows a running query.
4. The user's only remaining lever that provably works is tearing down the process-side connection — deactivate the data source. The Doris-side query *keeps running to completion anyway* (nothing ever told its FE to stop), burning cluster resources.

---

## Leg 3 — What we can ship

### Ranked options

**Option 1 — connect-time trace-id + plugin-owned cancel (RECOMMENDED PRIMARY).**
- *UX*: the normal Stop button just works; nothing new to learn.
- *Reliability behind LB*: exact — `KILL QUERY "<guid>"` is FE-forwarded (4.0+); GUID is globally unique → zero wrong-kill risk; same-user auth always passes.
- *Size*: small. One `DatabaseConnectionInterceptor` (~60 lines) + one action override (~100–150 lines) + a killer-connection helper. No parser/model surface, flag-gateable like everything else in the plugin.
- *Risks*: needs Doris ≥ 4.0 for the one-shot path (fallback below covers older); `session_context` is one session variable — if a user manually resets it we lose the handle (fallback covers that too); action override must delegate to stock behavior for non-Doris sessions.

**Option 2 — "Kill Doris query" console action with processlist picker (FALLBACK / COMPLEMENT — also ship).**
- *UX*: explicit action (console toolbar / context menu): runs `SET fetch_all_fe_for_system_table=true` (or `show_all_fe_connection=true` on old versions) + `SHOW FULL PROCESSLIST` on a helper connection, shows rows (pre-selecting the row whose `Info` contains this console's marker / whose `TraceId` matches), user confirms → `KILL QUERY "<QueryId>"`.
- *Reliability*: works on **every** Doris version (query-id kill reaches other FEs via FE-forwarding on 4.0+ or BE-broadcast on 2.1/3.x); human confirmation removes wrong-kill risk entirely.
- *Size*: modest (a dialog); doubles as a mini process manager — a first slice of the manageability backlog below.
- *Risks*: an extra click; picker lists only same-user rows unless admin.

**Option 3 — driver URL properties / infra that make stock cancel work (DOCUMENT-ONLY).**
- No Connector/J property changes cancel routing (verified in source; `queryTimeoutKillsConnection` is local-only). The only true fixes are *infrastructural*: `sessionAffinity: ClientIP` on the k8s Service, or per-FE Services/direct FE host in the JDBC URL, so the second connection lands on the same FE. Worth a README note for users who can touch infra; not something the plugin can effect.

**Option 4 — document the manual workaround.** Second console: `SET fetch_all_fe_for_system_table=true; SHOW FULL PROCESSLIST;` → `KILL QUERY "<QueryId>"`. Zero code; today's stopgap; belongs in plugin docs regardless.

**Ranking: 1 (primary) + 2 (fallback/complement), 4 as docs, 3 as an infra footnote.** The addendum's instinct — *kill by query id (or better, by our own trace id) is the most reliable* — is confirmed with source evidence; kill-by-connection-id is confirmed cross-FE-unsafe (and collision-dangerous).

### The concrete recipe (what we inject/capture, when)

**At connect time** — `DatabaseConnectionInterceptor.handleConnected(connection, proto)` (EP `com.intellij.database.connectionInterceptor`), guarded by `connection.getDbms() == Doris dbms`:

1. Mint `guid = "dg-" + 16-hex chars` per connection; remember it keyed by the connection.
2. `SET session_context = 'trace_id:dg-9f2c41d87ab34e0b'` — the 4.0+ kill handle (execute via the same `DbImplUtilCore`/SmartStatements shape `DorisDatabaseDialect.queryStringOrNull` already uses).
3. `connection.getRemoteConnection().setClientInfo("DorisTraceId", "dg-9f2c41d87ab34e0b")` — server-visible fingerprint in every statement's `Info` on any version (merges with DataGrip's `ApplicationName` via `CommentClientInfoProvider`).
4. Capture identity: `SELECT CONNECTION_ID()`; FE host from the 4.x processlist `FE` column of the `CurrentConnected=Yes` row, or `SHOW FRONTENDS` `CurrentConnected`. (Belt-and-braces for the pre-4.0 path and for diagnostics; steps 2+3 are the load-bearing ones.)

**At execute time** — nothing. The driver stamps the comment on every statement by itself; the FE maps trace-id→query-id at every query start by itself.

**At cancel time** — override `<action id="Console.Jdbc.Cancel">` (`com.intellij.database.actions.CancelRunningStatementsAction`, `database-plugin.jar!plugin.xml:892`) for Doris sessions (delegate to the stock action otherwise). On trigger, from a short-lived helper connection to the data source (any FE the LB gives us):

```sql
-- Doris >= 4.0: one shot, forwarded across FEs, kills only the query
KILL QUERY "dg-9f2c41d87ab34e0b";
-- error 1097 "Unknown query id" => nothing was running; treat as success
```

Pre-4.0 fallback (also the recovery path if the trace id was clobbered):

```sql
SET show_all_fe_connection = true;               -- old name; new: fetch_all_fe_for_system_table
SHOW FULL PROCESSLIST;
-- match: Info LIKE '%DorisTraceId=dg-9f2c41d87ab34e0b%'
--        (or Id = <captured conn id> AND FE = '<captured FE host>')  -- 4.x columns
KILL QUERY "e6e4ce9567b04859-8eeab8d6b5513e38";  -- the row's QueryId; reaches other FEs
```

Then still let the stock `Statement.cancel()` fire (or run our kill first and let stock become a harmless 1094 on an already-finished query) so the *client-side* statement unblocks promptly. Optionally: suppress the stock path entirely for Doris to eliminate the same-user collision wrong-kill; since our kill makes the server return an error to the console connection, the client unblocks anyway.

Example with generic identifiers, end to end: console runs `INSERT INTO acme_dw.acme_orders_agg SELECT ... FROM acme_events ...`; processlist on any FE shows `Info = /* ApplicationName=DataGrip 2026.1.4, DorisTraceId=dg-9f2c... */ INSERT INTO acme_dw...`, `TraceId = dg-9f2c...`; Stop button → helper connection → `KILL QUERY "dg-9f2c..."` → console gets *"Query was cancelled"*, connection stays alive, grid shows the error, done.

### queryTimeout note

DataGrip's query-timeout option becomes `Statement.setQueryTimeout()` → Connector/J `CancelQueryTaskImpl` → **the same second-connection `KILL QUERY <conn-id>`** → equally broken behind the LB (and `queryTimeoutKillsConnection` only adds local connection teardown). If we ship Option 1, consider also advising users to keep the JDBC queryTimeout off for Doris and rely on Doris' own `query_timeout` session/global variable, which the FE enforces coordinator-side and needs no second connection at all.

---

## Backlog: manageability (recorded verbatim-intent, no research tonight)

The user's larger daily pain, beyond cancel: **Doris manageability is poor.**

- The **job → task → task-item error drill-down is buried** — finding *why* something failed takes too many hops through SHOW-style commands and tables.
- **Some errors never clear and are undated** — stale failures linger with no timestamp, so you can't tell a new error from one that's weeks old.
- **Some errors only exist on FE/BE node web UIs** — which are **unreachable behind k8s** (no route to individual pods' HTTP ports), so parts of the cluster's diagnostic surface are simply invisible in this deployment.

Future project direction: a **shim or Doris-side plugin adding manageability** — surfacing job/task/task-item errors properly (dated, clearable, drillable) in the IDE or elsewhere, rather than scattered across per-node web UIs. The Option-2 processlist/kill picker above would be a natural first slice of that surface. Recorded as backlog; no design work done tonight.

---

## Evidence index

- **Driver**: `cj-src/com/mysql/cj/jdbc/StatementImpl.java` (cancel, ~277–330); `cj-src/com/mysql/cj/CancelQueryTaskImpl.java`; `cj-src/com/mysql/cj/jdbc/CommentClientInfoProvider.java`; `cj-src/com/mysql/cj/protocol/a/NativeMessageBuilder.java:112,259` (comment prepend). Sources jar: `/Users/jminard/.gradle/caches/modules-2/files-2.1/com.mysql/mysql-connector-j/9.7.0/.../mysql-connector-j-9.7.0-sources.jar`.
- **DataGrip DB-261.24374.56** (`/Users/jminard/Applications/DataGrip.app/Contents/plugins/DatabaseTools/lib/`): `modules/intellij.database.connectivity.jar` → `DatabaseConnectionEstablisher(.Companion)` (`prepareConnection`, `setClientInfo("ApplicationName", ...)`, `OPTION_SEND_APP_INFO` gate), `DatabaseSessionManager$ConnectionWithClient.interrupt`, `AbstractEngine.visitCancel`, `CancellingDelegate`; `modules/intellij.database.core.impl.jar` → `JdbcBasedSmartStatement.cancel` → `runWithoutReporting(... RemoteStatement.cancel)`; `modules/intellij.database.dialects.base.jar` → `DatabaseDialectEx` (no cancel methods); `database-plugin.jar!META-INF/plugin.xml:892` (`Console.Jdbc.Cancel`); `modules/intellij.database.jdbcConsole.jar` → `RemoteConnection.setClientInfo/cancelAll`, EP list in `intellij.database.connectivity.xml`.
- **Doris FE (master `c09ebd73c13`)**: `fe/fe-core/.../commands/utils/KillUtils.java`; `commands/KillQueryCommand.java`, `KillConnectionCommand.java`; `qe/ConnectScheduler.java:59,84`; `qe/ConnectPoolMgr.java:103-121`; `qe/ConnectContext.java:1046-1071,1338-1375` (ThreadInfo.toRow); `catalog/SchemaTable.java:498,538-556`; `qe/VariableVarCallbacks.java` (session_context trace_id); `qe/SessionVariable.java:790,3253` (fetch_all_fe_for_system_table default true); `httpv2/rest/manager/QueryProfileAction.java:521-540`; `mysql/MysqlHandshakePacket.java:55`; `fe-sql-parser DorisParser.g4:530-531`; `common/proc/FrontendsProcNode.java:110-116`. History: `4f1aa7db923` (#50791, 4.0.0-rc01+, not in branch-2.1/3.0); branch-3.0 `StmtExecutor.handleKill` (BE broadcast); `fb406cc1885` (#30907 show_all_fe_connection); `8ce0d2642f9` (#55700 rename, default true); `2917124ebd7` (FE column); `af68d8b5077` (#51400 TraceId column); `55727c312ea` (#40739 last_query_id).
- **Docs (doris-website checkout, 2026-07-06)**: `versioned_docs/version-{2.1,3.x,4.x}/sql-manual/sql-statements/session/quer{y,ies}/KILL-QUERY.md`, `SHOW-PROCESSLIST.md`, `.../system-functions/last-query-id.md`.
- **Plugin**: `/Users/jminard/DEV/brikk/repos/doris-intellij/wt-froze-over/src/main/kotlin/dev/sort/doris/catalog/DorisDatabaseDialect.kt`; `/Users/jminard/DEV/brikk/repos/doris-intellij/wt-froze-over/src/main/resources/config/doris-drivers.xml` (Connector/J rolling artifact).

---

## LIVE VERIFICATION — Doris 4.1.2 (podman, 2026-07-08)

Every load-bearing claim above was validated against a real `doris-4.1.2-rc01` FE+BE cluster
(apache/doris:fe-4.1.2 / be-4.1.2 images; FE heap patched 8g→2g and BE mem_limit 2G to fit an 8 GB
VM — conf recipe preserved in the session scratchpad `doris-cluster/`):

| Claim | Result |
|---|---|
| `SHOW PROCESSLIST` columns incl. `QueryId`, `TraceId`, `FE`, `CurrentConnected` | **VERIFIED** — exact column set as researched |
| `SET session_context='trace_id:dg-…'` binds our chosen id; visible in `TraceId` | **VERIFIED** |
| `KILL QUERY "dg-…"` (trace id) from a *different* connection kills the running query | **VERIFIED** — `SELECT SLEEP(300)` died in ~2 s: `cancel query by user from <addr>`; killer got OK |
| Kill by `QueryId` string | **VERIFIED** — same clean cancellation |
| Kill by *wrong connection id* | **VERIFIED LOUD** — `Unknown thread id: 99999` (error 1105/2). The server is loud; DataGrip's `runWithoutReporting` is what makes the stock path *feel* silent |
| Kill by unknown trace/query id | **VERIFIED** — `Unknown query id: dg-neverexists` → plugin should treat as "nothing running, success" |
| Driver-style `/* … DorisTraceId=dg-… */` comment visible in processlist `Info` | **VERIFIED** — marker survives, greppable |
| Cross-FE forwarding | **NOT tested live** (single-FE sandbox; 8 GB VM can't fit two 2 GB FEs + BE). Source-verified only (`KillUtils.killQueryByQueryId` FE fan-out). Validate on the user's real multi-FE cluster: `KILL QUERY "<trace>"` issued via the k8s LB |

Conclusion: the recommended recipe (connect-time trace id + `KILL QUERY "<trace>"` at cancel) is
**implementation-ready**; the only remaining unknown is multi-FE forwarding, which the user's
production cluster can confirm in one console command.
