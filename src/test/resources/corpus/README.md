# Golden-tree corpus

Source SQL for the dual golden-tree corpus driven by
`src/test/kotlin/dev/sort/doris/sql/DorisGoldenCorpusTest.kt` (Gate 1 of
`RESEARCH-when-hell-freezes-over-parser.md`, "The golden-corpus method").

Every `*.sql` file here is parsed by **two** dialects and its `DebugUtil.psiToString`
tree is pinned:

- `golden/doris/<rel>.tree` — the tree our shipping **DorisSQL** dialect produces.
- `golden/mysql/<rel>.tree` — the tree the **raw platform MySQL** dialect produces.

The test walks this directory recursively and mirrors each file's relative path under
both golden roots (e.g. `corpus/mysql-core/04-baseline-select.sql` →
`golden/doris/mysql-core/04-baseline-select.tree` and its `mysql/` twin).

The MySQL goldens are an **upstream-drift alarm**: when a future DataGrip reshapes
MySQL's trees they fail loudly, naming exactly which constructs moved. The Doris goldens
are our **regression** surface.

## Categories

- **`mysql-core/`** — constructs both dialects share. This is Route B's target-shape
  spec and the primary upstream-drift alarm surface; Doris and MySQL trees are expected
  to be identical (or differ only where a documented Doris construct is involved).
- **`doris/`** — Doris-specific constructs. The lexer masks fire here and the two trees
  deliberately diverge — that divergence is the point, recorded without judgment.
- **`edge/`** — mask near-misses and broken-SQL recovery shapes (see the review rule).

## Manifest — `mysql-core/`

| File | Intent |
|------|--------|
| 01-select-with-lag-toplevel.sql | CTE + top-level `SELECT *, LAG() OVER (...)` window function |
| 02-watch-time-lag-query.sql | Realistic CTE + windowed `LAG` over a multi-key `PARTITION BY` |
| 03-scalar-subquery-interval.sql | Scalar subquery compared against `INTERVAL` arithmetic |
| 04-baseline-select.sql | Minimal `SELECT ... FROM ... WHERE` |
| 05-baseline-join.sql | Two-table `INNER JOIN` with `ON` + `WHERE` |
| 06-baseline-group-by.sql | `GROUP BY` / `HAVING` / `ORDER BY` |
| 07-operator-precedence-chain.sql | Arithmetic operator precedence (`+ - * / %`) |
| 08-case-simple.sql | Simple `CASE expr WHEN ...` |
| 09-case-searched.sql | Searched `CASE WHEN cond ...` |
| 10-between-in-like-isnull.sql | `BETWEEN` / `IN` / `LIKE` / `IS NULL` predicate cluster |
| 11-scalar-subquery.sql | Scalar subquery in the select list |
| 12-exists-subquery.sql | `EXISTS (subquery)` predicate |
| 13-correlated-subquery.sql | Correlated scalar subquery in the select list |
| 14-trim-leading.sql | `TRIM(LEADING 'x' FROM y)` |
| 15-extract-year.sql | `EXTRACT(YEAR FROM d)` |
| 16-position-in.sql | `POSITION('a' IN b)` |
| 17-group-concat.sql | `GROUP_CONCAT(DISTINCT x ORDER BY y SEPARATOR ',')` |
| 18-count-distinct-multi.sql | `COUNT(DISTINCT a, b)` |
| 19-literal-zoo.sql | Hex / float / escaped-string / `NULL` / `TRUE` / `FALSE` literals |
| 20-interval-arithmetic.sql | `col + INTERVAL 1 DAY` |
| 21-joins-inner-left-right-cross.sql | `INNER` / `LEFT` / `RIGHT` / `CROSS` join chain |
| 22-join-using.sql | `JOIN ... USING (col)` |
| 23-self-join.sql | Self-join with table aliases |
| 24-derived-table.sql | Derived table (subquery) with alias |
| 25-union-vs-union-all.sql | `UNION` and `UNION ALL` chain |
| 26-parenthesized-union-order-limit.sql | Parenthesized union with trailing `ORDER BY` / `LIMIT` |
| 27-window-frame-rows.sql | Window frame `ROWS BETWEEN 1 PRECEDING AND CURRENT ROW` |
| 28-named-window-clause.sql | Named `WINDOW` clause referenced by `OVER w` |
| 29-with-recursive.sql | `WITH RECURSIVE` self-referencing CTE |
| 30-limit-offset-variants.sql | `LIMIT n OFFSET m` and `LIMIT m, n` |
| 31-distinct.sql | `SELECT DISTINCT` |
| 32-having.sql | `GROUP BY` with `HAVING` |
| 33-update-with-join.sql | Multi-table `UPDATE ... JOIN ... SET` |
| 34-delete-using.sql | `DELETE FROM ... USING` join |
| 35-insert-values-multirow.sql | `INSERT ... VALUES` with multiple rows |
| 36-insert-on-duplicate-key.sql | `INSERT ... ON DUPLICATE KEY UPDATE` |
| 37-replace-into.sql | `REPLACE INTO ... VALUES` |
| 38-create-table-plain.sql | `CREATE TABLE` (columns, PK, index, `AUTO_INCREMENT`) |
| 39-alter-table-add-modify-drop.sql | `ALTER TABLE ADD` / `MODIFY` / `DROP COLUMN` |
| 40-create-index.sql | `CREATE INDEX ... ON` |
| 41-truncate.sql | `TRUNCATE TABLE` |
| 42-explain-select.sql | `EXPLAIN SELECT` |
| 43-set-var-session.sql | `SET @var` and `SET SESSION` |
| 44-transaction-control.sql | `START TRANSACTION` / `COMMIT` / `ROLLBACK` |

## Manifest — `doris/`

| File | Intent |
|------|--------|
| 01-insert-overwrite-lag-pipeline.sql | `INSERT OVERWRITE TABLE ... PARTITION(*)` + multi-CTE pipeline |
| 02-if-in-select-list.sql | `IF()` and `array_contains()` in select list / join |
| 03-regexp-function.sql | `REGEXP()` function-call form (Doris) |
| 04-doris-cast-targets.sql | `CAST` / `TRY_CAST` to `STRING` / `LARGEINT` / `ARRAY<>` / `MAP<>` |
| 05-count-star-nested-casts.sql | Nested `CAST(... AS JSON)` / `STRING` inside function args |
| 06-map-bracket-access.sql | `CAST(map['key'] AS string)` bracket access |
| 07-except-cte-chain.sql | `SELECT * EXCEPT(col)` column exclusion in a CTE chain |
| 08-except-set-operator.sql | `EXCEPT` set operator (unmasked; goldens expected equal) |
| 09-insert-into-columns-select-except.sql | `INSERT INTO (cols) SELECT * EXCEPT(col)` |
| 10-insert-overwrite-variants.sql | `INSERT OVERWRITE` variants (`TABLE`, `PARTITION` forms) |
| 11-insert-overwrite-real-insert-psi.sql | `INSERT OVERWRITE` producing real INSERT PSI |
| 12-create-view-modern-body.sql | `CREATE OR REPLACE VIEW` with `EXCEPT` / `REGEXP` / JSON body |
| 13-create-view-lateral-view.sql | `CREATE VIEW ... LATERAL VIEW EXPLODE` |
| 14-create-job.sql | `CREATE JOB ... ON SCHEDULE ... DO INSERT` |
| 15-doris-admin-statements.sql | `REFRESH` / `WARM UP` / `CREATE DATABASE` / `DISTRIBUTED BY` DDL |
| 16-qualify-clause.sql | `QUALIFY` window-filter clause |
| 17-table-function.sql | Table-valued function (`mv_infos(...)`) |
| 18-three-part-catalog-names.sql | `catalog.db.table` three-part names |
| 19-switch-catalog.sql | `SWITCH <catalog>` |
| 20-use-catalog-dot-db.sql | `USE catalog.db` |
| 21-five-statement-boundaries.sql | Five mixed statements (boundary detection) |
| 22-lenient-statement-no-eat-next.sql | Lenient statement must not swallow the next one |
| 23-create-mtmv-partition-expr.sql | `CREATE MATERIALIZED VIEW ... PARTITION BY (date_trunc(...))` |
| 24-create-table-inverted-index-auto-partition.sql | Inline `INDEX ... USING INVERTED` + `AUTO PARTITION BY RANGE` |
| 25-refresh-materialized-view-complete.sql | `REFRESH MATERIALIZED VIEW ... COMPLETE` |
| 26-refresh-mv-partition.sql | `REFRESH MATERIALIZED VIEW ... PARTITION/PARTITIONS/AUTO` |
| 27-use-compute-group.sql | `USE @group` / `USE db@group` compute-group forms |
| 28-create-table-unique-key-sequence.sql | `UNIQUE KEY` + `function_column.sequence_col` + merge-on-write |
| 29-create-table-aggregate-key.sql | `AGGREGATE KEY` with SUM/MAX/MIN/REPLACE/BITMAP_UNION/HLL_UNION |
| 30-create-table-colocate-bloom.sql | `colocate_with` + `bloom_filter_columns` properties |
| 31-create-table-dynamic-partition.sql | `PARTITION BY RANGE` + `dynamic_partition.*` properties |
| 32-create-table-list-partition.sql | `PARTITION BY LIST` with multi-column `VALUES IN` tuples |
| 33-create-table-range-multi-column.sql | Multi-column RANGE, `VALUES [(...), (...))` + `LESS THAN` |
| 34-create-table-like.sql | `CREATE TABLE LIKE` (plain + `WITH ROLLUP`) |
| 35-ctas-variants.sql | CTAS with and without an explicit column list |
| 36-create-table-generated-column.sql | Generated column `c INT AS (a + b)` |
| 37-alter-table-columns.sql | `ALTER TABLE ADD / MODIFY / DROP COLUMN` |
| 38-alter-table-partitions.sql | `ALTER TABLE ADD / DROP / REPLACE PARTITION` (incl. interval form) |
| 39-alter-table-rename-properties-rollup.sql | `RENAME` / `SET ("k"="v")` / `ADD`/`DROP ROLLUP` |
| 40-create-mtmv-refresh-strategies.sql | MTMV `BUILD`/`REFRESH` × `ON SCHEDULE EVERY ... STARTS` / `ON COMMIT` |
| 41-create-mtmv-partition-column-workload.sql | MTMV `PARTITION BY (col)` + `workload_group` property |
| 42-create-job-schedule-variants.sql | `CREATE JOB` `EVERY n MINUTE/DAY [STARTS/ENDS]` + one-time `AT` |
| 43-create-routine-load-kafka.sql | `CREATE ROUTINE LOAD ... FROM KAFKA (...)` property bags |
| 44-export-table.sql | `EXPORT TABLE ... TO "s3://..." WITH s3 (...)` |
| 45-backup-restore.sql | `BACKUP SNAPSHOT ... TO` / `RESTORE SNAPSHOT ... FROM` |
| 46-grant-revoke.sql | `GRANT`/`REVOKE` `*_PRIV` forms, workload-group grant, role grant |
| 47-create-user-role.sql | `CREATE USER [IF NOT EXISTS] ... DEFAULT ROLE` / `CREATE ROLE` |
| 48-create-catalog-property-bags.sql | `CREATE CATALOG` (hms / iceberg / jdbc property bags) |
| 49-create-resource-workload-group.sql | `CREATE RESOURCE` / `CREATE WORKLOAD GROUP` |
| 50-delete-from-partition.sql | `DELETE FROM ... PARTITION (p)` / `PARTITIONS (p1, p2)` / plain |
| 51-update-set.sql | Single-table `UPDATE ... SET ... WHERE` |
| 52-truncate-partition.sql | `TRUNCATE TABLE` plain + `PARTITION (...)` |
| 53-insert-variants.sql | `INSERT` with partition spec + column list / `DEFAULT` / `INSERT ... SELECT` |

## Manifest — `edge/`

Near-misses (01–06) deliberately land **next to** a DorisLexer mask trigger without
firing it; the Doris and MySQL goldens are expected to be equal. Broken-SQL shapes
(07–12) pin the parser's error-recovery trees — those are contracts too, because
completion in half-typed SQL depends on them.

| File | Intent |
|------|--------|
| 01-except-parenthesized-setop.sql | `EXCEPT (SELECT ...)` parenthesized set-op — must stay set-op, not masked |
| 02-except-plain-setop.sql | `EXCEPT SELECT ...` plain set-op — must stay set-op |
| 03-insert-named-partitions.sql | `INSERT ... PARTITION (p1, p2)` named partitions — must NOT be masked |
| 04-regexp-operator-form.sql | `x REGEXP 'pat'` operator form — must stay operator, not re-typed |
| 05-cast-mysql-valid-targets.sql | `CAST(v AS CHAR)` / `CAST(v AS JSON)` — MySQL-valid targets keep typing |
| 06-backtick-reserved-column-names.sql | Backtick-quoted `` `string` `` / `` `except` `` columns |
| 07-dangling-comma.sql | `SELECT a, FROM t` — dangling comma recovery |
| 08-missing-table.sql | `SELECT * FROM ;` — missing table recovery |
| 09-empty-predicate.sql | `SELECT * FROM t WHERE ;` — empty predicate recovery |
| 10-unclosed-paren-no-semicolon.sql | `INSERT INTO t VALUES (` — unclosed paren, no semicolon |
| 11-unclosed-cte-paren.sql | `WITH c AS (SELECT 1 SELECT * FROM c;` — unclosed CTE paren |
| 12-truncated-ddl.sql | `CREATE TABLE t (id INT,` — truncated DDL |

## How to re-record

Re-recording rewrites every `*.tree` under `golden/` from the current parser output,
then passes:

```
./gradlew test --tests 'dev.sort.doris.sql.DorisGoldenCorpusTest' -Pgolden.record=true
```

Default (no flag) is verify mode: each corpus file's tree is asserted equal to its
golden for both dialects; a missing golden is a failure. All mismatches aggregate into a
single failure per dialect, each line naming the relative corpus path.

## The review rule

**Golden diffs are reviewed like code, never re-recorded blindly.** A `git diff` under
`golden/` after a parser or platform change is a change to a contract:

- A diff under **`golden/mysql/`** means the upstream MySQL tree shape moved — re-audit
  our delegation against the new platform before re-recording.
- A diff under **`golden/doris/`** for `mysql-core/` or `edge/` near-miss files is a
  **red flag**: it usually means a mask is over- or under-firing. Investigate the cause;
  do not re-record to make it green.
- Deliberate, understood shape changes (a new shaped mini-rule, an intentional mask) are
  re-recorded *after* the diff is reviewed and the change is the reason for the diff.
