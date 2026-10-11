package dev.sort.doris.pipes

import com.intellij.database.settings.DatabaseSettings
import com.intellij.openapi.editor.Editor
import org.antlr.v4.runtime.Token
import org.apache.doris.nereids.DorisLexer
import org.apache.doris.sqlparser.DorisSqlParser

/** Ownership precheck only. The platform still selects the actual statements and prompts. */
internal fun pipeExecutionCandidate(editor: Editor, option: DatabaseSettings.ExecOption): String? {
    val text = editor.document.text
    val caret = editor.caretModel.offset
    val chunks = DorisPipes.chunks(text)
    val current = DorisPipes.chunkAt(text, caret)
    val selected = editor.selectionModel.selectedText
    if (selected != null) {
        if (option.execSelection != DatabaseSettings.EXECUTE_SELECTION_SMART_EXPAND) return selected
        val expanded = chunks.filter { it.startOffset < editor.selectionModel.selectionEnd &&
            it.endOffset > editor.selectionModel.selectionStart }
        return if (expanded.isEmpty()) selected else text.substring(expanded.first().startOffset, expanded.last().endOffset)
    }
    val tokens = current?.takeIf { it.boundaryError == null }?.let {
        DorisSqlParser().newLexer(it.text).allTokens.filter { token ->
            token.channel == Token.DEFAULT_CHANNEL && token.type != DorisLexer.SEMICOLON
        }
    }
    val tokenStart = tokens?.firstOrNull()?.let { current.startOffset + current.text.offsetByCodePoints(0, it.startIndex) }
    val tokenEnd = tokens?.lastOrNull()?.let { current.startOffset + current.text.offsetByCodePoints(0, it.stopIndex + 1) }
    val outside = current == null || !current.hasSql || tokens != null &&
        (tokenStart == null || caret < tokenStart || caret > tokenEnd!!)
    return if (outside) when (option.execOutside) {
        DatabaseSettings.EXECUTE_OUTSIDE_WHOLE_SCRIPT -> text
        DatabaseSettings.EXECUTE_OUTSIDE_SCRIPT_TAIL -> {
            // Keep a leading comment intact; slicing it at the caret invents broken SQL.
            val start = if (tokenStart != null && caret <= tokenStart) current.startOffset
                else chunks.firstOrNull { it.hasSql && it.startOffset >= caret }?.startOffset ?: text.length
            text.substring(start)
        }
        else -> null
    } else when (option.execInside) {
        DatabaseSettings.EXECUTE_INSIDE_WHOLE_SCRIPT,
        DatabaseSettings.EXECUTE_INSIDE_SHOW_CHOOSER,
        DatabaseSettings.EXECUTE_INSIDE_BATCH -> text
        DatabaseSettings.EXECUTE_INSIDE_SCRIPT_TAIL -> text.substring(current.startOffset)
        else -> current.text
    }
}
