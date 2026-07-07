package dev.sort.doris.sql.replay

import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_AS_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_BINARY_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_COLUMN_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_COLUMN_SHORT_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_FROM_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_GROUP_BY_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_HAVING_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_IDENTIFIER
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_JOIN_CONDITION_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_JOIN_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_LIMIT_OFFSET_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_NUMERIC_LITERAL
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_AS_QUERY_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_CREATE_TABLE_STATEMENT
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_CREATE_VIEW_STATEMENT
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_ORDER_BY_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_QUALIFY_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_QUERY_EXPRESSION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_STATEMENT
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_VIEW_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_REFERENCE_LIST
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_SELECT_CLAUSE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_SELECT_OPTION
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_SELECT_STATEMENT
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_USING_CLAUSE
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

        // Doris STATEMENT-LEAD families (Route B unparked, RESEARCH-when-hell-freezes-over Route B).
        // The authoritative Doris grammar wraps each family in a distinctly-labelled statementBase
        // alternative; those context classes are the top materialised node of the replayed statement.
        //  - CREATE [OR REPLACE] VIEW ... AS <query>  -> SQL_CREATE_VIEW_STATEMENT (shared shape, mirrors
        //    the platform MySQL CREATE VIEW: SQL_VIEW_REFERENCE name + SQL_AS_QUERY_CLAUSE over the query).
        //  - CREATE MATERIALIZED VIEW ... AS <query>  -> SQL_CREATE_VIEW_STATEMENT is the BEST-FIT shared
        //    kind (the platform has no MTMV element type); the Doris-only options (BUILD/REFRESH/DISTRIBUTED)
        //    stay as plain token runs inside the typed statement, the AS-query is replayed in full.
        //  - CREATE TABLE (Doris)                     -> SQL_CREATE_TABLE_STATEMENT (see CstReplayer's
        //    column-def / data-type-delegation handling; Doris clauses are deliberate token runs).
        //  - REFRESH / WARM UP / SWITCH               -> no platform statement kind fits, so they stay a
        //    generic SQL_STATEMENT (boundary-preserving, same top kind as the lenient path) but now carry
        //    MATERIALISED inner references (table refs / identifiers) for navigation & completion context.
        "CreateViewContext" to SQL_CREATE_VIEW_STATEMENT,
        "CreateMTMVContext" to SQL_CREATE_VIEW_STATEMENT,
        "CreateTableContext" to SQL_CREATE_TABLE_STATEMENT,
        "RefreshTableContext" to SQL_STATEMENT,
        "WarmUpClusterContext" to SQL_STATEMENT,
        "SwitchCatalogContext" to SQL_STATEMENT,

        // CREATE JOB <name> ON SCHEDULE ... DO <insert>: no platform statement kind fits the job wrapper,
        // so it stays a generic SQL_STATEMENT (boundary identical to lenient) with the header (name,
        // ON SCHEDULE ...) as a token run — but its DO-body INSERT becomes REAL insert PSI via
        // [CstReplayer.emitInsertSkeleton]. Golden evidence for nesting a full insert statement inside
        // another statement: the platform's own MySQL `CREATE EVENT ... DO INSERT ...` renders exactly
        // SQL_INSERT_STATEMENT > SQL_INSERT_DML_INSTRUCTION > SQL_TABLE_COLUMN_LIST > SQL_TABLE_REFERENCE
        // nested inside MYSQL_CREATE_EVENT_STATEMENT (live-dumped 2026-07-07; CREATE EVENT is the direct
        // analog of CREATE JOB's DO-body).
        "CreateScheduledJobContext" to SQL_STATEMENT,

        // CREATE TABLE column definitions: each `columnDef` is a real SQL_COLUMN_DEFINITION whose name
        // materialises as SQL_IDENTIFIER (via the strictIdentifier mapping). The column DATA TYPE is left
        // as a plain token run inside the definition — the platform's SQL_BUILTIN_TYPE_ELEMENT is produced
        // by parseDataType (a *protected* SqlParser method the replay bridge cannot call cross-class), and
        // Doris DDL adds type spellings (LARGEINT, agg-model modifiers like `INT SUM`) the MySQL data-type
        // parser would reject anyway. So the column name is typed & navigable; the type stays a stable span.
        "ColumnDefContext" to com.intellij.sql.psi.SqlCompositeElementTypes.SQL_COLUMN_DEFINITION,

        // QUALIFY: the one query clause the MySQL grammar cannot parse, but the platform DOES own a shared
        // SQL_QUALIFY_CLAUSE element type. The replayer materialises it as a sibling of the synthetic
        // SQL_TABLE_EXPRESSION (like ORDER BY / LIMIT), and DELEGATES its boolean expression to the platform
        // value-expression parser (window functions et al. come out exact). Replaces the bounded-parse path.
        "QualifyClauseContext" to SQL_QUALIFY_CLAUSE,
        // NB: QueryContext / SetOperationContext / QueryPrimaryDefaultContext are resolved in
        // [CstReplayer.collect] because their type depends on whether the query is a UNION (flat
        // SQL_UNION_EXPRESSION with per-branch SQL_QUERY_EXPRESSION) or a plain SQL_QUERY_EXPRESSION.

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
        // NB: ComparisonContext / NumericLiteralContext (and the arithmetic/logical/column/dereference
        // expression classes below) are UNREACHABLE under Gate 2.5 — every expression is delegated to
        // the platform value-expression parser, so the replayer never descends into an expression
        // subtree. They remain only as a documented fallback for a hypothetical expression that appears
        // outside a delegating clause; delete when the delegation surface is proven exhaustive.
        "ComparisonContext" to SQL_BINARY_EXPRESSION,
        "NumericLiteralContext" to SQL_NUMERIC_LITERAL,
        // JOIN ... USING (cols): the id list is a reference list of short column references (golden 22).
        "IdentifierListContext" to SQL_REFERENCE_LIST,

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
            // A multipart identifier `p1.p2.…` is a REFERENCE whose kind depends on the enclosing construct:
            // a table name in a relation / DDL / REFRESH / WARM UP is a table ref; the object name of a
            // CREATE VIEW / MTMV is a view ref (mirrors the platform MySQL CREATE VIEW shape). The qualifier
            // parts nest as SQL_REFERENCE via CstReplayer.emitMultipartQualifiers (gated on the same set).
            "MultipartIdentifierContext" -> REFERENCE_PARENTS[parentClass]
            // NB: the tableAlias child is always PRESENT but EMPTY when unaliased, so probe non-empty.
            "TableNameContext" -> if (hasNonEmptyChildRule(TABLE_ALIAS_RULE)) SQL_AS_EXPRESSION else null
            // JoinCriteria: `ON <expr>` is the join condition; `USING (cols)` is the using clause.
            "JoinCriteriaContext" -> if (hasNonEmptyChildRule(IDENTIFIER_LIST_RULE)) SQL_USING_CLAUSE else SQL_JOIN_CONDITION_CLAUSE
            // A column name inside a USING id list is a short (unqualified) column reference (golden 22).
            // The list element is an `errorCapturingIdentifier` directly under `identifierSeq`.
            "ErrorCapturingIdentifierContext" -> if (parentClass == "IdentifierSeqContext") SQL_COLUMN_SHORT_REFERENCE else null
            // RelationContext (chained joins) is handled by CstReplayer.emitNestedJoins — it needs one
            // nested SQL_JOIN_EXPRESSION per join, which a single flat-map entry cannot express.
            else -> null
        }

    /**
     * ANTLR parent-context class -> the reference element type its `multipartIdentifier` child maps to.
     * Also the gate for [CstReplayer.emitMultipartQualifiers]: a multipart name whose parent is a key here
     * gets its qualifier prefixes nested as SQL_REFERENCE. Anything not listed leaves the multipart
     * transparent (its inner identifier leaf still materialises through the strictIdentifier mapping).
     */
    val REFERENCE_PARENTS: Map<String, IElementType> = mapOf(
        "TableNameContext" to SQL_TABLE_REFERENCE,     // relationPrimary # tableName (FROM / JOIN)
        "CreateViewContext" to SQL_VIEW_REFERENCE,     // CREATE [OR REPLACE] VIEW <name>
        "CreateMTMVContext" to SQL_VIEW_REFERENCE,     // CREATE MATERIALIZED VIEW <name>
        "CreateTableContext" to SQL_TABLE_REFERENCE,   // CREATE TABLE <name>
        "RefreshTableContext" to SQL_TABLE_REFERENCE,  // REFRESH TABLE <name>
        "WarmUpItemContext" to SQL_TABLE_REFERENCE,    // WARM UP ... WITH TABLE <name>
        "InsertTableContext" to SQL_TABLE_REFERENCE,   // INSERT INTO <target> (CREATE JOB DO-body)
    )

    /** SQL_INSERT_STATEMENT: the DO-body insert of a CREATE JOB (MySQL CREATE EVENT analog shape). */
    val INSERT_STATEMENT: IElementType = com.intellij.sql.psi.SqlCompositeElementTypes.SQL_INSERT_STATEMENT

    /** SQL_INSERT_DML_INSTRUCTION: `INTO <target> <query>` — the shape INSERT completion consults. */
    val INSERT_DML_INSTRUCTION: IElementType =
        com.intellij.sql.psi.SqlCompositeElementTypes.SQL_INSERT_DML_INSTRUCTION

    /**
     * SQL_TABLE_COLUMNS_LIST: wraps the insert target reference (+ column list when present). NB the
     * constant is ..._COLUMNS_LIST while its DEBUG name (what golden trees show) is SQL_TABLE_COLUMN_LIST.
     */
    val TABLE_COLUMN_LIST: IElementType = com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_COLUMNS_LIST

    /** SQL_AS_QUERY_CLAUSE: the synthetic `AS <query>` wrapper of a CREATE [MATERIALIZED] VIEW (MySQL shape). */
    val AS_QUERY_CLAUSE: IElementType = SQL_AS_QUERY_CLAUSE

    /** Context classes of the CREATE-VIEW family; host the synthetic SQL_AS_QUERY_CLAUSE. */
    val AS_QUERY_PARENTS: Set<String> = setOf("CreateViewContext", "CreateMTMVContext")

    const val TABLE_ALIAS_RULE: String = "tableAlias"
    private const val IDENTIFIER_LIST_RULE = "identifierList"

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

    /**
     * Context class of a select-list item (`namedExpression`). When it carries an alias (an
     * `identifierOrText` child) the platform wraps the whole item in SQL_AS_EXPRESSION (golden 06);
     * see [CstReplayer]'s alias handling.
     */
    const val NAMED_EXPRESSION_CLASS: String = "NamedExpressionContext"

    /** Rule name of the select-item alias (`namedExpression`'s trailing `AS? identifierOrText`). */
    const val ALIAS_TEXT_RULE: String = "identifierOrText"

    /** SQL_AS_EXPRESSION wraps an aliased select item (delegated expression + alias identifier). */
    val AS_EXPRESSION: IElementType = SQL_AS_EXPRESSION

    /** SQL_QUERY_EXPRESSION: a (non-union) query, or a single branch of a union. */
    val QUERY_EXPRESSION: IElementType = SQL_QUERY_EXPRESSION

    /** SQL_UNION_EXPRESSION: the flat set-operation node holding all union branches (golden 25). */
    val UNION_EXPRESSION: IElementType = com.intellij.sql.psi.SqlCompositeElementTypes.SQL_UNION_EXPRESSION

    /** SQL_JOIN_EXPRESSION: one per join in a chained `relation` (golden 21). */
    val JOIN_EXPRESSION: IElementType = SQL_JOIN_EXPRESSION

    /** SQL_PARENTHESIZED_QUERY_EXPRESSION: the `( query )` of a derived table (golden 24). */
    val PARENTHESIZED_QUERY_EXPRESSION: IElementType =
        com.intellij.sql.psi.SqlCompositeElementTypes.SQL_PARENTHESIZED_QUERY_EXPRESSION

    /** SQL_WITH_QUERY_EXPRESSION: a `WITH ... <body>` query (golden 01). */
    val WITH_QUERY_EXPRESSION: IElementType =
        com.intellij.sql.psi.SqlCompositeElementTypes.SQL_WITH_QUERY_EXPRESSION

    /** SQL_WITH_CLAUSE: the `WITH [RECURSIVE] <named queries>` clause (golden 01/29). */
    val WITH_CLAUSE: IElementType = com.intellij.sql.psi.SqlCompositeElementTypes.SQL_WITH_CLAUSE

    /** SQL_NAMED_QUERY_DEFINITION: a single CTE definition `name AS ( query )` (golden 01). */
    val NAMED_QUERY_DEFINITION: IElementType =
        com.intellij.sql.psi.SqlCompositeElementTypes.SQL_NAMED_QUERY_DEFINITION

    /** SQL_REFERENCE: a qualifier part of a multi-part table name `db.tbl` (golden 02). */
    val REFERENCE: IElementType = SQL_REFERENCE

    /** Context class of the LIMIT clause; hosts synthetic SQL_NUMERIC_LITERAL wrappers per integer. */
    const val LIMIT_CLAUSE_CLASS: String = "LimitClauseContext"

    /** Element type for the synthesised query-body wrapper (FROM + WHERE + ...). */
    val TABLE_EXPRESSION: IElementType = com.intellij.sql.psi.SqlCompositeElementTypes.SQL_TABLE_EXPRESSION

    /** SQL_SELECT_OPTION wraps a bare DISTINCT terminal in the select clause (31-distinct.tree). */
    val SELECT_OPTION: IElementType = SQL_SELECT_OPTION

    /** SQL_NUMERIC_LITERAL wraps a bare integer terminal inside LIMIT (30-limit-offset-variants.tree). */
    val NUMERIC_LITERAL: IElementType = SQL_NUMERIC_LITERAL
}
