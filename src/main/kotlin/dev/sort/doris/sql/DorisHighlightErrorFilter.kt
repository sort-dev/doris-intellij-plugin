package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.editor.SubstrateErrorFilter

class DorisHighlightErrorFilter : SubstrateErrorFilter(DorisSqlDialect.INSTANCE)
