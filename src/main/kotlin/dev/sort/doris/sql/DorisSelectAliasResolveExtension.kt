package dev.sort.doris.sql

import com.intellij.psi.ResolveState
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlLanguageDialectEx
import com.intellij.sql.psi.SqlGroupByClause
import com.intellij.sql.psi.SqlHavingClause
import com.intellij.sql.psi.SqlOrderByClause
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.SqlSelectClause
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.sort.doris.DorisDbms

/**
 * Makes a SELECT-list alias resolvable (and completable) inside HAVING / GROUP BY / ORDER BY for
 * DorisSQL — the way MySQL, H2, Redshift and BigQuery already do it.
 *
 * ## Why this is needed (traced through the platform, DB-261 bytecode)
 *
 * `SqlQueryExpressionImpl.processDeclarations` decides whether a tail clause's references may see
 * the query's SELECT-clause aliases from a **hardcoded DBMS allowlist**: for a `SqlHavingClause` it
 * offers them only when `dbms.isMysql() || isH2() || isRedshift() || isBigQuery()` (GROUP BY and
 * ORDER BY carry their own lists). `isMysql()` is membership in the platform's `Dbms.MYSQL_LIKE`
 * `HSet` hierarchy, wired up at platform static-init for the first-party MySQL family. A
 * plugin-defined `Dbms` (`Dbms.create("DORIS", …)` gets a fresh standalone `HSet`) can NOT join that
 * hierarchy through any public API — `create` takes no family, `mySet` is private, the `*_LIKE` sets
 * are `final`. So for DorisSQL the query never calls `selectClause.processDeclarations`, the aliases
 * are never offered, and a bare `HAVING watches > 1` reference resolves to nothing.
 *
 * Two user-visible symptoms, one cause: (1) no completion for the alias in the tail clause, and
 * (2) the platform `SqlAggregatesInspection` fires "aggregate-free conditions in HAVING … might be
 * inefficient", because it resolves the reference to check whether it reaches an aggregate — and an
 * unresolved alias reaches nothing.
 *
 * ## What this does — reuse, not reinvention
 *
 * The `com.intellij.sql.resolveExtension` EP runs for every reference (no dbms attribute), so we
 * gate to DORIS here, then, when the reference is an **unqualified** ref inside a HAVING / GROUP BY /
 * ORDER BY clause, we re-issue the *exact* call the platform withholds:
 * `selectClause.processDeclarations(processor, …)`. That feeds the SELECT clause's alias definitions
 * into the very same `SqlScopeProcessor` the platform's built-in MySQL path feeds — the platform's
 * own alias-offering code, not a copy of it. We do not join the MySQL family (that would need
 * reflection into platform internals and would flip ~50 other `isMysql()`-gated behaviors, several
 * of which the plugin deliberately relies on being *false* to get its two-level catalog model).
 *
 * ## Ordering / precedence (why it's safe)
 *
 * `SqlReferenceImpl.processResolveVariants` runs normal resolution FIRST and only calls the
 * extensions if it did not already resolve (`if (!doProcessResolveVariants(p)) return false; …`).
 * So a bare name that IS a real table column resolves as a column (column wins a name clash, which
 * matches nothing worse than MySQL's rare alias-vs-column ambiguity), and only a name with no column
 * — the alias-only case the user hit — falls through to us. For completion the collecting processor
 * never stops, so both columns and aliases are offered. `processDeclarations` returns `false` when
 * the processor is satisfied and `true` to keep looking; we propagate that verbatim.
 */
class DorisSelectAliasResolveExtension : SqlResolveExtension {

    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val element = reference.element

        // The EP is global (no dbms attribute): act only for DorisSQL.
        val dialect = element.language as? SqlLanguageDialectEx ?: return CONTINUE
        if (dialect.dbms !== DorisDbms.DORIS) return CONTINUE

        // Only unqualified references — `c`, never `t.c` (a qualified ref is not a select alias).
        val refExpr = PsiTreeUtil.getNonStrictParentOfType(element, SqlReferenceExpression::class.java)
        if (refExpr?.qualifierExpression != null) return CONTINUE

        // Only the tail clauses where MySQL exposes select aliases.
        val clause = PsiTreeUtil.getParentOfType(
            element,
            SqlHavingClause::class.java,
            SqlGroupByClause::class.java,
            SqlOrderByClause::class.java,
        ) ?: return CONTINUE

        val query = PsiTreeUtil.getParentOfType(clause, SqlQueryExpression::class.java) ?: return CONTINUE
        val select: SqlSelectClause = query.selectClause ?: return CONTINUE

        // The platform's own alias-offering, re-issued for DORIS (lastParent = null → offer every
        // select item; place = the reference for context).
        return select.processDeclarations(processor, ResolveState.initial(), null, element)
    }

    private companion object {
        /** `SqlResolveExtension.process` follows the Processor contract: true = keep resolving. */
        const val CONTINUE = true
    }
}
