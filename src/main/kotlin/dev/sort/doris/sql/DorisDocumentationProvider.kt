package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.docs.SqlEngineDocumentationProvider
import dev.brikk.house.sql.metadata.DORIS_METADATA

/** Quick and external documentation of Doris statements, clauses and table functions, linking the Doris pages. */
class DorisDocumentationProvider : SqlEngineDocumentationProvider(DORIS_METADATA)
