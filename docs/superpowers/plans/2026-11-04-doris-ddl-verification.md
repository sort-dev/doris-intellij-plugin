# Doris table DDL repair verification

Runtime: DataGrip 262.11879.103, original plugin 1.6.4, custom version 1.6.4.1.

The regression tests first failed because affectedSchemas was empty and table detailsLevel
was null. After the fix, 18 focused tests pass, including the platform documentation DDL
helper, XML cache serialization, model replacement, cancellation, and source-read errors.

Full suite: 660 tests, 9 failures. All failures below reproduce unchanged on baseline 1.6.4:

- dev.sort.doris.sql.DorisTableFunctionsTest.testAutopopupConfidenceEditorEntryPointDelegates
- dev.sort.doris.sql.DorisTableFunctionsTest.testAutopopupNotSkippedInsideDoubleQuotedTvfProperty
- dev.sort.doris.sql.DorisTableFunctionsTest.testAutopopupNotSkippedInsideTvfPropertyString
- dev.sort.doris.sql.DorisTableFunctionsTest.testCompletionOffersEnumValues
- dev.sort.doris.sql.DorisTableFunctionsTest.testCompletionOffersEnumValuesInSingleQuotes
- dev.sort.doris.sql.DorisTableFunctionsTest.testCompletionOffersNextKeyAfterCommaInSingleQuotes
- dev.sort.doris.sql.DorisTableFunctionsTest.testCompletionOffersPropertyKeys
- dev.sort.doris.sql.DorisTableFunctionsTest.testCompletionOffersPropertyKeysInsideSingleQuotes
- dev.sort.doris.sql.DorisTableFunctionsTest.testVectorSearchOpenRelationAndPropertyCompletion

Offline verifier: no new compatibility problems and no new internal or experimental API
usage versus baseline. Both have 19 existing compatibility findings, including unresolved
platform/optional dependency classes. Do not describe this as a completely clean verifier run.
Only the installed 262 runtime was checked. Tests use test-framework 262.10968.63 because
JetBrains does not publish test-framework 262.11879.103 at the configured repositories.

Review: independent source review found no actionable defects.

Live UI validation (2026-11-04): installed custom plugin
1.6.4.1-dev.202611040432+7aa6c3a8e7df through Install Plugin from Disk and restarted
DataGrip. Verified the installed JAR version. Changed the target Doris data source's
Options > Introspection > Default level from Auto select to Level 3: Everything.
The source stage then logged a successful original definition read for dim_store.
After the queued introspections finished, reopened dim_store with Go to Declaration
from the existing console. The DDL editor displayed the complete CREATE TABLE,
including ENGINE=OLAP, UNIQUE KEY, DISTRIBUTED BY HASH, and PROPERTIES. The low-level
introspection warning was absent. This validation covers the table DDL editor;
function hover documentation was outside this install/validation request.
