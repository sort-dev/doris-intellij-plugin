package dev.sort.doris.sql

import com.intellij.lexer.Lexer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.sql.dialects.mysql.MysqlLexer
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.lexer.CastTargetMask
import dev.brikk.house.sql.intellij.lexer.MaskingSqlLexer
import dev.sort.doris.pipes.DorisPipes

class DorisLexer(private val brikkHouse: Boolean = false) : MaskingSqlLexer(
    MysqlLexer(),
    CastTargetMask(setOf("CAST", "TRY_CAST", "CONVERT"), MYSQL_CAST_TYPES, CastTargetMask.Extent.TargetType),
) {
    private var bindEnds: Map<Int, Int> = emptyMap()

    override fun onStart(buffer: CharSequence, startOffset: Int, endOffset: Int) {
        bindEnds = if (brikkHouse) DorisPipes.namedParameterRanges(buffer.toString())
            .associate { it.first to it.last + 1 } else emptyMap()
    }

    override fun rule(base: Lexer, upper: String): Boolean {
        when {
            brikkHouse && base.tokenStart in bindEnds -> {
                val start = base.tokenStart
                val end = bindEnds.getValue(start)
                note(base.bufferSequence.subSequence(start, end).toString().uppercase())
                while (base.tokenType != null && base.tokenEnd <= end) base.advance()
                addToken(start + 1, SqlTokens.SQL_EXTERNAL_PARAM_PREFIX)
                addToken(end, SqlTokens.SQL_IDENT)
            }
            upper == "EXCEPT" && isColumnExclusion(base) -> maskWithGroup(base)
            upper == "INSERT" && wordsFollow(base, "OVERWRITE") -> maskInsertOverwriteHeader(base)
            upper == "PARTITION" && isPartitionStar(base) -> maskWithGroup(base)
            upper == "TEMPORARY" && statementHead in TEMPORARY_PARTITION_STATEMENT_LEADS &&
                wordsFollow(base, "PARTITION") -> maskWords(base, 1)
            upper == "REGEXP" && nextIs(base, "(") -> emitAs(base, SqlTokens.SQL_IDENT)
            base.tokenType == SqlTokens.SQL_IDENT_DELIMITED &&
                base.bufferSequence[base.tokenStart] == '"' -> emitDoubleQuotedString(base)
            else -> return false
        }
        return true
    }

    private fun emitDoubleQuotedString(base: Lexer) {
        val start = base.tokenStart
        val end = base.tokenEnd
        val seq = base.bufferSequence
        if (end - start < 2 || seq[end - 1] != '"' || StringUtil.indexOf(seq, '\'', start, end) < 0) {
            emitAs(base, SqlTokens.SQL_STRING_TOKEN)
            return
        }
        note(base.tokenText.uppercase())
        addToken(start + 1, SqlTokens.SQL_CUSTOM_LQUOTE)
        if (end - 1 > start + 1) addToken(end - 1, SqlTokens.SQL_CUSTOM_QUOTED_STRING_TOKEN)
        addToken(end, SqlTokens.SQL_CUSTOM_RQUOTE)
        base.advance()
    }

    private fun maskInsertOverwriteHeader(base: Lexer) {
        emit(base)
        while (base.tokenType != null && base.tokenText.isBlank()) advanceLexer(base)
        maskWords(base, 1)
        while (base.tokenType != null && base.tokenText.isBlank()) advanceLexer(base)
        if (base.tokenType != null && base.tokenText.equals("TABLE", ignoreCase = true)) maskWords(base, 1)
    }

    private fun isPartitionStar(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = offsetAfterWhitespace(base, base.tokenEnd)
        if (seq.getOrNull(i) != '(') return false
        i = offsetAfterWhitespace(base, i + 1)
        if (seq.getOrNull(i) != '*') return false
        i = offsetAfterWhitespace(base, i + 1)
        return seq.getOrNull(i) == ')'
    }

    private fun isColumnExclusion(base: Lexer): Boolean {
        val seq = base.bufferSequence
        var i = offsetAfterWhitespace(base, base.tokenEnd)
        if (seq.getOrNull(i) != '(') return false
        i = offsetAfterWhitespace(base, i + 1)
        val c = seq.getOrNull(i) ?: return false
        if (!(c.isLetter() || c == '_' || c == '`')) return false
        return bufferWord(base, i).uppercase() !in setOf("SELECT", "WITH", "VALUES", "TABLE")
    }

    private companion object {
        val MYSQL_CAST_TYPES = setOf(
            "BINARY", "CHAR", "NCHAR", "NATIONAL", "DATE", "DATETIME", "TIME", "YEAR",
            "DECIMAL", "DEC", "DOUBLE", "FLOAT", "REAL", "SIGNED", "UNSIGNED", "JSON",
        )
        val TEMPORARY_PARTITION_STATEMENT_LEADS = setOf("INSERT", "SELECT", "WITH")
    }
}
