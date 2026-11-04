# Chinese Doris function inspection repair

Root cause: DorisHighlightInfoFilter matched only the English unknown-function prefix.
The installed DataGrip Chinese SqlBundle uses `未知数据库函数 ''{0}''` for the same
`unknown.function` key. The function metadata and completion were already available.

Regression: new filter test failed on the Chinese DATE_TRUNC false positive before the fix.
The filter now recognizes English and Chinese unknown-function messages, while retaining
the existing Doris-only language guard and known-function allowlist.

Validation: 55 tests passed on DataGrip 262 runtime: all DorisHighlightingSuppressionTest
cases plus the 18 DDL regression tests. Chinese misspelled functions remain flagged;
unrelated argument diagnostics and MySQL files are not suppressed. buildPlugin passed.
Code commit: ce3191085100. Package: 1.6.4.2-dev.202611040500+ce3191085100.

Live Chinese UI validation: installed 1.6.4.2 from disk and restarted DataGrip.
Confirmed installed plugin JAR version. In the restored Chinese console, DATE_TRUNC
now has normal blue function highlighting with no red error, while the unrelated
invalid `select date;` still retains its error. No SQL was executed for this validation.
