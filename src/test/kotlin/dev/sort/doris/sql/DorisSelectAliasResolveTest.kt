package dev.sort.doris.sql

import com.intellij.lang.Language
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlGroupByClause
import com.intellij.sql.psi.SqlHavingClause
import com.intellij.sql.psi.SqlOrderByClause
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * [DorisSelectAliasResolveExtension]: a SELECT-list alias must resolve inside HAVING / GROUP BY /
 * ORDER BY on DorisSQL, exactly as it does on MySQL (the platform gates this behind a hardcoded
 * MySQL-family DBMS allowlist a plugin dialect cannot join — see the extension KDoc).
 *
 * Reproduces the user's real shape (aggregate aliases referenced in HAVING) with generic identifiers.
 * The assertion is resolution parity with the platform MySQL dialect: same target PSI, so every
 * downstream consumer — completion, and `SqlAggregatesInspection`'s "aggregate-free HAVING" check
 * that resolves the reference to look for an aggregate — behaves identically to MySQL for free.
 */
class DorisSelectAliasResolveTest : BasePlatformTestCase() {

    private val sql = """
        SELECT kind, sid,
               count(*) AS c,
               min(ord) AS min_ord,
               max(ord) AS max_ord
          FROM acme_derived.sessions
         GROUP BY kind, sid
        HAVING min_ord <> 1 OR max_ord <> c
         ORDER BY c DESC
         LIMIT 100;
    """.trimIndent()

    /** Each aggregate alias referenced in HAVING / ORDER BY resolves to its select-list definition. */
    fun testHavingAndOrderByAliasesResolveToSelectItems() {
        // The aggregate-alias references only (min_ord/max_ord/c in HAVING, c in ORDER BY); the
        // GROUP BY here names real columns (kind/sid), which resolve to column refs, not aliases.
        val aliasRefs = resolveTailAliases("DorisSQL").filter { it.first in setOf("c", "min_ord", "max_ord") }
        assertEquals(
            "expected the three aggregate aliases referenced across HAVING+ORDER BY (c twice); got $aliasRefs",
            listOf("min_ord", "max_ord", "c", "c"),
            aliasRefs.map { it.first },
        )
        for ((name, target) in aliasRefs) {
            assertTrue(
                "alias '$name' should resolve to its SELECT-list SqlAsExpression, resolved to $target",
                target is SqlAsExpression,
            )
        }
    }

    /** Parity: DorisSQL resolves the same tail-clause aliases the platform MySQL dialect does. */
    fun testResolutionMatchesMysql() {
        val doris = resolveTailAliases("DorisSQL").map { it.first to (it.second?.let { t -> t::class.simpleName }) }
        val mysql = resolveTailAliases("MySQL").map { it.first to (it.second?.let { t -> t::class.simpleName }) }
        assertEquals("DorisSQL tail-clause alias resolution must match MySQL", mysql, doris)
    }

    /** (name, resolvedTarget) for every unqualified reference inside HAVING/GROUP BY/ORDER BY. */
    private fun resolveTailAliases(langId: String): List<Pair<String, PsiElement?>> {
        val lang = Language.findLanguageByID(langId) ?: error("no language $langId")
        val file = PsiFileFactory.getInstance(project).createFileFromText("corpus.sql", lang, sql, false, true)!!
        return PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java)
            .filter { ref ->
                ref.qualifierExpression == null &&
                    ref.name != null &&
                    PsiTreeUtil.getParentOfType(
                        ref, SqlHavingClause::class.java, SqlGroupByClause::class.java, SqlOrderByClause::class.java,
                    ) != null
            }
            .map { it.name!! to runCatching { it.resolve() }.getOrNull() }
    }
}
