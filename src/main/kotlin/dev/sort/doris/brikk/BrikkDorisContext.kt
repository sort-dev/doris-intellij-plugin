package dev.sort.doris.brikk

import com.intellij.openapi.util.Key
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiElement
import dev.brikk.house.intellij.ContextResult

/** Injection metadata only. This class must remain usable without the Kotlin plugin. */
object BrikkDorisContext {
    val PROFILE: Key<Boolean> = Key.create("doris.brikkHouse")
    val UNCERTAIN: Key<Boolean> = Key.create("doris.brikkHouse.uncertainTemplates")
    val RELATIONS: Key<RelationProvider> = Key.create("doris.brikkHouse.relations")

    /** Copied helper contracts only. Implemented by the optional Kotlin adapter. */
    fun interface RelationProvider { fun read(): ContextResult }

    fun relations(file: PsiFile): ContextResult? =
        if (isActive(file)) owner(file).getUserData(RELATIONS)?.read() else null

    fun isActive(file: PsiFile?): Boolean {
        if (file == null) return false
        return owner(file).getUserData(PROFILE) == true
    }

    fun hasUncertainTemplates(file: PsiFile): Boolean = owner(file).getUserData(UNCERTAIN) == true

    private fun owner(file: PsiFile): PsiElement =
        InjectedLanguageManager.getInstance(file.project).getInjectionHost(file) ?: file
}
