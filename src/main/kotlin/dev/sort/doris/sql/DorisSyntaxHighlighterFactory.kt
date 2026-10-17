package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.editor.KeywordOverlayHighlighterFactory

class DorisSyntaxHighlighterFactory : KeywordOverlayHighlighterFactory(DorisSqlDialect.INSTANCE, DORIS_KEYWORDS)
