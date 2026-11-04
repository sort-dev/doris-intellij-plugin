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

Review: independent source review found no actionable defects. No live UI success has yet
been claimed; install the packaged plugin and validate the physical table DDL in DataGrip.
