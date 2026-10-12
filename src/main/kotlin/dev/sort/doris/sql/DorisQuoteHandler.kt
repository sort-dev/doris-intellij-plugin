package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.editor.SqlDialectQuoteHandler

class DorisQuoteHandler : SqlDialectQuoteHandler(EnterInString.Newline)
