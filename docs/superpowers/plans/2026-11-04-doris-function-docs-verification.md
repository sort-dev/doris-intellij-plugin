# Doris function documentation repair

Root cause: the registered shared SqlEngineDocumentationProvider handles statements,
properties and table functions, not scalar functions. DocumentationManager returned
no function documentation for DATE_TRUNC and BITMAP_COUNT before the repair.
Both new regression cases failed before implementation.

Implementation: register a separate Doris-only documentation provider for unqualified
built-in call names using bundled FunctionCatalog metadata. Supply synthetic PSI targets
for the IDE documentation pipeline; render escaped metadata overloads offline. DATE_TRUNC
has a bundled Chinese page with argument descriptions, units, NULL/error behavior,
both valid argument orders, example results and the official Doris 4.x link:
https://doris.apache.org/docs/4.x/sql-manual/sql-functions/scalar-functions/date-time-functions/date-trunc/
Existing shared documentation remains registered for statements/properties/TVFs.
Qualified UDFs, unknown functions and same-named columns retain platform behavior.

Validation: 64 tests passed on DataGrip 262 runtime, including 9 documentation cases,
modern IdeDocumentationTargetProvider targeting, lowercase/nested calls, negative column
and UDF cases, existing statement/property contracts, Chinese function checks and DDL
regressions. buildPlugin passed. Independent review found no actionable defects.
Code commit: d3e610ea8dff. Package: 1.6.4.3-dev.202611040511+d3e610ea8dff.

Live UI verification: installed 1.6.4.3 from disk and restarted DataGrip. Confirmed
installed JAR version. Ctrl+J with the caret inside DATE_TRUNC in the existing Doris
console displayed the full Chinese documentation with both signatures, parameters,
units, return/NULL behavior, examples and official link. Settings > Editor > Code Editing
had Show quick documentation on hover enabled already; no setting was changed.
The modern documentation target used by hover is regression-tested, but an automatic
popup from mouse movement alone was not directly observed (the available UI API exposes
click/drag but no move-only operation). No SQL was executed.
