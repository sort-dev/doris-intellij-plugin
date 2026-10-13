package dev.sort.doris.sql

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import dev.sort.doris.pipes.DorisPipes

internal fun withoutPipeChunkErrors(text: String, errors: List<DorisSyntaxError>): List<DorisSyntaxError> {
    val chunks = DorisPipes.chunks(text).filter { it.hasPipeOperator }
    return errors.filterNot { error ->
        val offset = error.startOffsetIn(text)
        offset != null && chunks.any { offset in it.startOffset until it.endOffset }
    }
}

data class DorisSyntaxError(val line: Int, val col: Int, val length: Int, val message: String) {
    fun startOffsetIn(text: String): Int? {
        if (line < 1 || col < 0) return null
        var lineStart = 0
        repeat(line - 1) {
            lineStart = text.indexOf('\n', lineStart).takeIf { it >= 0 }?.plus(1) ?: return null
        }
        val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 } ?: text.length
        val contentEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
        return text.offsetByCodePoints(lineStart, col.coerceAtMost(text.codePointCount(lineStart, contentEnd)))
    }

    fun toTextRange(document: Document): TextRange? {
        val start = startOffsetIn(document.text) ?: return null
        val end = (start + length).coerceIn(start, document.getLineEndOffset(line - 1))
        return if (end > start) TextRange(start, end) else TextRange(start, (start + 1).coerceAtMost(document.textLength))
    }
}
