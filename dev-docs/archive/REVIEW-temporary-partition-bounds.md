# Temporary-partition execution bounds in 1.4.4

## Reproduction and cause

The 1.4.2 and 1.4.3 tests parsed the INSERT alone or after `SELECT 0;`. They missed
the effect of a preceding range-partition definition on the lexer's statement state.

This valid Doris script reproduces the split in 1.4.3, including the intervening SELECT:

```sql
ALTER TABLE pm_swap_hourly ADD TEMPORARY PARTITION p_20240502_day
VALUES [('2024-05-02 00:00:00'), ('2024-05-03 00:00:00'));
select 1;

INSERT INTO pm_swap_hourly TEMPORARY PARTITION(p_20240502_day)
SELECT event_at, id, amount, note
FROM pm_swap_hourly
WHERE event_at >= '2024-05-02 00:00:00'
  AND event_at < '2024-05-03 00:00:00';
```

The outer `[` and `)` denote a half-open partition interval. `DorisLexer` counted
parentheses but not brackets, leaving its depth negative. Its new statement-lead
tracking reset at a semicolon only when the depth was zero. Later INSERTs therefore
inherited an earlier statement's lead, preventing the `TEMPORARY` modifier mask.
MySQL error recovery then treated a SELECT on the next line as a new statement.

The actual local `88-partition-mania.sql` file also reproduced the screenshot through
`JdbcConsoleProvider.findScriptModel` and `chooseStatements`, which the platform's
green execution-box highlighter calls. At the reported multiline INSERT, 1.4.3 chose
offsets 31474–31607, starting at SELECT. With the correction it chooses 31411–31607,
starting at INSERT. These offsets refer to the file snapshot used for the test.

## Correction

- Every semicolon token resets the statement lead, parenthesis depth, and CAST context.
  Semicolons inside comments or literals are not delimiter tokens.
- Lexer restart context recovery uses MySQL tokens instead of a raw backwards
  semicolon search, so comment and literal contents cannot invent statement boundaries.

The SQL does not need bracket changes. The embedded engine remains brikk-sql 0.15.1.

## Verification

- Full default test suite: 457 tests, zero failures, errors, or skips.
- Targeted lexer and execution-range tests passed on DB-262.10315.132 and
  IU-263.4732.28 using the 261-compiled classes. The temporary full-file probe also
  passed on each of the three platform generations.
- Committed regressions cover preceding range DDL followed by `select 1;`, fresh
  parsing, paste and edit, replay on/off, caret positions in the header and source,
  and all three selection modes. The ranges come from the platform's statement
  chooser rather than only counting PSI nodes.
- `buildPlugin` and `verifyEmbeddedPipes` passed. The final descriptor is 1.4.4.

Tests parsed the file and inspected chosen SQL; they did not execute its database
operations. The temporary probe was removed from the suite to avoid depending on
the requester's checkout. Logs, its source, and before/after XML are under
`/tmp/opencode/doris-partition-*` and `/tmp/opencode/doris-file-probe-before.xml`.

Local installation ZIP:

```text
build/distributions/doris-intellij-plugin-1.4.4.zip
SHA-256: 2f4ef41062d2f2844355d99605cab06e4e1f7eba9200e0b9edddb39f8fe2b46b
```
