package dev.sort.doris.sql

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.IFileElementType
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.base.SqlParserDefinitionBase
import com.intellij.sql.dialects.mysql.MysqlElementFactory
import com.intellij.sql.psi.stubs.elementTypes.SqlFileElementType

/**
 * DorisSQL parsing on a MySQL foundation: MySQL lexer + element factory (so modern SQL parses),
 * plus a lenient [DorisPsiParser] (a MysqlParser subclass) that keeps statement boundaries correct
 * for Doris-specific statements MySQL can't parse (INSERT OVERWRITE, DISTRIBUTED BY, EXCEPT(...),
 * ...). See [DorisSqlDialect] for why MySQL rather than SQL92. Mirrors the StarRocks plugin.
 */
class DorisParserDefinition : SqlParserDefinitionBase() {
    override fun createElementFactory(): SqlElementFactoryBase = MysqlElementFactory()
    // DorisLexer wraps MysqlLexer to mask `* EXCEPT(cols)` before the generated grammar mis-reads
    // EXCEPT as a set operator. Parser-only; the editor highlighter uses its own MysqlLexer.
    override fun createLexer(project: Project): Lexer = DorisLexer()
    override fun createParser(project: Project): PsiParser = DorisPsiParser()
    override fun getFileNodeType(): IFileElementType = FILE

    private companion object {
        private val FILE = SqlFileElementType("DORIS_SQL_FILE", DorisSqlDialect.INSTANCE)
    }
}
