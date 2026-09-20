#!/usr/bin/env bash
# Build the upstream standalone facade with the exact Doris 4.1.4 grammar.
# Adapted from brikk-house/tools/build_doris_parser.sh, commit 1d6161621b6f.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
SOURCE=${1:?Usage: bash vendor/build-parser.sh /path/to/disposable-doris-checkout}
WRAPPER_SHA=2a58ad96ac3ca6330a6df3d0b90f437351f63701
GRAMMAR_SHA=ad35a140c7fd0b842f18c23300bac581f7d04326
MODULE="$SOURCE/fe/fe-sql-parser"

test "$(git -C "$SOURCE" rev-parse HEAD)" = "$WRAPPER_SHA"
for file in DorisLexer.g4 DorisParser.g4; do
    git -C "$SOURCE" show "$GRAMMAR_SHA:fe/fe-core/src/main/antlr4/org/apache/doris/nereids/$file" \
        > "$MODULE/src/main/antlr4/org/apache/doris/nereids/$file"
done
# Permit warnings as the release's fe-core build does, without changing the grammar.
python3 - "$MODULE/pom.xml" <<'PY'
import pathlib
import sys
pom = pathlib.Path(sys.argv[1])
pom.write_text(pom.read_text().replace(
    "<treatWarningsAsErrors>true</treatWarningsAsErrors>",
    "<treatWarningsAsErrors>false</treatWarningsAsErrors>",
))
PY
mvn -B -ntp -f "$MODULE/pom.xml" -Pflatten clean package -Dmaven.test.skip=true -Dcheckstyle.skip
cp "$MODULE/target/doris-fe-sql-parser.jar" "$ROOT/vendor/lib/doris-fe-sql-parser-4.1.4-gad35a140c7fd.jar"
