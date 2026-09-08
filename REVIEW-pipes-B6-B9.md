# B6-B9 pipe diagnostics verification

Date: 2026-09-08

Status: **fixed; verification passed**.

## Changes

- `DorisHighlightInfoFilter` classifies pipe territory through the shared
  native-token chunk scanner. Textual `|>` inside literals, comments, dollar strings
  and quoted identifiers no longer suppresses ordinary semantic diagnostics.
- `DorisErrorAnnotator` maps parser line/code-point columns to UTF-16 document offsets
  and removes native errors only when the exact offset lies inside a pipe chunk.
- Doris-owned `Doris Pipes:` and `Doris (server):` annotations bypass semantic filters.
- The B3/B10 per-chunk recovery fix is pinned with the original B8 multi-error shape.

## Tests

- Full DB-261.24374.56 suite, SQL Transpiler absent: 391/391 passed.
- Full DB-261.24374.56 suite, SQL Transpiler installed: 391/391 passed.
- Changed diagnostics on DB-262.10315.132, companion absent: 36/36 passed.
- Changed diagnostics on DB-262.10315.132, companion installed: 36/36 passed.
- `verifyEmbeddedPipes`: passed; embedded libraries, notices and Java 21 ceiling are unchanged.
- Plugin Verifier 1.410: DB-261.24374.56 and IU-262.8665.81 both Compatible.

The verifier reported the existing API notices: 4 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 261; 5 scheduled-removal, 6 deprecated,
33 experimental and 17 internal usages on 262. No compatibility problem was hidden.

No live database operation or SQL request was performed.
