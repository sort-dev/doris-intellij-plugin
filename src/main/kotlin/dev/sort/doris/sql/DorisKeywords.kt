package dev.sort.doris.sql

internal val DORIS_KEYWORDS: Set<String> =
    (DorisReservedKeywords.RESERVED_KEYWORDS + DorisOptionalKeywords.OPTIONAL_KEYWORDS)
        .mapTo(HashSet()) { it.uppercase() }
