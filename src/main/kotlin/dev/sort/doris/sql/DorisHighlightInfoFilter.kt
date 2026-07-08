package dev.sort.doris.sql

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlAsExpression
import com.intellij.sql.psi.SqlCreateTableStatement
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlGroupByClause
import com.intellij.sql.psi.SqlIdentifier
import com.intellij.sql.psi.SqlQueryExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlSetAssignment
import com.intellij.sql.psi.SqlStatement

/**
 * Suppresses DataGrip's semantic false-positives on Doris built-ins in Doris files:
 *  - "Unable to resolve object type 'X'" — Doris types (JSON, VARIANT, BITMAP, HLL, LARGEINT,
 *    AGG_STATE, ARRAY/MAP/STRUCT, ...) that the SQL92 substrate parses as unknown object types.
 *  - "Unknown database function 'X'" — Doris built-in functions. SqlResolveInspection resolves
 *    function calls against the introspected data-source model (ObjectKind.ROUTINE via
 *    processNameIndex), NOT the dialect's getSupportedFunctions() registry, so Doris built-ins are
 *    never found there and can't be registered into it.
 *
 * Both are safe to drop: nothing authoritatively validates these anyway (fe-sql-parser is
 * syntax-only; the DB model doesn't expose Doris built-ins). Unresolved *tables/columns* still
 * highlight normally, and fe-sql-parser ([DorisErrorAnnotator]) still catches real syntax errors.
 *
 * ## Table-valued functions (narrowly scoped "Unable to resolve column" suppression)
 * Two TVF-only contexts additionally drop unresolved-COLUMN errors ([isTvfFalsePositive]):
 *  1. Inside the ARGUMENT LIST of a registered TVF call: Doris property bags are written
 *     `"key"="value"`, and the MySQL grammar reads the double-quoted operands as column
 *     references — every documented-style call (`tasks("type"="mv")`) lit up red.
 *  2. Unresolved columns in a query whose FROM contains an OPEN-relation TVF (`s3`, `hdfs`, ...,
 *     [DorisTableFunctions.Schema.Open]): their real output columns are unknowable without
 *     reading external data (which we never do), so any column reference must be accepted
 *     silently — no fabricated columns, no red (research doc Tier B degradation).
 * TVFs with static schemas keep NORMAL column validation — their columns resolve for real via
 * [DorisTypeSystem], and a genuinely wrong column should stay red.
 *
 * ## Dogfood 2026-07-08 batch
 * Two more narrowly-gated rules: the `* EXCEPT` count-mismatch suppression (P0, see
 * [isExceptCountMismatch]) and four unresolved-reference shapes that have nothing authoritative to
 * resolve against (see [isDorisUnresolvedFalsePositive]).
 */
class DorisHighlightInfoFilter : HighlightInfoFilter {
    override fun accept(highlightInfo: HighlightInfo, file: PsiFile?): Boolean {
        if (file == null || !file.language.isKindOf(DorisSqlDialect.INSTANCE)) return true
        val description = highlightInfo.description ?: return true
        if (SUPPRESSED_PREFIXES.any { description.contains(it) }) return false
        // P0 (dogfood 2026-07-08): "N value(s) expected, got M" (SqlInsertValuesInspection) is
        // structurally wrong whenever the feeding query contains `* EXCEPT(...)`: the EXCEPT list is
        // lexer-masked (DorisLexer), so the platform expands `*` to ALL source columns and the counts
        // can never agree. Gated on the statement actually containing the column-exclusion form —
        // genuine count mismatches (no EXCEPT) keep their inspection. Covers INSERT INTO ... SELECT
        // and CREATE TABLE/MTMV column-list vs AS-query alike (both live in one SqlStatement).
        if (description.contains(COUNT_MISMATCH) && isExceptCountMismatch(file, highlightInfo)) {
            return false
        }
        if (description.contains(UNRESOLVED_PREFIX)) {
            val element = file.findElementAt(highlightInfo.startOffset)
            if (element != null && isDorisUnresolvedFalsePositive(element)) return false
        }
        if (description.contains(UNRESOLVED_COLUMN) && isTvfFalsePositive(file, highlightInfo)) return false
        // M9 (flag-ON only): references into enumerated-but-not-introspected catalogs are
        // OUT-OF-SCOPE, not wrong — suppress instead of red-flooding. Nonexistent names under
        // INTROSPECTED namespaces (incl. internal) keep their error.
        if (dev.sort.doris.DorisCatalogs.enabled &&
            description.contains(UNRESOLVED_PREFIX) &&
            isOutOfScopeReference(file, highlightInfo)
        ) {
            return false
        }
        return true
    }

    /** P0: the count-mismatch highlight sits on the feeding query; gate on its statement's text. */
    private fun isExceptCountMismatch(file: PsiFile, info: HighlightInfo): Boolean {
        val element = file.findElementAt(info.startOffset) ?: return false
        val statement = PsiTreeUtil.getParentOfType(element, SqlStatement::class.java, false) ?: return false
        return EXCEPT_COLUMN_EXCLUSION.containsMatchIn(statement.text)
    }

    /**
     * Doris-only unresolved-reference false positives (dogfood 2026-07-08 P2 batch). Four narrowly
     * scoped shapes, each of which has NOTHING authoritative to resolve against — the platform model
     * simply does not contain the entity the reference names:
     *
     *  1. [isSessionVariableTarget] — `SET enable_local_shuffle = true`: the assignment target of a
     *     session-scope SET resolves as a COLUMN under the MySQL grammar. Doris has hundreds of
     *     session variables MySQL doesn't know; nothing validates them here (a variable registry is
     *     a future feature), so ANY unresolved ref inside a SqlSetAssignment is dropped. (`SET GLOBAL
     *     x = ...` already parses as a system variable and never errors.)
     *
     *  2. [isBareTokenRunIdentifier] — a bare SQL_IDENTIFIER that is NOT part of a reference
     *     expression. Only the Route B replayer produces these: identifiers inside deliberate
     *     Doris-only token runs (SWITCH <catalog>, CREATE JOB <name>, REFRESH ... PARTITION(p_x),
     *     REFRESH AUTO ON SCHEDULE EVERY 1 <unit>, ENGINE = OLAP). SqlResolveInspection still
     *     resolve-checks the bare identifier, but there is no model entity for a job name / partition
     *     name / schedule unit — they are definitions or pure syntax. Real references (identifier
     *     wrapped in SqlReferenceExpression — columns, tables, qualifiers) keep normal validation.
     *
     *  3. [isSelectAliasInGroupBy] — `GROUP BY <select-alias>`: legal in Doris (and MySQL). The
     *     platform DOES resolve aliases in GROUP BY, but only for Dbms in the MYSQL_LIKE HSet family
     *     (SqlQueryExpressionImpl gates on Dbms.isMysql(); membership is registered via the
     *     com.intellij.database.addToHSet EP) — and DORIS is not in the family. Joining the family
     *     would flip every isMysql() behavior at once (unaudited blast radius), so instead we drop
     *     just the unresolved-column error when the name matches a select-item alias of the SAME
     *     query. A name matching no alias stays red.
     *
     *  4. [isCtasKeyColumn] — CTAS key/order columns: `CREATE TABLE t UNIQUE KEY(id) ORDER BY(c)
     *     ... AS SELECT ...` has no column-definition list; the key columns can only be validated
     *     against the AS-query's projection, which the platform never computes for the replayed
     *     Doris shape. Gated on the statement really being a CTAS (no declared columns + AS query);
     *     a CREATE TABLE with real column definitions keeps key-column validation.
     */
    private fun isDorisUnresolvedFalsePositive(element: PsiElement): Boolean =
        isSessionVariableTarget(element) ||
            isBareTokenRunIdentifier(element) ||
            isSelectAliasInGroupBy(element) ||
            isCtasKeyColumn(element)

    private fun isSessionVariableTarget(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, SqlSetAssignment::class.java) != null

    private fun isBareTokenRunIdentifier(element: PsiElement): Boolean {
        val identifier = PsiTreeUtil.getParentOfType(element, SqlIdentifier::class.java, false) ?: return false
        return identifier.parent !is SqlReferenceExpression
    }

    private fun isSelectAliasInGroupBy(element: PsiElement): Boolean {
        val ref = PsiTreeUtil.getParentOfType(element, SqlReferenceExpression::class.java, false) ?: return false
        if (PsiTreeUtil.getParentOfType(ref, SqlGroupByClause::class.java) == null) return false
        val name = PsiTreeUtil.getParentOfType(element, SqlIdentifier::class.java, false)?.name ?: return false
        val select = PsiTreeUtil.getParentOfType(ref, SqlQueryExpression::class.java)?.selectClause ?: return false
        // DIRECT select items only — an alias inside a nested subquery must not vouch for the outer query.
        return select.children.filterIsInstance<SqlAsExpression>()
            .any { it.nameElement?.name?.equals(name, ignoreCase = true) == true }
    }

    private fun isCtasKeyColumn(element: PsiElement): Boolean {
        var e: PsiElement? = element
        while (e != null) {
            // key/order columns hang DIRECTLY off the create statement; anything inside the AS-query
            // (or any other query) is a normal query-scoped reference and keeps validation.
            if (e is SqlQueryExpression) return false
            if (e is SqlCreateTableStatement) {
                return e.declaredColumns.isEmpty() &&
                    PsiTreeUtil.findChildOfType(e, SqlQueryExpression::class.java) != null
            }
            e = e.parent
        }
        return false
    }

    /**
     * OUT-OF-SCOPE detection (M9, the degrade half of the "Introspect this?" design;
     * classification decision table lives in [dev.sort.doris.catalog.DorisOutOfScope]):
     *
     *  1. The failing reference is QUALIFIED and its qualifier resolves to a das namespace that is
     *     enumerated-but-childless (an external catalog, or a database introspection never
     *     visited) — the segment cannot possibly resolve until the user opts the namespace in.
     *  2. The failing reference is a COLUMN in a query whose FROM contains a table reference that
     *     is itself out-of-scope by rule 1 — without the table's columns, every column reference
     *     is unresolvable; keeping them red is pure noise (mirror of the TVF open-relation rule).
     */
    private fun isOutOfScopeReference(file: PsiFile, info: HighlightInfo): Boolean {
        val element = file.findElementAt(info.startOffset) ?: return false
        val ref = PsiTreeUtil.getParentOfType(element, SqlReferenceExpression::class.java, false)

        // (1) qualified segment whose parent path lands on a childless external namespace
        if (ref != null && isQualifierOutOfScope(ref)) return true

        // (2) column inside a query whose FROM has an out-of-scope table reference
        var query: SqlQueryExpression? = PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java)
        while (query != null) {
            val from: PsiElement? = query.tableExpression
            if (from != null &&
                PsiTreeUtil.findChildrenOfType(from, SqlReferenceExpression::class.java)
                    .any { isQualifierOutOfScope(it) }
            ) {
                return true
            }
            query = PsiTreeUtil.getParentOfType(query, SqlQueryExpression::class.java)
        }
        return false
    }

    /** True when [ref]'s immediate qualifier resolves to an enumerated-but-childless namespace. */
    private fun isQualifierOutOfScope(ref: SqlReferenceExpression): Boolean {
        val qualifier = ref.qualifierExpression as? SqlReferenceExpression ?: return false
        val resolved = qualifier.reference?.resolve() ?: return false
        val das = resolved as? com.intellij.database.model.DasObject ?: return false
        return dev.sort.doris.catalog.DorisOutOfScope.classify(das) ==
            dev.sort.doris.catalog.DorisOutOfScope.Classification.OUT_OF_SCOPE
    }

    private fun isTvfFalsePositive(file: PsiFile, info: HighlightInfo): Boolean {
        val element = file.findElementAt(info.startOffset) ?: return false

        // (1) property-bag args of any registered TVF call
        var call: SqlFunctionCallExpression? =
            PsiTreeUtil.getParentOfType(element, SqlFunctionCallExpression::class.java)
        while (call != null) {
            val inArgs = call.parameterList?.textRange?.contains(info.startOffset) == true
            if (inArgs && DorisTableFunctions.byName(call.nameElement?.name) != null) return true
            call = PsiTreeUtil.getParentOfType(call, SqlFunctionCallExpression::class.java)
        }

        // (2) output columns of an open-relation TVF anywhere in the enclosing queries' FROMs
        var query: SqlQueryExpression? = PsiTreeUtil.getParentOfType(element, SqlQueryExpression::class.java)
        while (query != null) {
            val from: PsiElement? = query.tableExpression
            if (from != null &&
                PsiTreeUtil.findChildrenOfType(from, SqlFunctionCallExpression::class.java).any {
                    DorisTableFunctions.byName(it.nameElement?.name)?.schema is DorisTableFunctions.Schema.Open
                }
            ) return true
            query = PsiTreeUtil.getParentOfType(query, SqlQueryExpression::class.java)
        }
        return false
    }

    private companion object {
        private const val UNRESOLVED_COLUMN = "Unable to resolve column"
        private const val UNRESOLVED_PREFIX = "Unable to resolve"

        // SqlInsertValuesInspection's message (SqlBundle "incorrect.values.number"):
        // "{0} value(s) expected, got {1}". Match the stable middle, the numbers vary.
        private const val COUNT_MISMATCH = " value(s) expected, got "

        // The Doris `* EXCEPT(col, ...)` column-exclusion form — same discrimination as
        // DorisLexer.isExceptColumnExclusion: a `*`, then EXCEPT, then a parenthesised list that
        // starts with an identifier (backtick ok). A set-operation right-hand side (SELECT / WITH /
        // VALUES / TABLE) is excluded so `SELECT * FROM t EXCEPT (SELECT ...)` keeps its inspections.
        private val EXCEPT_COLUMN_EXCLUSION = Regex(
            """\*\s*EXCEPT\s*\(\s*(?!(?:SELECT|WITH|VALUES|TABLE)\b)[`_\p{L}]""",
            RegexOption.IGNORE_CASE,
        )

        // Match on the stable leading phrase of each message (the '{0}' quoted name varies).
        private val SUPPRESSED_PREFIXES = listOf(
            "Unable to resolve object type", // Doris types unknown to the SQL92 substrate
            "Unknown database function",     // Doris built-ins absent from the introspected model
        )
    }
}
