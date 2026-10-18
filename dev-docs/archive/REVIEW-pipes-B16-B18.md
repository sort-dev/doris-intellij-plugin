# B16-B18 PIPE stage authoring verification

Date: 2026-09-09

Status: **fixed; verification passed**.

## Changes

- Run-to-stage has a contained first-stage mode that translates marker-free `FROM`
  prefixes only after a real Doris pipeline has been identified.
- The stage-shape LRU uses structural keys containing normalized SQL, base relation
  and immutable base columns. Locked lookup/insertion preserves access ordering while
  engine computation remains outside the lock.
- Stage scopes represent the input to the caret's stage. Wildcards merge known base
  columns; exact empty scopes no longer restore stale base columns.
- Completion alias discovery scans only native-token-visible text before the caret.
  Future PIPE AS/JOIN aliases and declaration-like text in comments, strings, complex
  backticks or malformed lexical tails cannot become qualifiers.

## Tests

- Initial `FROM` stage executes through both intention and menu entry points with the
  exact editor range and initiating console owner; later stages remain excluded.
- Normal `transpile("FROM t")` stays `NotPipe`; the scoped mode rejects ordinary SELECT.
- The known `FB`/`Ea` Java-hash collision returns the correct scope in both call orders.
- FROM, EXTEND, SELECT and LIMIT caret positions receive their exact input scopes.
- Alias visibility covers before/after declaration, comments, strings, dollar strings,
  simple/complex backticks, and simple/nested unterminated comments.

## Verification

- Full DB-261.24374.56 suite, SQL Transpiler absent: 407/407 passed.
- Full DB-261.24374.56 suite, SQL Transpiler installed: 407/407 passed.
- PIPE execution/upgrade/safety tests on DB-262.10315.132, companion absent: 105/105 passed.
- PIPE execution/upgrade/safety tests on DB-262.10315.132, companion installed: 105/105 passed.
- `verifyEmbeddedPipes`: passed; embedded libraries, notices and Java 21 ceiling are unchanged.
- Plugin Verifier 1.410: DB-261.24374.56 and IU-262.8665.81 both Compatible.

The verifier retained the existing notices: 4 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 261; 5 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 262. No compatibility problem was hidden.

No live database operation or SQL request was performed.
