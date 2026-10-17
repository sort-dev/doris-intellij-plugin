package dev.sort.doris.pipes

import com.intellij.database.console.session.DatabaseSession
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.pipe.FirstStageRule
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.PipeDialect
import dev.brikk.house.sql.intellij.pipe.PipeSubmitListener
import dev.brikk.house.sql.intellij.pipe.ValidationTiming
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.sort.doris.DorisDbms
import dev.sort.doris.brikk.DorisHostSqlChannel
import dev.sort.doris.brikk.BrikkDorisContext
import dev.sort.doris.sql.DorisSqlDialect
import dev.sort.doris.sql.DorisNativeValidation

object DorisPipeDialect : PipeDialect {
    override val dialect = "doris"
    override val label = "Doris PIPE"
    override val notificationGroup = "Doris Pipes"
    override val vetoProperty = DorisPipes.PROPERTY
    override val language get() = DorisSqlDialect.INSTANCE
    override val firstStage = FirstStageRule.AnyQuery
    override val serverFirstColumn = 0
    override val submitListener: PipeSubmitListener get() = DorisPipesExecution
    override val hostSql get() = DorisHostSqlChannel

    override fun isProjectEnabled(project: Project) = project.service<DorisPipesSettings>().enabled
    // Initial injected parsing precedes the channel's file marker; retain the existing editing flag.
    override fun enablesEditing(file: PsiFile) = BrikkDorisContext.isActive(file)
    override fun enablesPreview(file: PsiFile) = !isInspectionHost(file)
    override fun ownsSession(session: DatabaseSession) = session.connectionPoint.dbms === DorisDbms.DORIS

    override fun scriptRanges(text: String): List<SqlStatementRange> {
        ProgressManager.checkCanceled()
        return SqlScriptSnapshot.parse(text, dialect, ProgressManager::checkCanceled).statements
    }

    override val validator = object : NativeSqlValidator {
        override val timing = ValidationTiming.Lowering
        override fun validate(sql: String, file: PsiFile?): NativeVerdict =
            DorisNativeValidation.verdict(DorisNativeValidation.verify(sql))
    }
}
