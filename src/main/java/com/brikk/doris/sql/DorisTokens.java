package com.brikk.doris.sql;

/**
 * Marker interface combining reserved and optional keyword sets.
 * Used by SqlLanguageDialectBase.createTokensHelper() for keyword classification.
 */
public interface DorisTokens extends DorisReservedKeywords, DorisOptionalKeywords {
}
