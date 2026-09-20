# Vendored dependencies

## `lib/doris-fe-sql-parser-4.1.4-gad35a140c7fd.jar`

The plugin uses Apache Doris's native ANTLR grammar for diagnostics, CST replay,
and validation of generated PIPE SQL. Doris does not publish this standalone
parser to Maven Central.

This artifact uses the exact Doris 4.1.4 release grammar with a separately pinned
standalone facade/build module, because the release branch has no `fe-sql-parser`
module. It is the same artifact committed by brikk-house in
`1d6161621b6f8ad7fdb1c6344a55f64474387b41` for its native oracle.

| Component | Pin |
|---|---|
| Source | https://github.com/apache/doris |
| Grammar | Tag `4.1.4`, commit `ad35a140c7fd0b842f18c23300bac581f7d04326` |
| Facade/build module | `2a58ad96ac3ca6330a6df3d0b90f437351f63701` |
| Internal Maven version | `1.2-SNAPSHOT`, not a published release coordinate |
| Refreshed | 2026-09-20 |
| License | Apache License 2.0, see `../THIRD_PARTY_NOTICES.md` |
| Runtime dependency | `org.antlr:antlr4-runtime:4.13.1` |
| Artifact SHA-256 | `358ecccb39e84c65e62dc72b4090191c445fad4be1fb64e79ddaf68787d8703a` |

### Rebuilding

Use Maven 3.9.16 and JDK 17 or later in a disposable source checkout:

```bash
git clone --filter=blob:none --no-checkout --depth 1 \
  --revision 2a58ad96ac3ca6330a6df3d0b90f437351f63701 \
  https://github.com/apache/doris.git /tmp/opencode/doris-parser-build
git -C /tmp/opencode/doris-parser-build sparse-checkout set fe/fe-sql-parser
git -C /tmp/opencode/doris-parser-build checkout --detach 2a58ad96ac3ca6330a6df3d0b90f437351f63701
git -C /tmp/opencode/doris-parser-build fetch --depth 1 origin ad35a140c7fd0b842f18c23300bac581f7d04326
bash vendor/build-parser.sh /tmp/opencode/doris-parser-build
```

The script replaces the two grammar files with the exact release-tag versions.
It permits the release grammar's ANTLR warnings about implicit GET_FORMAT and
nullable warmUpSingleTableRef, matching the release's fe-core build. It does not
modify the grammar or Java facade sources.
