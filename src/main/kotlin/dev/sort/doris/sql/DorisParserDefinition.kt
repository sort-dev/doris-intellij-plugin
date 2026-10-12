package dev.sort.doris.sql

import com.intellij.lang.PsiParser
import com.intellij.lang.ParserDefinition
import com.intellij.psi.PsiElement
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType
import com.intellij.sql.psi.impl.lexer.SqlPreprocessingLexer

/**
 * DorisSQL parsing on a MySQL foundation: MySQL lexer + element factory (so modern SQL parses),
 * plus a lenient [DorisPsiParser] (a MysqlParser subclass) that keeps statement boundaries correct
 * for Doris-specific statements MySQL can't parse (INSERT OVERWRITE, DISTRIBUTED BY, EXCEPT(...),
 * ...). See [DorisSqlDialect] for why MySQL rather than SQL92. Mirrors the StarRocks plugin.
 */
class DorisParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = DorisElementFactory()
    // DorisLexer wraps MysqlLexer to mask `* EXCEPT(cols)` before the generated grammar mis-reads
    // EXCEPT as a set operator. NB this lexer also drives editor syntax coloring (the platform's
    // SqlSyntaxHighlighter takes its lexer from this ParserDefinition), so masked spans lex as
    // comments in the editor too — DorisMaskedSpanRecolorAnnotator paints them back.
    override fun createLexer(project: Project): Lexer = DorisLexer()
    override fun createParser(project: Project): PsiParser = DorisPsiParser()
    override fun getFileNodeType(): IFileElementType = FILE

    private companion object {
        private val FILE = object : SqlFileElementType("DORIS_SQL_FILE", DorisSqlDialect.INSTANCE) {
            override fun createLexer(definition: ParserDefinition, psi: PsiElement, host: PsiElement?): Lexer {
                if (host?.getUserData(dev.sort.doris.brikk.BrikkDorisContext.PROFILE) != true &&
                    !dev.sort.doris.brikk.BrikkDorisContext.isActive(psi.containingFile)) {
                    return super.createLexer(definition, psi, host)
                }
                val scoped = object : ParserDefinition by definition {
                    override fun createLexer(project: Project): Lexer = DorisLexer(brikkHouse = true)
                }
                val hostLanguage = host?.let { SqlPreprocessingLexer.getHostLanguage(it) }
                    ?: psi.getUserData(HOST_LANGUAGE)
                // Retain the platform's Kotlin-escape and user-parameter preprocessing.
                return SqlPreprocessingLexer.withPreprocessingIfNeeded(psi.project, scoped, hostLanguage)
            }
        }
    }
}
