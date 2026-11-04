# Refresh offline Chinese Doris function documentation

The plugin reads packaged HTML and an index. Runtime documentation does not access HTTP.
The generator is a developer tool; Markdown dependencies are not shipped in the plugin.

1. Install the pinned generation dependency with `python3 -m pip install -r scripts/requirements-doris-docs.txt` in your development environment.
2. Check out Apache's official website repository at the source commit in the packaged manifest.
3. Sparse checkout `i18n/zh-CN/docusaurus-plugin-content-docs/version-4.x/sql-manual/sql-functions`
   and `i18n/zh-CN/docusaurus-plugin-content-docs/version-4.x/sql-manual/basic-element/sql-data-types/conversion`.
4. Run `python3 scripts/import_doris_function_docs.py /path/to/doris-website --catalog scripts/data/doris-function-names.json` from the plugin repository.
5. Run `python3 -m unittest discover -s scripts/tests -v` and the Gradle documentation tests.
6. Review the generated manifest: imported pages, alias names, runtime catalog coverage, metadata-only names and source provenance.

`scripts/data/doris-function-names.json` is a name/alias/kind snapshot of the bundled
`GeneratedDorisFunctionCatalogKt.DORIS_FUNCTION_CATALOG` in brikk-sql-metadata 0.20.0.
Update it when that dependency changes. The Kotlin corpus test compares coverage with
the actual runtime metadata, preventing unnoticed drift in the reported coverage.

The generator adapts Apache-2.0 source pages to restricted HTML, preserves code/tables,
expands links, localizes English section labels and the remaining BIT_LENGTH prose,
removes network images/scripts and resolves explicit aliases and short variant pages.
Draft pages are excluded. Undocumented metadata names keep a clearly labeled fallback;
no sample query or undocumented behavior is invented to claim complete coverage.
