package dev.sort.doris.sql.replay

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.base.SqlParser
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_COLUMN_REFERENCE
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_IDENTIFIER
import com.intellij.sql.psi.SqlKeywordTokenType
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.DefaultErrorStrategy
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.TerminalNode
import org.apache.doris.nereids.DorisParser
import org.apache.doris.sqlparser.DorisSqlParser

/**
 * Route B "shadow-replay bridge" (Gates 2 / 2.5 of RESEARCH-when-hell-freezes-over-parser.md).
 *
 * Given a [PsiBuilder] positioned at the start of a statement, this parses that one statement with
 * the authoritative Doris ANTLR grammar (fe-sql-parser), then REPLAYS the platform's own
 * MysqlLexer token stream through the builder, opening/closing PSI markers at CST node boundaries.
 * The result is a platform PSI tree whose *shape* is dictated by the real Doris parser but whose
 * *tokens* remain the platform's (so the rest of the SQL ecosystem keeps working).
 *
 * ## Gate 2.5 — the statement-structure/expression split
 *
 * Function calls (COUNT(*), SUM(x) OVER ..., etc.) exposed the wall: the platform renders them as
 * SQL_FUNCTION_CALL carrying platform-internal frame nodes ("INFO:[expr:any*]", "INFO:[0]") that are
 * artifacts of the MySQL *generated* parser's argument machinery — NOT derivable from the ANTLR CST.
 * Synthesising them is guesswork. So we stop trying: the replayer reproduces the *statement
 * structure* (clauses, joins, table refs, the synthetic table-expression) from the CST, but at each
 * OUTERMOST EXPRESSION it hands the builder to the platform's own expression parser
 * ([SqlParser.parseValueExpression] → MysqlExpressionParsing.value_expression). That produces the
 * REAL platform expression subtree — function-call frames, operator nesting, CASE/BETWEEN/IN,
 * scalar subqueries, window clauses — byte-for-byte, because it is literally the code that emitted
 * the golden.
 *
 * "Delegation points" are the outermost expressions in: select-list items (namedExpression), WHERE /
 * HAVING / JOIN-ON conditions, GROUP BY items, and ORDER BY sort keys. During replay, when the
 * builder reaches a delegation point's start offset we call parseValueExpression and then VERIFY it
 * consumed exactly the delegation span (advanced to end+1, skipping only whitespace). Any greed
 * mismatch aborts the whole statement replay -> delegation fallback. Aliases (`expr AS a`) and bare
 * star projections (`*`) are handled by replay structure around the delegated/!delegated span.
 *
 * Safety contract: if ANTLR reports ANY syntax error, or any boundary/greed check fails, we consume
 * nothing (or roll back) and return false, so the caller falls through to the existing MySQL
 * delegation unchanged — "never worse than today".
 */
internal class CstReplayer(private val builder: PsiBuilder, private val parser: SqlParser) {

    /** One materialised STRUCTURE node: an absolute [start,stopExclusiveEnd] span and its type. */
    private class Node(
        val startOffset: Int,   // absolute offset of the first token (inclusive)
        val endOffset: Int,     // absolute offset of the last token's last char (inclusive)
        val type: IElementType,
        val seq: Int,           // pre-order DFS index; breaks ties when spans coincide
    ) {
        var marker: PsiBuilder.Marker? = null
    }

    /** Outermost-expression spans handed to the platform expression parser (start -> inclusive end). */
    private val delegations = HashMap<Int, Int>()

    /**
     * Attempt to replay the statement at the builder's current position onto typed platform PSI.
     * Covers the query family (SELECT / WITH / QUALIFY), the Doris statement-lead families the
     * mapping table understands (CREATE [MATERIALIZED] VIEW, CREATE TABLE, REFRESH, WARM UP, SWITCH,
     * CREATE JOB), and the DML long-tail forms with pinned platform shapes (single-table UPDATE,
     * DELETE [USING] — see [updateDeleteReplayable]).
     * Returns true iff a coherent PSI tree was produced (builder advanced to the statement's end,
     * exclusive of the terminating ';'); false with the builder untouched/rolled-back otherwise, so
     * the caller falls through to the existing lenient/delegation path unchanged.
     */
    fun tryReplayStatement(): Boolean {
        val statementStart = builder.currentOffset
        val statementText = extractStatementText(statementStart) ?: return false

        val parse = antlrParse(statementText) ?: return false // any syntax error -> bail, nothing consumed
        val root = parse.root
        val rootStop = root.stop ?: return false

        // Table-valued function in FROM (`FROM tasks(...)`, `FROM s3(...)`) — bail, consume nothing.
        // The whole TVF stack (DorisTableFunctions builtin overlay, DorisTypeSystem static schemas,
        // DorisHighlightInfoFilter's property-bag/open-relation rules) keys on the platform's
        // SqlFunctionCallExpression, which only MySQL delegation produces; the replayer has no mapping
        // for the call and would flatten it to a bare identifier + token run, losing name resolution,
        // column typing, and completion (dogfood 2026-07-08: tasks()/jobs()/S3() red in file editors).
        if (containsContext(root, "TableValuedFunctionContext")) return false

        // CTAS (`CREATE TABLE ... AS <query>`) — DECLINE, consume nothing (dogfood 2026-07-08: CTAS
        // key/order columns red). A CTAS defines its columns FROM the query, so there are no
        // columnDef PSI nodes for the ctasCols / DUPLICATE KEY / DISTRIBUTED identifier lists to
        // resolve against; replaying them as SQL_REFERENCE_LIST column references red-flags every
        // name. The lenient path (statement run + real query tail) is strictly better today.
        if (containsCtas(root)) return false

        // UPDATE / DELETE variant gate: only the forms whose platform shape is pinned replay (plain
        // single-table UPDATE; DELETE with/without USING). Doris-only tails DECLINE to delegation.
        if (!updateDeleteReplayable(root)) return false

        val nodes = ArrayList<Node>()
        val seq = intArrayOf(0)
        collect(root, parse.ruleNames, statementStart, nodes, seq, null, false)
        if (nodes.isEmpty()) return false

        // Honesty guard: the FIRST materialised node (pre-order => the outermost) must be a known
        // top-level statement kind spanning the entire statement. A statement family the grammar
        // ACCEPTS but the mapping table has no statement-lead entry for (CREATE TABLE LIKE, and any
        // future family that sneaks past wantsReplay) would otherwise replay as loose tokens + inner
        // identifier leaves with NO statement wrapper — breaking run-block boundaries. Decline instead:
        // never an accepted-but-shapeless statement.
        val statementEndOffset = statementStart + rootStop.stopIndex // absolute, inclusive
        val top = nodes.first()
        if (top.startOffset != statementStart || top.endOffset != statementEndOffset ||
            top.type !in ReplayMapping.TOP_STATEMENT_TYPES) return false

        return replay(nodes, statementEndOffset)
    }

    /** True iff the statement is a CTAS: a CreateTableContext with a direct `query` child. */
    private fun containsCtas(ctx: ParserRuleContext): Boolean {
        if (ctx.javaClass.simpleName == "CreateTableContext" && hasChildClass(ctx, "QueryContext")) return true
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is ParserRuleContext && containsCtas(child)) return true
        }
        return false
    }

    /**
     * UPDATE / DELETE variant gate (Part 2, statement long-tail). Replay covers exactly the forms whose
     * platform PSI shape is pinned by a golden:
     *  - UPDATE <tbl> SET ... [WHERE]           (golden 51 — single-table, no alias, no FROM tail)
     *  - DELETE FROM <tbl> [USING rels] [WHERE] (goldens 34/50 — no PARTITION spec, no alias)
     * Everything else DECLINES so the existing delegation/lenient path keeps handling it:
     *  - UPDATE ... FROM <rels> (Doris-only): no platform shape to reproduce; the FROM relations would
     *    end up loose runs inside a typed statement platform inspections may prod.
     *  - a target `tableAlias`: the platform wraps aliased targets differently (unpinned) — decline
     *    rather than guess.
     *  - DELETE ... PARTITION/PARTITIONS (...): MySQL's typed partition clause (singular form) is
     *    richer than a replayed token run — delegation is strictly better; the plural form is lenient
     *    by dispatch. Partition names are also not column refs (the REFRESH-MV dogfood class).
     * Statements that are neither UPDATE nor DELETE pass through untouched.
     */
    private fun updateDeleteReplayable(root: ParserRuleContext): Boolean {
        val stmt = findContext(root, "UpdateContext") ?: findContext(root, "DeleteContext") ?: return true
        if (hasNonEmptyChildOfClass(stmt, "TableAliasContext")) return false
        return when (stmt.javaClass.simpleName) {
            "UpdateContext" -> !hasNonEmptyChildOfClass(stmt, "FromClauseContext")
            else -> !hasNonEmptyChildOfClass(stmt, "PartitionSpecContext")
        }
    }

    /** First context of the given simpleName in the subtree (inclusive), or null. */
    private fun findContext(ctx: ParserRuleContext, simpleName: String): ParserRuleContext? {
        if (ctx.javaClass.simpleName == simpleName) return ctx
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is ParserRuleContext) findContext(child, simpleName)?.let { return it }
        }
        return null
    }

    /** True iff [ctx] has a direct child context of [simpleName] that actually spans tokens. */
    private fun hasNonEmptyChildOfClass(ctx: ParserRuleContext, simpleName: String): Boolean {
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i) as? ParserRuleContext ?: continue
            if (child.javaClass.simpleName == simpleName && hasTokens(child)) return true
        }
        return false
    }

    // --- ANTLR parse ---------------------------------------------------------------------------

    private class ParseResult(val root: DorisParser.SingleStatementContext, val ruleNames: Array<String>)

    /** Parse [text] as a single statement; null if the Doris grammar reports any lex/parse error. */
    private fun antlrParse(text: String): ParseResult? {
        var hadError = false
        val listener = object : BaseErrorListener() {
            override fun syntaxError(
                recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int,
                charPositionInLine: Int, msg: String, e: RecognitionException?,
            ) {
                hadError = true
            }
        }
        val lexer = SHARED.newLexer(text)
        lexer.removeErrorListeners(); lexer.addErrorListener(listener)
        val parser = SHARED.newParser(lexer)
        parser.removeErrorListeners(); parser.addErrorListener(listener)
        parser.errorHandler = DefaultErrorStrategy()
        val root = try {
            parser.singleStatement()
        } catch (t: Exception) {
            return null
        }
        if (hadError || root.stop == null) return null
        return ParseResult(root, parser.ruleNames)
    }

    // --- CST walk ------------------------------------------------------------------------------

    /**
     * Pre-order DFS. Emits a Node for each mapped structure context plus the synthetic wrapper nodes,
     * and registers DELEGATION POINTS for outermost expressions (skipping their subtrees entirely —
     * the platform expression parser reproduces them at replay time). [parentClass] is the ANTLR
     * context simpleName of [ctx]'s parent, used both for context-sensitive mapping and to recognise
     * an expression sitting directly under a delegating clause/item.
     */
    private fun collect(
        ctx: ParserRuleContext, ruleNames: Array<String>, absStart: Int,
        out: MutableList<Node>, seq: IntArray, parentClass: String?, branchIsQueryExpr: Boolean,
    ) {
        val cls = ctx.javaClass.simpleName

        // Delegation-point detection: is this the outermost expression of a delegating clause/item?
        // MvPartition carve-out: only its FUNCTION form (`PARTITION BY (date_trunc(dt, 'day'))`)
        // delegates — the platform renders a real SQL_FUNCTION_CALL that resolves as a builtin. The
        // bare-COLUMN form (`PARTITION BY (event_date)`) must NOT delegate: the platform would shape
        // it as a SQL_COLUMN_REFERENCE, which resolve-checks against a scope the CREATE MATERIALIZED
        // VIEW statement does not provide (the dogfood class of red bare identifiers). Skipping the
        // delegation lets normal descent materialise a bare SQL_IDENTIFIER leaf instead — stable, quiet.
        val mvPartitionBareColumn =
            cls == "MvPartitionContext" && !containsContext(ctx, "FunctionCallExpressionContext")
        if (isDelegationExpr(parentClass, ruleNameOf(ctx, ruleNames)) && hasTokens(ctx) && !mvPartitionBareColumn) {
            val bareStar = bareStarOf(ctx)
            if (bareStar != null) {
                // Bare `*` / `t.*` are select-all, which the platform models as SQL_COLUMN_REFERENCE
                // rather than a value expression (golden 21). Emit it as structure, don't delegate.
                out.add(Node(absStart + bareStar.start.startIndex, absStart + bareStar.stop.stopIndex,
                    SQL_COLUMN_REFERENCE, seq[0]++))
            } else {
                delegations[absStart + ctx.start.startIndex] = absStart + ctx.stop.stopIndex
            }
            return // the expression subtree is delegated / handled — never emit CST nodes below it
        }

        // Query-level structure. A plain query's `query`/`queryPrimary`/`queryTerm` layers collapse to a
        // single SQL_QUERY_EXPRESSION. A UNION or a CTE makes the `query` a COMPOUND wrapper instead, in
        // which the branch(es) each become their own SQL_QUERY_EXPRESSION:
        //  - UNION: `query` -> SQL_UNION_EXPRESSION (flat, spanning the whole query so a trailing
        //    union-level ORDER BY / LIMIT lands inside it — golden 26); every SetOperation layer is
        //    transparent; each branch queryPrimary -> SQL_QUERY_EXPRESSION.
        //  - CTE (`WITH ...`): `query` -> SQL_WITH_QUERY_EXPRESSION; the `cte` -> SQL_WITH_CLAUSE, each
        //    `aliasQuery` -> SQL_NAMED_QUERY_DEFINITION, and the main body queryPrimary ->
        //    SQL_QUERY_EXPRESSION (golden 01).
        // A parenthesised branch/subquery (`( query )`) -> SQL_PARENTHESIZED_QUERY_EXPRESSION wrapping an
        // inner SQL_QUERY_EXPRESSION (golden 26). branchIsQueryExpr is threaded from the enclosing
        // `query`: true iff that query is compound, so its direct branch queryPrimaries materialise.
        val isUnion = cls == "QueryContext" && firstRuleChild(ctx)?.javaClass?.simpleName == "SetOperationContext"
        val isWith = cls == "QueryContext" && hasChildClass(ctx, "CteContext")

        val type: IElementType? = when (cls) {
            "QueryContext" -> when {
                isUnion -> ReplayMapping.UNION_EXPRESSION
                isWith -> ReplayMapping.WITH_QUERY_EXPRESSION
                else -> ReplayMapping.QUERY_EXPRESSION
            }
            "SetOperationContext" -> null
            "QueryPrimaryDefaultContext" -> if (branchIsQueryExpr) ReplayMapping.QUERY_EXPRESSION else null
            "SubqueryContext" -> ReplayMapping.PARENTHESIZED_QUERY_EXPRESSION
            "CteContext" -> ReplayMapping.WITH_CLAUSE
            "AliasQueryContext" -> ReplayMapping.NAMED_QUERY_DEFINITION
            else -> ReplayMapping.BY_CONTEXT_CLASS[cls]
                ?: ReplayMapping.resolveContextual(
                    cls, parentClass,
                    hasAncestorClass = { name ->
                        generateSequence(ctx.parent) { it.parent }.any { it.javaClass.simpleName == name }
                    },
                ) { rule -> hasNonEmptyChildRule(ctx, ruleNames, rule) }
        }
        if (type != null) addNode(ctx, absStart, type, out, seq)

        // Chained joins: ANTLR keeps a flat `relation = relationPrimary joinRelation*`, but the platform
        // left-nests one SQL_JOIN_EXPRESSION per join (golden 21). Emit N nested nodes, all starting at
        // the relation's start and ending at successive joinRelation stops, outermost (widest) first so
        // the open order (by seq) nests correctly.
        if (cls == "RelationContext") emitNestedJoins(ctx, ruleNames, absStart, out, seq)

        // Derived table: `( query ) AS alias` (relationPrimary # aliasedQuery). Platform shape is
        // SQL_AS_EXPRESSION > SQL_PARENTHESIZED_QUERY_EXPRESSION ( ( <query> ) ) + AS + alias (golden 24).
        if (cls == "AliasedQueryContext") emitDerivedTable(ctx, ruleNames, absStart, out, seq)

        // Qualified name `db.tbl` (multipartIdentifier under a reference-bearing parent — a table name,
        // a CREATE VIEW/TABLE object name, REFRESH/WARM UP table): the platform nests the qualifier parts
        // in SQL_REFERENCE, leaving the final part a bare SQL_IDENTIFIER (golden 02, MySQL CREATE VIEW shape).
        if (cls == "MultipartIdentifierContext" && parentClass in ReplayMapping.REFERENCE_PARENTS) {
            emitMultipartQualifiers(ctx, ruleNames, absStart, out, seq)
        }

        // CREATE [MATERIALIZED] VIEW ... AS <query>: the platform wraps `AS <query>` in a single
        // SQL_AS_QUERY_CLAUSE (MySQL CREATE VIEW shape). The Doris CST has the AS terminal and the query as
        // bare siblings under the create-view context, so we SYNTHESISE the wrapper spanning [AS, query-end].
        // Emit BEFORE descending so it opens ahead of the query expression it contains.
        if (cls in ReplayMapping.AS_QUERY_PARENTS) emitAsQueryClause(ctx, absStart, out, seq)

        // NB (dogfood 2026-07-08 item 7, investigated and DECLINED): the one structural delta between
        // the replayed Doris CREATE TABLE and the platform MySQL shape is the missing
        // SQL_TABLE_ELEMENT_LIST wrapper around the parenthesised definitions. It CANNOT be
        // synthesised like the SQL_TABLE_EXPRESSION one: SqlCompositeElementTypes.SQL_TABLE_ELEMENT_LIST
        // is a SqlLazyElementType (IReparseableElementType chameleon) that the platform only ever
        // creates COLLAPSED (its PSI is SqlLazyParseablePsiElement, built by the AST factory from
        // text). A replay marker.done(type) with children hits the hard AssertionError in
        // SqlElementFactory.createCompositeElement (MysqlElementFactory has no PSI mapping for it),
        // and marker.collapse(type) would surrender the span to a LAZY REPARSE by the generated
        // MySQL DDL grammar — reintroducing exactly the Doris mis-parses replay exists to avoid
        // (golden/mysql/doris/24: "<type> expected, got 'STRING'", "BTREE or HASH expected, got
        // 'INVERTED'") and violating the pinned zero-PsiErrorElement replay contract. The replayed
        // definitions therefore deliberately stay direct children of the statement.

        // CREATE TABLE inline index (`indexDef`): the IndexDefContext itself maps to SQL_INDEX_DEFINITION
        // (BY_CONTEXT_CLASS); here we wrap its NAME identifier in SQL_INDEX_REFERENCE so the index name is
        // a typed, navigable reference (MySQL inline-index shape) rather than a bare SQL_IDENTIFIER.
        if (cls == "IndexDefContext") emitIndexReference(ctx, ruleNames, absStart, out, seq)

        // CREATE JOB's DO-body INSERT (`supportedDmlStatement # insertTable`): synthesise the platform's
        // insert PSI around the target reference and the replayed query. Emit BEFORE descending so the
        // wrappers open ahead of the table reference / query expression they contain.
        if (cls == "InsertTableContext") emitInsertSkeleton(ctx, absStart, out, seq)

        // UPDATE / DELETE (Part 2): synthesise the platform DML-instruction wrappers around the
        // target/relations the CST provides as bare siblings. Emit BEFORE descending so the wrappers
        // open ahead of the table reference / assignments they contain.
        if (cls == "UpdateContext") emitUpdateSkeleton(ctx, absStart, out, seq)
        if (cls == "DeleteContext") emitDeleteSkeleton(ctx, absStart, out, seq)

        // Synthetic SQL_TABLE_EXPRESSION: the platform groups the query BODY (FROM + WHERE + GROUP BY +
        // HAVING) into one node the flat ANTLR grammar lacks. It stops before the queryOrganization
        // (ORDER BY / LIMIT), which the platform keeps as a sibling. Emit its open BEFORE descending so
        // its seq precedes the FROM clause's.
        if (cls == ReplayMapping.QUERY_SPECIFICATION_CLASS) emitTableExpression(ctx, absStart, out, seq)

        // Aliased select item (`expr AS a` / `expr a`): the platform wraps the whole named expression
        // in SQL_AS_EXPRESSION (golden 06). The expression is delegated as usual; the alias identifier
        // materialises via the UnquotedIdentifierContext -> SQL_IDENTIFIER mapping, and the bare AS
        // keyword replays inside this wrapper. Emit the wrapper BEFORE descending so it opens ahead of
        // the delegation point that shares its start offset.
        if (cls == ReplayMapping.NAMED_EXPRESSION_CLASS &&
            hasNonEmptyChildRule(ctx, ruleNames, ReplayMapping.ALIAS_TEXT_RULE)) {
            addNode(ctx, absStart, ReplayMapping.AS_EXPRESSION, out, seq)
        }

        // Synthetic leaf wrappers around bare terminals the CST does NOT wrap in a rule node but the
        // platform PSI does: DISTINCT -> SQL_SELECT_OPTION; each LIMIT integer -> SQL_NUMERIC_LITERAL.
        when (cls) {
            ReplayMapping.SELECT_CLAUSE_CLASS ->
                emitTerminalWrappers(ctx, absStart, out, seq, ReplayMapping.SELECT_OPTION) { it.equals("DISTINCT", ignoreCase = true) }
            ReplayMapping.LIMIT_CLAUSE_CLASS ->
                emitTerminalWrappers(ctx, absStart, out, seq, ReplayMapping.NUMERIC_LITERAL) { t -> t.isNotEmpty() && t.all(Char::isDigit) }
        }

        // A compound query (union / CTE) makes its direct branch queryPrimaries into SQL_QUERY_EXPRESSION;
        // a nested `query` recomputes its own compound-ness, so the flag resets at each query boundary.
        val childBranchFlag = if (cls == "QueryContext") (isUnion || isWith) else branchIsQueryExpr
        val n = ctx.childCount
        for (i in 0 until n) {
            val child = ctx.getChild(i)
            if (child is ParserRuleContext) collect(child, ruleNames, absStart, out, seq, cls, childBranchFlag)
        }
    }

    /** True iff any node in [ctx]'s subtree (inclusive) is a context of the given simpleName. */
    private fun containsContext(ctx: ParserRuleContext, simpleName: String): Boolean {
        if (ctx.javaClass.simpleName == simpleName) return true
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i)
            if (child is ParserRuleContext && containsContext(child, simpleName)) return true
        }
        return false
    }

    /** First direct child of [ctx] that is a rule context, or null. */
    private fun firstRuleChild(ctx: ParserRuleContext): ParserRuleContext? {
        for (i in 0 until ctx.childCount) (ctx.getChild(i) as? ParserRuleContext)?.let { return it }
        return null
    }

    /** True iff [ctx] has a direct child rule context of the given simpleName. */
    private fun hasChildClass(ctx: ParserRuleContext, simpleName: String): Boolean {
        for (i in 0 until ctx.childCount) if ((ctx.getChild(i) as? ParserRuleContext)?.javaClass?.simpleName == simpleName) return true
        return false
    }

    /**
     * Emit the nested SQL_JOIN_EXPRESSION chain for a `relation` with joins. For joins j1..jN, the
     * i-th (widest) node spans [relation.start, jI.stop]; widest first so open order (ascending seq)
     * places ancestors before descendants. No joins -> nothing (the relation is transparent).
     */
    private fun emitNestedJoins(ctx: ParserRuleContext, ruleNames: Array<String>, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val joins = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .filter { ruleNameOf(it, ruleNames) == "joinRelation" && hasTokens(it) }
        if (joins.isEmpty()) return
        val relStart = absStart + ctx.start.startIndex
        for (j in joins.indices.reversed()) { // widest (last join) first -> smallest seq
            out.add(Node(relStart, absStart + joins[j].stop.stopIndex, ReplayMapping.JOIN_EXPRESSION, seq[0]++))
        }
    }

    /**
     * Emit the derived-table wrappers for an `aliasedQuery` (`( query ) [AS] alias`): SQL_AS_EXPRESSION
     * over the whole thing (when aliased) and SQL_PARENTHESIZED_QUERY_EXPRESSION over the `( ... )`. The
     * inner query, and the alias identifier, materialise through normal recursion.
     */
    private fun emitDerivedTable(ctx: ParserRuleContext, ruleNames: Array<String>, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        if (hasNonEmptyChildRule(ctx, ruleNames, ReplayMapping.TABLE_ALIAS_RULE)) {
            addNode(ctx, absStart, ReplayMapping.AS_EXPRESSION, out, seq) // outermost wrapper
        }
        val open = firstTerminal(ctx, "(") ?: return
        val close = lastTerminal(ctx, ")") ?: return
        out.add(Node(absStart + open, absStart + close, ReplayMapping.PARENTHESIZED_QUERY_EXPRESSION, seq[0]++))
    }

    /**
     * For a multi-part table name `p1.p2.….pn`, emit the platform's nested SQL_REFERENCE qualifiers:
     * one per prefix `p1..pi` for i in 1..n-1 (widest first), leaving `pn` as a bare SQL_IDENTIFIER
     * (golden 02). A single-part name emits nothing.
     */
    private fun emitMultipartQualifiers(ctx: ParserRuleContext, ruleNames: Array<String>, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val parts = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .filter { ruleNameOf(it, ruleNames) == "errorCapturingIdentifier" && hasTokens(it) }
        if (parts.size < 2) return
        val start = absStart + parts.first().start.startIndex
        for (i in (parts.size - 1) downTo 1) { // widest prefix first -> smallest seq (outermost)
            out.add(Node(start, absStart + parts[i - 1].stop.stopIndex, ReplayMapping.REFERENCE, seq[0]++))
        }
    }

    /**
     * Wrap the NAME of a CREATE TABLE inline `indexDef` in SQL_INDEX_REFERENCE. The name is the first
     * direct `identifier` child (`INDEX <name> (cols) ...`); its inner strictIdentifier still materialises
     * SQL_IDENTIFIER, nesting inside the reference (MySQL SQL_INDEX_DEFINITION > SQL_INDEX_REFERENCE shape).
     * Nothing emitted if the index is unnamed (defensive — the definition then holds just its column list).
     */
    private fun emitIndexReference(ctx: ParserRuleContext, ruleNames: Array<String>, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val name = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .firstOrNull { ruleNameOf(it, ruleNames) == "identifier" && hasTokens(it) } ?: return
        out.add(Node(absStart + name.start.startIndex, absStart + name.stop.stopIndex, ReplayMapping.INDEX_REFERENCE, seq[0]++))
    }

    private fun firstTerminal(ctx: ParserRuleContext, text: String): Int? {
        for (i in 0 until ctx.childCount) {
            val t = ctx.getChild(i) as? TerminalNode ?: continue
            if (t.text == text) return t.symbol.startIndex
        }
        return null
    }

    /** Start offset of the first direct terminal child whose text equals [text] case-insensitively. */
    private fun firstTerminalCi(ctx: ParserRuleContext, text: String): Int? {
        for (i in 0 until ctx.childCount) {
            val t = ctx.getChild(i) as? TerminalNode ?: continue
            if (t.text.equals(text, ignoreCase = true)) return t.symbol.startIndex
        }
        return null
    }

    private fun lastTerminal(ctx: ParserRuleContext, text: String): Int? {
        for (i in ctx.childCount - 1 downTo 0) {
            val t = ctx.getChild(i) as? TerminalNode ?: continue
            if (t.text == text) return t.symbol.stopIndex
        }
        return null
    }

    /**
     * True iff an expression node of ANTLR rule [childRule] sitting directly under [parentClass] is
     * the OUTERMOST expression of a delegating clause/item — i.e., a hand-off point to the platform
     * expression parser. Keyed on (parent class, child *rule name*) so it is independent of which
     * labelled expression subclass (Predicated/Comparison/ArithmeticBinary/...) the node happens to be.
     */
    private fun isDelegationExpr(parentClass: String?, childRule: String?): Boolean = when (parentClass) {
        "NamedExpressionContext" -> childRule == "expression"          // select-list item
        "WhereClauseContext" -> childRule == "booleanExpression"       // WHERE condition
        "HavingClauseContext" -> childRule == "booleanExpression"      // HAVING condition
        "JoinCriteriaContext" -> childRule == "booleanExpression"      // JOIN ... ON condition
        "ExpressionWithOrderContext" -> childRule == "expression"      // GROUP BY item
        "SortItemContext" -> childRule == "expression"                 // ORDER BY sort key
        "QualifyClauseContext" -> childRule == "booleanExpression"     // QUALIFY condition
        // DDL partition expressions (Task 1c): the column/function key inside a PARTITION BY clause. Each
        // hands off to the platform value-expression parser so `date_trunc(col, 'day')` comes out a real
        // SQL_FUNCTION_CALL (and a bare `col` a SQL_COLUMN_REFERENCE) rather than a loose identifier +
        // column-ref + string-literal token run. Covers both the CREATE TABLE `PARTITION BY [RANGE|LIST]
        // (k, ...)` list (identityOrFunction per key) and the CREATE MATERIALIZED VIEW `PARTITION BY (k)`
        // (mvPartition). DISTRIBUTED BY HASH / DUPLICATE KEY take an identifierList, not expressions, so
        // they keep their SQL_REFERENCE_LIST shape and are not delegated here.
        "IdentityOrFunctionListContext" -> childRule == "identityOrFunction"
        "CreateMTMVContext" -> childRule == "mvPartition"
        // UPDATE ... SET col = <expr>: the RHS of each assignment (the LHS is a multipartIdentifier,
        // shaped as SQL_COLUMN_REFERENCE via REFERENCE_PARENTS — golden 51).
        "UpdateAssignmentContext" -> childRule == "expression"
        else -> false
    }

    /**
     * Synthesise the SQL_AS_QUERY_CLAUSE of a CREATE [MATERIALIZED] VIEW: it spans from the `AS` terminal
     * to the stop of the view's defining `query`. The `AS` keyword and the query replay INSIDE it (the
     * query via the normal query machinery). Nothing emitted if either landmark is missing (defensive —
     * the statement then rolls back cleanly rather than producing a malformed wrapper).
     */
    private fun emitAsQueryClause(ctx: ParserRuleContext, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val asStart = firstTerminalCi(ctx, "AS") ?: return
        val query = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .firstOrNull { it.javaClass.simpleName == "QueryContext" && hasTokens(it) } ?: return
        out.add(Node(absStart + asStart, absStart + query.stop.stopIndex, ReplayMapping.AS_QUERY_CLAUSE, seq[0]++))
    }

    /**
     * Synthesise the platform insert PSI for a CREATE JOB DO-body `insertTable`. Target shape (dumped
     * live from the platform's own MySQL `CREATE EVENT ... DO INSERT INTO db.t SELECT ...` — the direct
     * analog of a DO-body insert, and identical to the delegation-rendered inserts in
     * golden/doris/doris/09/11):
     *
     *   SQL_INSERT_STATEMENT            [INSERT .. query-end]
     *     SQL_INSERT_DML_INSTRUCTION    [INTO .. query-end]     <- the shape INSERT completion consults
     *       SQL_TABLE_COLUMN_LIST       [target span]
     *         SQL_TABLE_REFERENCE       (via the InsertTableContext entry in REFERENCE_PARENTS)
     *       SQL_QUERY_EXPRESSION        (normal query replay)
     *
     * VARIANT GATE: only the plain `INSERT INTO <multipartIdentifier> <query>` form is shaped — the form
     * CREATE JOB bodies use in practice (corpus doris/14). The grammar's other insertTable variants
     * (INSERT OVERWRITE TABLE, PARTITION spec, WITH LABEL, explicit column list, per-insert hints, CTE)
     * are DEFERRED: for those the skeleton is not emitted and the insert stays a token run inside the
     * job SQL_STATEMENT with its query still replayed (lenient-parity, zero error elements) — never a
     * half-right typed shape.
     */
    private fun emitInsertSkeleton(ctx: ParserRuleContext, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val intoStart = firstTerminalCi(ctx, "INTO") ?: return // OVERWRITE-form etc.: no INTO -> stay a run
        val ruleKids = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .filter { hasTokens(it) }
        // Plain form only: exactly the target multipart + the source query, in that order.
        if (ruleKids.size != 2) return
        val target = ruleKids[0].takeIf { it.javaClass.simpleName == "MultipartIdentifierContext" } ?: return
        val query = ruleKids[1].takeIf { it.javaClass.simpleName == "QueryContext" } ?: return
        val end = ctx.stop?.stopIndex ?: return
        if (query.stop.stopIndex != end) return // trailing grammar tail we don't model -> stay a run
        addNode(ctx, absStart, ReplayMapping.INSERT_STATEMENT, out, seq)
        out.add(Node(absStart + intoStart, absStart + end, ReplayMapping.INSERT_DML_INSTRUCTION, seq[0]++))
        out.add(Node(absStart + target.start.startIndex, absStart + target.stop.stopIndex,
            ReplayMapping.TABLE_COLUMN_LIST, seq[0]++))
    }

    /**
     * Synthesise the platform's UPDATE body wrappers (golden 51 / golden/mysql/mysql-core/33):
     *
     *   SQL_UPDATE_STATEMENT           (UpdateContext via BY_CONTEXT_CLASS)
     *     SQL_UPDATE_DML_INSTRUCTION   [target .. statement-end]
     *       SQL_TABLE_REFERENCE        (target multipart via REFERENCE_PARENTS)
     *       SQL_SET_CLAUSE             [SET .. last assignment]
     *         SQL_SET_ASSIGNMENT ...   (UpdateAssignmentContext; RHS delegated)
     *       SQL_WHERE_CLAUSE           (normal whereClause mapping)
     *
     * Landmarks are guaranteed by [updateDeleteReplayable] + the grammar (UPDATE always has a target,
     * SET, and at least one assignment); missing ones emit nothing and the statement then fails the
     * top-level guard or replays without the wrapper — caught by the pinned goldens, never silent.
     */
    private fun emitUpdateSkeleton(ctx: ParserRuleContext, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val end = ctx.stop?.stopIndex ?: return
        val target = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .firstOrNull { it.javaClass.simpleName == "MultipartIdentifierContext" && hasTokens(it) } ?: return
        val setStart = firstTerminalCi(ctx, "SET") ?: return
        val assignments = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .firstOrNull { it.javaClass.simpleName == "UpdateAssignmentSeqContext" && hasTokens(it) } ?: return
        out.add(Node(absStart + target.start.startIndex, absStart + end, ReplayMapping.UPDATE_DML_INSTRUCTION, seq[0]++))
        out.add(Node(absStart + setStart, absStart + assignments.stop.stopIndex, ReplayMapping.SET_CLAUSE, seq[0]++))
    }

    /**
     * Synthesise the platform's DELETE body wrappers. Two pinned shapes:
     *  - plain:  SQL_DELETE_DML_INSTRUCTION [FROM .. end] > SQL_FROM_CLAUSE [FROM .. target] + WHERE
     *    (golden/doris/doris/50, third statement)
     *  - USING:  SQL_DELETE_DML_INSTRUCTION [FROM .. end] > SQL_CLAUSE [FROM .. target] +
     *    SQL_FROM_CLAUSE [USING .. relations-end] + WHERE (golden/mysql/mysql-core/34)
     * The USING relations reuse the query FROM machinery (nested joins, table refs, ON delegation).
     */
    private fun emitDeleteSkeleton(ctx: ParserRuleContext, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val end = ctx.stop?.stopIndex ?: return
        val fromStart = firstTerminalCi(ctx, "FROM") ?: return
        val target = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .firstOrNull { it.javaClass.simpleName == "MultipartIdentifierContext" && hasTokens(it) } ?: return
        val usingStart = firstTerminalCi(ctx, "USING")
        val relations = (0 until ctx.childCount).mapNotNull { ctx.getChild(it) as? ParserRuleContext }
            .firstOrNull { it.javaClass.simpleName == "RelationsContext" && hasTokens(it) }
        out.add(Node(absStart + fromStart, absStart + end, ReplayMapping.DELETE_DML_INSTRUCTION, seq[0]++))
        if (usingStart != null && relations != null) {
            out.add(Node(absStart + fromStart, absStart + target.stop.stopIndex, ReplayMapping.GENERIC_CLAUSE, seq[0]++))
            out.add(Node(absStart + usingStart, absStart + relations.stop.stopIndex, ReplayMapping.FROM_CLAUSE, seq[0]++))
        } else {
            out.add(Node(absStart + fromStart, absStart + target.stop.stopIndex, ReplayMapping.FROM_CLAUSE, seq[0]++))
        }
    }

    /**
     * If [exprCtx] is a bare star projection (`*`, no arguments), return the StarContext so the caller
     * can model it as SQL_COLUMN_REFERENCE. Descends the single-rule-child chain
     * (expression -> booleanExpression -> valueExpression -> primaryExpression) and returns the
     * StarContext only when it has NO rule-context children (pure `*`); a qualified `t.*` returns null
     * and is left to delegation (fails cleanly if the platform can't consume it).
     */
    private fun bareStarOf(exprCtx: ParserRuleContext): ParserRuleContext? {
        var c: ParserRuleContext = exprCtx
        while (true) {
            if (c.javaClass.simpleName == "StarContext") {
                val hasRuleChild = (0 until c.childCount).any { c.getChild(it) is ParserRuleContext }
                return if (hasRuleChild) null else c
            }
            val ruleKids = (0 until c.childCount).mapNotNull { c.getChild(it) as? ParserRuleContext }
            if (ruleKids.size != 1) return null
            c = ruleKids[0]
        }
    }

    /**
     * Emit the synthetic SQL_TABLE_EXPRESSION for a query specification: spans from the FROM clause to
     * the stop of the LAST body clause (FROM / WHERE / GROUP BY / HAVING). Nothing emitted if there is
     * no FROM (bare `SELECT 1` has no table expression).
     */
    private fun emitTableExpression(ctx: ParserRuleContext, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val body = ArrayList<ParserRuleContext>()
        for (i in 0 until ctx.childCount) {
            val child = ctx.getChild(i) as? ParserRuleContext ?: continue
            if (child.javaClass.simpleName in ReplayMapping.BODY_CLAUSE_CLASSES && hasTokens(child)) body.add(child)
        }
        val from = body.firstOrNull { it.javaClass.simpleName == ReplayMapping.FROM_CLAUSE_CLASS } ?: return
        val bodyEnd = body.maxOf { it.stop.stopIndex }
        out.add(Node(absStart + from.start.startIndex, absStart + bodyEnd, ReplayMapping.TABLE_EXPRESSION, seq[0]++))
    }

    /** Emit a synthetic wrapper [type] around each direct terminal child of [ctx] whose text [matches]. */
    private fun emitTerminalWrappers(
        ctx: ParserRuleContext, absStart: Int, out: MutableList<Node>, seq: IntArray,
        type: IElementType, matches: (String) -> Boolean,
    ) {
        for (i in 0 until ctx.childCount) {
            val term = ctx.getChild(i) as? TerminalNode ?: continue
            val tok = term.symbol ?: continue
            if (tok.startIndex > tok.stopIndex) continue
            if (matches(term.text)) out.add(Node(absStart + tok.startIndex, absStart + tok.stopIndex, type, seq[0]++))
        }
    }

    private fun hasTokens(ctx: ParserRuleContext): Boolean {
        val s = ctx.start ?: return false
        val e = ctx.stop ?: return false
        return s.startIndex <= e.stopIndex
    }

    private fun addNode(ctx: ParserRuleContext, absStart: Int, type: IElementType, out: MutableList<Node>, seq: IntArray) {
        val s = ctx.start ?: return
        val e = ctx.stop ?: return
        if (s.startIndex > e.stopIndex) return // empty/error node (start past stop) — no tokens, skip
        out.add(Node(absStart + s.startIndex, absStart + e.stopIndex, type, seq[0]++))
    }

    /** Pack an inclusive [start,end] token span into a single long key. */
    private fun spanKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xffffffffL)

    private fun ruleNameOf(ctx: ParserRuleContext, ruleNames: Array<String>): String? =
        ctx.ruleIndex.takeIf { it in ruleNames.indices }?.let { ruleNames[it] }

    /** True iff [ctx] has a direct child of the given rule with a real (non-empty) token span. */
    private fun hasNonEmptyChildRule(ctx: ParserRuleContext, ruleNames: Array<String>, rule: String): Boolean {
        val children = ctx.children ?: return false
        return children.any {
            it is ParserRuleContext && ruleNameOf(it, ruleNames) == rule &&
                it.start != null && it.stop != null && it.start.startIndex <= it.stop.stopIndex
        }
    }

    // --- Replay --------------------------------------------------------------------------------

    /**
     * Walk the builder's tokens; open markers at node starts (outermost first) before advancing a
     * token, close markers at node ends (innermost first) after advancing. At a delegation point,
     * hand off to the platform expression parser instead of a single-token step. Guarded by a
     * rollback marker: any structural surprise (a boundary that misses a token edge, a delegation
     * that over/under-runs its span) restores the builder and returns false.
     */
    private fun replay(nodes: List<Node>, statementEndOffset: Int): Boolean {
        val opensAt = HashMap<Int, MutableList<Node>>()
        val closesAt = HashMap<Int, MutableList<Node>>()
        for (node in nodes) {
            opensAt.getOrPut(node.startOffset) { ArrayList() }.add(node)
            closesAt.getOrPut(node.endOffset) { ArrayList() }.add(node)
        }
        // outermost first on open (ancestors have smaller seq), innermost first on close.
        opensAt.values.forEach { it.sortBy { n -> n.seq } }
        closesAt.values.forEach { it.sortByDescending { n -> n.seq } }

        // Single-token identifier leaves that may need keyword->identifier remapping (see below).
        val identSpans = HashSet<Long>()
        for (node in nodes) if (node.type === SQL_IDENTIFIER) identSpans.add(spanKey(node.startOffset, node.endOffset))

        val rollback = builder.mark()
        val stack = ArrayDeque<Node>()

        while (!builder.eof() && builder.currentOffset <= statementEndOffset) {
            val tokenStart = builder.currentOffset

            opensAt[tokenStart]?.forEach { node ->
                node.marker = builder.mark()
                stack.addLast(node)
            }

            // Delegation point: hand the span to the platform expression parser rather than replaying
            // it token-by-token, so function-call frames et al. come out exactly as the platform emits.
            val delEnd = delegations[tokenStart]
            if (delEnd != null) {
                if (!delegateExpression(delEnd)) { rollback.rollbackTo(); return false }
                if (!closeAt(delEnd, closesAt, stack)) { rollback.rollbackTo(); return false }
                continue
            }

            val tokenText = builder.tokenText ?: break
            val tokenEnd = tokenStart + tokenText.length - 1

            // Keyword-as-identifier remap: the platform's generated `identifier` rule converts a
            // non-reserved keyword token (e.g. NAME) to its identifier form (NAME_IDENT) via
            // SqlKeywordTokenType.getIdentifierToken(). Our raw-token replay must do the same so the
            // leaf token type matches the golden. Only for a token that IS a whole SQL_IDENTIFIER leaf.
            if (spanKey(tokenStart, tokenEnd) in identSpans) {
                (builder.tokenType as? SqlKeywordTokenType)?.identifierToken?.let { builder.remapCurrentToken(it) }
            }

            builder.advanceLexer()

            if (!closeAt(tokenEnd, closesAt, stack)) { rollback.rollbackTo(); return false }
        }

        if (stack.isNotEmpty()) { // some node never closed on a token boundary -> misalignment
            rollback.rollbackTo()
            return false
        }
        rollback.drop() // commit: keep the materialised markers, discard only the rollback anchor
        return true
    }

    /** Close every node ending at [offset], innermost-first, verifying it is the current stack top. */
    private fun closeAt(offset: Int, closesAt: Map<Int, List<Node>>, stack: ArrayDeque<Node>): Boolean {
        val closing = closesAt[offset] ?: return true
        for (node in closing) {
            val top = stack.removeLastOrNull()
            if (top !== node) return false // boundaries didn't nest as expected
            node.marker!!.done(node.type)
        }
        return true
    }

    /**
     * Delegate the expression starting at the builder's current position to the platform's own
     * value-expression parser, then verify it consumed EXACTLY up to [delEndInclusive] — the builder
     * must now sit past that offset with only whitespace skipped in between (maximal, no over/under
     * run). parseValueExpression(builder, 0, /*noError*/ true, ...) suppresses the "expression
     * expected" diagnostic; we judge success purely by greed.
     */
    private fun delegateExpression(delEndInclusive: Int): Boolean {
        parser.parseValueExpression(builder, 0, true, true)
        val next = builder.currentOffset
        if (next <= delEndInclusive) return false // under-ran the span (or consumed nothing)
        val text = builder.originalText
        for (i in (delEndInclusive + 1) until next) {
            if (!text[i].isWhitespace()) return false // over-ran past the span into real tokens
        }
        return true
    }

    // --- Statement text extraction -------------------------------------------------------------

    /**
     * The current statement's text: from the builder's position up to (excluding) the terminating
     * ';' or EOF. A minimal quote/comment-aware scan so a ';' inside a string/comment doesn't cut
     * the statement short — sufficient for the PoC.
     */
    private fun extractStatementText(start: Int): String? {
        val original = builder.originalText
        val len = original.length
        if (start >= len) return null
        var i = start
        while (i < len) {
            val c = original[i]
            when (c) {
                ';' -> return original.subSequence(start, i).toString()
                '\'', '"', '`' -> i = skipQuoted(original, i, c)
                '-' -> if (i + 1 < len && original[i + 1] == '-') { i = skipLineComment(original, i); continue } else i++
                '/' -> if (i + 1 < len && original[i + 1] == '*') { i = skipBlockComment(original, i); continue } else i++
                else -> i++
            }
        }
        return original.subSequence(start, len).toString()
    }

    private fun skipQuoted(text: CharSequence, open: Int, quote: Char): Int {
        var i = open + 1
        val len = text.length
        while (i < len) {
            val c = text[i]
            if (c == '\\' && quote != '`') { i += 2; continue } // backtick identifiers don't use \ escapes
            if (c == quote) {
                if (i + 1 < len && text[i + 1] == quote) { i += 2; continue } // doubled quote = literal
                return i + 1
            }
            i++
        }
        return len
    }

    private fun skipLineComment(text: CharSequence, start: Int): Int {
        var i = start + 2
        val len = text.length
        while (i < len && text[i] != '\n') i++
        return i
    }

    private fun skipBlockComment(text: CharSequence, start: Int): Int {
        var i = start + 2
        val len = text.length
        while (i + 1 < len) {
            if (text[i] == '*' && text[i + 1] == '/') return i + 2
            i++
        }
        return len
    }

    private companion object {
        // fe-sql-parser is stateless/thread-safe per its docs (same instance the annotator shares).
        private val SHARED = DorisSqlParser()
    }
}
