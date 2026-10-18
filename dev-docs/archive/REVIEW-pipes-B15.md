# B15 catalog-aware definition retrieval

Date: 2026-09-09

Status: **fixed; verification passed**.

Definition retrieval now emits stateless, fully qualified SQL:

- Catalog: `SHOW CREATE CATALOG catalog`
- Database: `SHOW CREATE DATABASE catalog.database`
- Internal table/view: catalog-qualified `SHOW CREATE TABLE` or `SHOW CREATE VIEW`
- External table-backed view: catalog-qualified `SHOW CREATE TABLE`

Every identifier component is quoted independently. Identically named objects in
different catalogs therefore produce distinct requests. No path issues `SWITCH`, `USE`,
`setCatalog` or `setSchema`; missing catalog identity in the reused catalog model fails
closed. The flat MySQL model retains `schema.object` forms.

## Verification

- Combined full DB-261.24374.56 suite: 414/414 passed in each companion mode.
- Combined focused B15/B19 tests on DB-262.10315.132: 7/7 passed in each companion mode.
- Generated catalog/database/table/view statements pass the bundled Doris parser.
- `verifyEmbeddedPipes` passed.
- Plugin Verifier reports DB-261.24374.56 and IU-262.8665.81 Compatible.

The verifier retained the existing API notices. No live database operation, connection
state mutation or `SHOW CREATE` request was performed.
