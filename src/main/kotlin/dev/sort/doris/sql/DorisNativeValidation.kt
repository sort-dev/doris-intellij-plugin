package dev.sort.doris.sql

import com.intellij.openapi.progress.ProgressManager
import dev.brikk.house.sql.intellij.pipe.NativeVerdict
import dev.brikk.house.sql.intellij.recoverEditorOperation
import dev.brikk.house.sql.verify.DorisVerifier
import dev.brikk.house.sql.verify.VerifyResult

internal object DorisNativeValidation {
    private val verifier by lazy { recoverEditorOperation { DorisVerifier.create() } }

    fun verify(text: String, checkCanceled: () -> Unit = ProgressManager::checkCanceled): VerifyResult {
        checkCanceled()
        val parser = verifier.getOrElse { return VerifyResult.Unavailable(it.message ?: "Doris verifier unavailable") }
        return recoverEditorOperation { parser.verifyScript(text, checkCanceled) }
            .getOrElse { VerifyResult.Unavailable(it.message ?: "Doris verification failed") }
    }

    fun verdict(result: VerifyResult): NativeVerdict = when (result) {
        VerifyResult.Accepted -> NativeVerdict.Accepted
        is VerifyResult.Rejected -> NativeVerdict.Rejected(result.errors.joinToString("\n") { it.message })
        is VerifyResult.Unverified, is VerifyResult.Unavailable -> NativeVerdict.Unavailable
    }

    fun errors(text: String, result: VerifyResult): List<DorisSyntaxError> =
        (result as? VerifyResult.Rejected)?.errors.orEmpty().map { error ->
            val start = error.offset ?: 0
            val lineStart = text.lastIndexOf('\n', start - 1) + 1
            DorisSyntaxError(1 + text.take(start).count { it == '\n' }, text.codePointCount(lineStart, start),
                ((error.endOffset ?: start + 1) - start).coerceAtLeast(1), error.message)
        }
}
