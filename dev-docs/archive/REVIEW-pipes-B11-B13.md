# B11-B13 pipe execution verification

Date: 2026-09-08

Status: **fixed; verification passed**.

## Changes

- The Execute interceptor keeps the `ScriptModel` and live `ExecOption` selected by
  `JdbcConsoleProvider.chooseStatements` instead of rebuilding a caret chunk.
- A pipe-aware model delegates ordinary statements and transforms pipe statements at
  `query`/`consoleQuery`. Every selected pipe is preflighted before execution starts.
- Stock `JdbcConsole.beforeExecuteQueries` and `executeQueries` now own normal Execute,
  restoring request chains, editor coupling, result routing and `newTab`.
- Native `:name` tokens become platform parameters. `ScriptModelUtilCore.statementText`
  applies the console's prompted or stored values before transpilation.
- Run-to-stage uses the same parameter model. Fixed-scope helper requests and error
  tracking use the initiating console, never the session's first client.
- Console lookup now requires exact virtual-file ownership.
- Error-map plans travel with each stock request chain and resolve by `queryIndex`, so
  retries and duplicate generated SQL retain the correct source statement.

Parameterized runs use the existing token fallback for server-error mapping because a
replacement value can change source offsets. Non-parameterized runs retain exact engine
source maps.

## Tests

- Full DB-261.24374.56 suite, SQL Transpiler absent: 395/395 passed.
- Full DB-261.24374.56 suite, SQL Transpiler installed: 395/395 passed.
- Execution/action/safety tests on DB-262.10315.132, companion absent: 35/35 passed.
- Execution/action/safety tests on DB-262.10315.132, companion installed: 35/35 passed.
- `verifyEmbeddedPipes`: passed; embedded libraries, notices and Java 21 ceiling are unchanged.
- Plugin Verifier 1.410: DB-261.24374.56 and IU-262.8665.81 both Compatible.

Coverage includes current single-statement behavior, whole script, script tail,
selected script, ordered mixed SQL/pipe chains, `newTab`, complete preflight, Execute
and run-to-stage parameters, detached session-client lists, and two consoles sharing
one session. No test connects to a database or executes SQL against a server.

The verifier reported the existing API notices: 4 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 261; 5 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 262. No compatibility problem was hidden.
