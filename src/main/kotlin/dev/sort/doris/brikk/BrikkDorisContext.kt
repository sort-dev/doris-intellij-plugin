package dev.sort.doris.brikk

import com.intellij.openapi.util.Key
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiElement

/** Injection metadata only. This class must remain usable without the Kotlin plugin. */
object BrikkDorisContext {
    val PROFILE: Key<Boolean> = Key.create("doris.brikkHouse")
    val UNCERTAIN: Key<Boolean> = Key.create("doris.brikkHouse.uncertainTemplates")

    fun isActive(file: PsiFile?): Boolean {
        if (file == null) return false
        return owner(file).getUserData(PROFILE) == true
    }

    fun hasUncertainTemplates(file: PsiFile): Boolean = owner(file).getUserData(UNCERTAIN) == true

    private fun owner(file: PsiFile): PsiElement =
        InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: file
}
