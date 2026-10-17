package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.editor.MaskedSpanRecolorAnnotator

class DorisMaskedSpanRecolorAnnotator : MaskedSpanRecolorAnnotator(DorisSqlDialect.INSTANCE, "doris", DORIS_KEYWORDS)
