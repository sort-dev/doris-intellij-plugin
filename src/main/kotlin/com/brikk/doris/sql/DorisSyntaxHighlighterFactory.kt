package com.brikk.doris.sql

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.sql.dialects.base.SqlSyntaxHighlighterFactory

/**
 * Uses the platform SQL highlighter for the DorisSQL dialect, then wraps it so Doris-only
 * keywords (which the SQL92 lexer does not recognize) are colored too. Mirrors the
 * tarantool-idea-plugin base, plus the Doris keyword layer.
 */
class DorisSyntaxHighlighterFactory : SqlSyntaxHighlighterFactory.Base(DorisSqlDialect.INSTANCE) {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter {
        return DorisKeywordHighlighter(super.getSyntaxHighlighter(project, virtualFile))
    }
}
