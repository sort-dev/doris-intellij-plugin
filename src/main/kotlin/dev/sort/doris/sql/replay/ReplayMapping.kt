package dev.sort.doris.sql.replay

import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_AS_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_BINARY_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_COLUMN_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_FROM_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_GROUP_BY_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_HAVING_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_IDENTIFIER
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_JOIN_CONDITION_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_JOIN_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_LIMIT_OFFSET_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_NUMERIC_LITERAL
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_ORDER_BY_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_QUERY_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_SELECT_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_SELECT_OPTION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_SELECT_STATEMENT
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_WHERE_CLAUSE

/**
 * Route B mapping table (Gate 2 of RESEARCH-when-hell-freezes-over-parser.md).
 *
 * Maps Doris ANTLR CST nodes to the platform's shared SQL PSI vocabulary
 * ([com.intellij.sql.psi.SqlCompositeElementTypes]). Only nodes named here materialize as PSI
 * markers during replay; every other CST rule is transparent (its tokens attach to the nearest
 * mapped ancestor). Derived by reading golden/mysql/mysql-core/04-baseline-select.tree against the
 * live CST for the same SQL.
 *
 * KEY DESIGN CHOICE — we key on the ANTLR *context class simpleName*, not the bare rule name.
 * ANTLR reuses rules like `identifier` / `primaryExpression` in many roles (a column vs. a table
 * name vs. a literal all descend through `primaryExpression`), so a rule-name -> type map is
 * ambiguous. Doris' grammar labels its alternatives (`# columnReference`, `# constantDefault`,
 * `# tableName`, ...), and ANTLR emits a distinct context subclass per label
 * (`ColumnReferenceContext`, `ConstantDefaultContext`, `TableNameContext`). Those labels ARE the
 * role discriminator the platform PSI needs, so the mapping is exact and unambiguous.
 *
 * Unlabeled rules keep the default `<Rule>Context` name (`SelectClauseContext`, `WhereClauseContext`)
 * which is likewise unique, so the same by-class-name scheme covers both.
 */
internal object ReplayMapping {

    /** Context-class simpleName -> platform element type. See class doc for why we key on the class. */
    val BY_CONTEXT_CLASS: Map<String, IElementType> = mapOf(
        // statement level: statementBase's `# statementDefault` (a bare query statement) is the
        // SELECT statement wrapper; the `query` rule inside it is the query expression. Both span the
        // whole statement text (minus the trailing ';', which the framework keeps as our sibling).
        "StatementDefaultContext" to SQL_SELECT_STATEMENT,
        "QueryContext" to SQL_QUERY_EXPRESSION,

        // SELECT ... : the select clause. The identifier leaf is the
        // `strictIdentifier # unquotedIdentifier` wrapper. A qualified column `a.b` is a
        // `primaryExpression # dereference` around a reference + trailing identifier.
        "SelectClauseContext" to SQL_SELECT_CLAUSE,
        "UnquotedIdentifierContext" to SQL_IDENTIFIER,
        "DereferenceContext" to SQL_COLUMN_REFERENCE,

        // FROM ... : the from clause.
        "FromRelationsContext" to SQL_FROM_CLAUSE,

        // WHERE ... / JOIN ON ... : comparison predicate and the join's ON criteria.
        "WhereClauseContext" to SQL_WHERE_CLAUSE,
        "ComparisonContext" to SQL_BINARY_EXPRESSION,
        "NumericLiteralContext" to SQL_NUMERIC_LITERAL,
        "JoinCriteriaContext" to SQL_JOIN_CONDITION_CLAUSE,

        // Expression nesting (golden/mysql/mysql-core/07-operator-precedence-chain.tree). ANTLR's
        // left-recursive `valueExpression` rule emits a distinct labeled subclass per binary form —
        // `# arithmeticBinary` (a+b, a*b, ...) and `# comparison` (a=b) — and a `booleanExpression`
        // `# logicalBinary` for AND/OR (golden/mysql/mysql-core/02-watch-time-lag-query.tree). All
        // three collapse to the platform's single SQL_BINARY_EXPRESSION. The pass-through wrappers the
        // grammar interposes (`# valueExpressionDefault`, `# predicated`, plus the unlabeled
        // expression/identifier chains) are NOT mapped, so they stay transparent — which is exactly how
        // the platform flattens single-child productions (see [CstReplayer] docs on the collapse rule).
        "ArithmeticBinaryContext" to SQL_BINARY_EXPRESSION,
        "LogicalBinaryContext" to SQL_BINARY_EXPRESSION,

        // Query-tail clauses. GROUP BY (`aggClause`) and HAVING (`havingClause`) sit INSIDE the
        // synthetic SQL_TABLE_EXPRESSION (golden/mysql/mysql-core/06-baseline-group-by.tree,
        // 32-having.tree); ORDER BY (`sortClause`) and LIMIT/OFFSET (`limitClause`) sit OUTSIDE it as
        // direct children of the query expression (06-baseline-group-by.tree,
        // 30-limit-offset-variants.tree). The intermediate `queryOrganization` wrapper is transparent.
        "AggClauseContext" to SQL_GROUP_BY_CLAUSE,
        "HavingClauseContext" to SQL_HAVING_CLAUSE,
        "SortClauseContext" to SQL_ORDER_BY_CLAUSE,
        "LimitClauseContext" to SQL_LIMIT_OFFSET_CLAUSE,
    )

    /**
     * Context-SENSITIVE resolutions. A handful of ANTLR nodes carry a different platform element
     * type depending on their surroundings — the classic identifier-role polymorphism the platform
     * PSI encodes structurally. These cannot live in the flat map; the replayer applies them after
     * consulting [BY_CONTEXT_CLASS]. Each takes the node's class, its parent's class, and a probe for
     * a named child, returning the type or null (transparent). Documented case-by-case:
     *
     *  - ColumnReferenceContext: a bare `primaryExpression # columnReference` is a whole column
     *    reference (SQL_COLUMN_REFERENCE); the SAME node as the LEFT side of a dereference `a.b` is
     *    just the qualifier reference (SQL_REFERENCE).
     *  - MultipartIdentifierContext: the table name inside a `relationPrimary` is the table reference
     *    (SQL_TABLE_REFERENCE); elsewhere it is transparent.
     *  - TableNameContext (`relationPrimary # tableName`): with a table alias it is the alias
     *    expression wrapper (SQL_AS_EXPRESSION, containing the table ref + the alias identifier);
     *    without one it is transparent (its multipart child already yields the table reference).
     *  - RelationContext: a relation that carries a `joinRelation` child is a join
     *    (SQL_JOIN_EXPRESSION); a plain single relation is transparent.
     */
    fun resolveContextual(nodeClass: String, parentClass: String?, hasNonEmptyChildRule: (String) -> Boolean): IElementType? =
        when (nodeClass) {
            "ColumnReferenceContext" -> if (parentClass == "DereferenceContext") SQL_REFERENCE else SQL_COLUMN_REFERENCE
            "MultipartIdentifierContext" -> if (parentClass == "TableNameContext") SQL_TABLE_REFERENCE else null
            // NB: the tableAlias child is always PRESENT but EMPTY when unaliased, so probe non-empty.
            "TableNameContext" -> if (hasNonEmptyChildRule(TABLE_ALIAS_RULE)) SQL_AS_EXPRESSION else null
            "RelationContext" -> if (hasNonEmptyChildRule(JOIN_RELATION_RULE)) SQL_JOIN_EXPRESSION else null
            else -> null
        }

    private const val TABLE_ALIAS_RULE = "tableAlias"
    private const val JOIN_RELATION_RULE = "joinRelation"

    /**
     * Context class of the SELECT body (`querySpecification # regularQuerySpecification`). The platform
     * PSI wraps this query's FROM + WHERE + GROUP BY + HAVING in a single SQL_TABLE_EXPRESSION that the
     * flat ANTLR grammar has no node for, so the replayer SYNTHESISES it. Crucially it spans only the
     * BODY clauses: it starts at the FROM clause and ends at the LAST of [BODY_CLAUSE_CLASSES], so the
     * trailing `queryOrganization` (ORDER BY / LIMIT) falls OUTSIDE it as a sibling — matching
     * golden/mysql/mysql-core/06-baseline-group-by.tree and 30-limit-offset-variants.tree.
     * See [CstReplayer]'s synthetic-node handling.
     */
    const val QUERY_SPECIFICATION_CLASS: String = "RegularQuerySpecificationContext"

    /** Context class of the FROM clause, used to find the synthetic table-expression's start offset. */
    const val FROM_CLAUSE_CLASS: String = "FromRelationsContext"

    /**
     * The query-body clauses that live INSIDE the synthetic SQL_TABLE_EXPRESSION. The table expression
     * spans [FROM.start, max(stop over these)]. ORDER BY / LIMIT (`queryOrganization`) are deliberately
     * excluded — the platform keeps them as siblings of the table expression (06-baseline-group-by).
     */
    val BODY_CLAUSE_CLASSES: Set<String> =
        setOf("FromRelationsContext", "WhereClauseContext", "AggClauseContext", "HavingClauseContext")

    /** Context class of the SELECT clause; hosts the synthetic SQL_SELECT_OPTION (DISTINCT) wrapper. */
    const val SELECT_CLAUSE_CLASS: String = "SelectClauseContext"

    /** Context class of the LIMIT clause; hosts synthetic SQL_NUMERIC_LITERAL wrappers per integer. */
    const val LIMIT_CLAUSE_CLASS: String = "LimitClauseContext"

    /** Element type for the synthesised query-body wrapper (FROM + WHERE + ...). */
    val TABLE_EXPRESSION: IElementType = com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_EXPRESSION

    /** SQL_SELECT_OPTION wraps a bare DISTINCT terminal in the select clause (31-distinct.tree). */
    val SELECT_OPTION: IElementType = SQL_SELECT_OPTION

    /** SQL_NUMERIC_LITERAL wraps a bare integer terminal inside LIMIT (30-limit-offset-variants.tree). */
    val NUMERIC_LITERAL: IElementType = SQL_NUMERIC_LITERAL
}
