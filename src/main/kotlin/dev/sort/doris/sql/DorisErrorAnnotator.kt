package dev.sort.doris.sql

import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.diagnostics.DiagnosticSeverity
import dev.brikk.house.sql.diagnostics.DiagnosticSource
import dev.brikk.house.sql.diagnostics.SqlDiagnostic
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorProfile
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorPsiFilter
import dev.brikk.house.sql.intellij.diagnostics.VerificationUnit
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.pipe.toNativeVerdict
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.doris.brikk.BrikkDorisContext
import dev.sort.doris.pipes.DorisPipeDialect
import dev.sort.doris.pipes.DorisPipes
import dev.sort.doris.pipes.DorisPipesEngine
import dev.sort.doris.pipes.DorisPipesSettings

internal const val DORIS_PIPE_DIAGNOSTIC_PREFIX = "Doris PIPE:"
internal const val DORIS_SERVER_DIAGNOSTIC_PREFIX = "Doris (server):"

object DorisNativeErrorProfile : NativeErrorProfile {
    override val language get() = DorisSqlDialect.INSTANCE
    override val dialect = "doris"
    override val label = "Doris"
    override val unit = VerificationUnit.File
    private val validators = listOf(false, true).associateWith { brikk ->
        listOf(false, true).associateWith { pipes ->
            object : NativeSqlValidator by DorisNativeValidation.validator {
                override fun validate(sql: String, file: PsiFile?): NativeVerdict = verify(sql, file).toNativeVerdict()
                override fun verify(sql: String, file: PsiFile?): VerifyResult = verifyScript(sql, pipes, brikk)
            }
        }
    }

    override fun validator(file: PsiFile): NativeSqlValidator =
        validators.getValue(BrikkDorisContext.isActive(file)).getValue(DorisPipes.isEditingEnabled(file))

    internal fun verificationText(sql: String, brikk: Boolean): String = if (!brikk) sql else buildString {
        append(sql)
        for (range in DorisPipes.namedParameterRanges(sql)) {
            setCharAt(range.first, '?')
            for (index in range.first + 1..range.last) setCharAt(index, ' ')
        }
    }

    internal fun verifyScript(sql: String, pipes: Boolean, brikk: Boolean): VerifyResult {
        val native = DorisNativeValidation.validator.verify(verificationText(sql, brikk), null)
        if (!pipes || !DorisPipes.containsPipeOperator(sql)) return native
        val ranges = DorisPipeDialect.scriptRanges(sql).filter { it.hasPipe }
        val errors = (native as? VerifyResult.Rejected)?.errors.orEmpty().filterNot { error ->
            val offset = error.offset ?: 0
            ranges.any { offset in it.start until it.end }
        } + DorisPipesEngine.pipeSyntaxErrors(sql).map { error ->
            val start = error.startOffsetIn(sql) ?: 0
            VerifyError.atOffset(sql, error.message, start, (start + error.length).coerceAtMost(sql.length))
        }
        return when {
            errors.isNotEmpty() -> VerifyResult.Rejected(errors.sortedBy { it.offset })
            native is VerifyResult.Unavailable || native is VerifyResult.Unverified -> native
            else -> VerifyResult.Accepted
        }
    }

    override fun fileDiagnostics(file: PsiFile, script: String): List<SqlDiagnostic> {
        if (!DorisPipes.isEditingEnabled(file) || script.isEmpty()) return emptyList()
        val mark = DorisPipes.execMarkFor(file.project, file.viewProvider.virtualFile.url, script) ?: return emptyList()
        val start = mark.start.coerceIn(0, script.lastIndex)
        val end = mark.end.coerceIn(start + 1, script.length)
        return listOf(SqlDiagnostic(DiagnosticSeverity.ERROR, "$DORIS_SERVER_DIAGNOSTIC_PREFIX ${mark.message}",
            start, end, DiagnosticSource.OTHER))
    }

    override fun modificationCount(file: PsiFile): Long = file.project.service<DorisPipesSettings>().let {
        it.stateModificationCount + it.diagnosticRevision.get()
    }

    override fun message(diagnostic: SqlDiagnostic): String = diagnostic.message
}

class DorisErrorAnnotator : NativeErrorAnnotator(DorisNativeErrorProfile)
class DorisNativeErrorPsiFilter : NativeErrorPsiFilter(DorisNativeErrorProfile)
