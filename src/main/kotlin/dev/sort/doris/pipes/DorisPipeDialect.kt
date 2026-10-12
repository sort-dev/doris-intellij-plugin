package dev.sort.doris.pipes

import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.SqlParameterSpan
import dev.brikk.house.sql.intellij.pipe.FirstStageRule
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeSubmitListener
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.sort.doris.DorisDbms
import dev.sort.doris.brikk.BrikkDorisContext
import dev.sort.doris.sql.DorisSqlDialect
import org.apache.doris.nereids.exceptions.ParseException as DorisParseException
import org.apache.doris.sqlparser.DorisSqlParser

object DorisPipeDialect : PipeDialect {
    override val dialect = "doris"
    override val label = "Doris Pipes"
    override val vetoProperty = DorisPipes.PROPERTY
    override val language get() = DorisSqlDialect.INSTANCE
    override val firstStage = FirstStageRule.FromOnly
    override val serverFirstColumn = 0
    override val submitListener: PipeSubmitListener get() = DorisPipesExecution

    override fun isProjectEnabled(project: Project) = project.service<DorisPipesSettings>().enabled
    override fun enablesEditing(file: PsiFile) = BrikkDorisContext.isActive(file)
    override fun isInspectionHost(file: PsiFile) = BrikkDorisContext.isActive(file)
    override fun enablesPreview(file: PsiFile) = !isInspectionHost(file)
    override fun ownsSession(session: DatabaseSession) = session.connectionPoint.dbms === DorisDbms.DORIS

    override fun scriptRanges(text: String) = DorisPipes.chunks(text).map {
        SqlStatementRange(it.startOffset, it.endOffset, it.hasSql, it.hasPipeOperator, it.boundaryError)
    }

    override fun parameterSpans(sql: String) = DorisPipes.namedParameterRanges(sql).map {
        SqlParameterSpan(sql.substring(it.first + 1, it.last + 1), it.first, it.last + 1)
    }

    override fun maskParameters(sql: String): String {
        val ranges = DorisPipes.namedParameterRanges(sql)
        if (ranges.isEmpty()) return sql
        return buildString(sql.length) {
            append(sql)
            for (range in ranges) {
                setCharAt(range.first, '0')
                for (index in range.first + 1..range.last) setCharAt(index, ' ')
            }
        }
    }

    override val validator = object : NativeSqlValidator {
        override val timing = ValidationTiming.Lowering
        private val parser = DorisSqlParser()

        override fun validate(sql: String, file: PsiFile?): NativeVerdict = try {
            parser.parseStatement(sql)
            NativeVerdict.Accepted
        } catch (failure: DorisParseException) {
            NativeVerdict.Rejected(failure.message ?: "Generated Doris SQL failed validation.")
        }
    }
}
