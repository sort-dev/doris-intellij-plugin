package dev.sort.doris.sql.replay

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.sql.psi.SqlCompositeElementTypes.SQL_IDENTIFIER
import com.intellij.sql.psi.SqlKeywordTokenType
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.DefaultErrorStrategy
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.apache.doris.nereids.DorisParser
import org.apache.doris.sqlparser.DorisSqlParser

/**
 * Route B "shadow-replay bridge" proof-of-concept (Gate 2 of
 * RESEARCH-when-hell-freezes-over-parser.md).
 *
 * Given a [PsiBuilder] positioned at the start of a statement, this parses that one statement with
 * the authoritative Doris ANTLR grammar (fe-sql-parser), then REPLAYS the platform's own
 * MysqlLexer token stream through the builder, opening/closing PSI markers at CST node boundaries.
 * The result is a platform PSI tree whose *shape* is dictated by the real Doris parser but whose
 * *tokens* remain the platform's (so the rest of the SQL ecosystem keeps working).
 *
 * The bridge is offset-driven: PsiBuilder tokens and ANTLR tokens both index into the same text, so
 * a CST node [start,stop] maps to "open a marker before the builder token at `start`, close it after
 * the builder token ending at `stop`". Whitespace is never advanced explicitly — PsiBuilder binds
 * edge whitespace outside markers by default, which is exactly how the golden trees were produced.
 *
 * Safety contract: if ANTLR reports ANY syntax error, or any CST boundary fails to land on a builder
 * token boundary, we consume nothing (or roll back) and return false, so the caller falls through to
 * the existing MySQL delegation unchanged — "never worse than today".
 */
internal class CstReplayer(private val builder: PsiBuilder) {

    /** One materialised PSI node: an absolute [start,stopExclusiveEnd] span and its element type. */
    private class Node(
        val startOffset: Int,   // absolute offset of the first token (inclusive)
        val endOffset: Int,     // absolute offset of the last token's last char (inclusive)
        val type: IElementType,
        val seq: Int,           // pre-order DFS index; breaks ties when spans coincide
    ) {
        var marker: PsiBuilder.Marker? = null
    }

    /**
     * Attempt to replay the statement at the builder's current position as a Doris SELECT.
     * Returns true iff a byte-faithful PSI tree was produced (builder advanced to the statement's
     * end, exclusive of the terminating ';'); false with the builder untouched/rolled-back otherwise.
     */
    fun tryReplaySelectStatement(): Boolean {
        val statementStart = builder.currentOffset
        val statementText = extractStatementText(statementStart) ?: return false

        val parse = antlrParse(statementText) ?: return false // any syntax error -> bail, nothing consumed
        val root = parse.root
        val rootStop = root.stop ?: return false

        val nodes = ArrayList<Node>()
        val seq = intArrayOf(0)
        collect(root, parse.ruleNames, statementStart, nodes, seq)
        if (nodes.isEmpty()) return false

        val statementEndOffset = statementStart + rootStop.stopIndex // absolute, inclusive
        return replay(nodes, statementEndOffset)
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

    /** Pre-order DFS; emit a Node for each mapped context, plus the synthetic table-expression. */
    private fun collect(ctx: ParserRuleContext, ruleNames: Array<String>, absStart: Int, out: MutableList<Node>, seq: IntArray) {
        val cls = ctx.javaClass.simpleName
        val type = ReplayMapping.BY_CONTEXT_CLASS[cls]
            ?: ReplayMapping.resolveContextual(cls, (ctx.parent as? ParserRuleContext)?.javaClass?.simpleName) { rule ->
                hasNonEmptyChildRule(ctx, ruleNames, rule)
            }
        if (type != null) addNode(ctx, absStart, type, out, seq)

        // Synthetic SQL_TABLE_EXPRESSION: the platform groups FROM + WHERE (+ GROUP BY / HAVING) into
        // one node the flat ANTLR grammar lacks. Span it from the FROM clause start to the end of the
        // query specification. Emit its open BEFORE descending so its seq precedes the FROM clause's.
        if (ruleNameOf(ctx, ruleNames) == ReplayMapping.QUERY_SPECIFICATION_RULE) {
            val from = ctx.children?.firstOrNull {
                it is ParserRuleContext && ruleNameOf(it, ruleNames) == ReplayMapping.FROM_CLAUSE_RULE
            } as? ParserRuleContext
            val stop = ctx.stop
            if (from?.start != null && stop != null && from.start.startIndex <= stop.stopIndex) {
                out.add(Node(absStart + from.start.startIndex, absStart + stop.stopIndex, ReplayMapping.TABLE_EXPRESSION, seq[0]++))
            }
        }

        val n = ctx.childCount
        for (i in 0 until n) {
            val child = ctx.getChild(i)
            if (child is ParserRuleContext) collect(child, ruleNames, absStart, out, seq)
        }
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
     * token, close markers at node ends (innermost first) after advancing. Guarded by a rollback
     * marker: any structural surprise (a boundary that misses a token edge) restores the builder.
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
            val tokenText = builder.tokenText ?: break
            val tokenEnd = tokenStart + tokenText.length - 1

            opensAt[tokenStart]?.forEach { node ->
                node.marker = builder.mark()
                stack.addLast(node)
            }

            // Keyword-as-identifier remap: the platform's generated `identifier` rule converts a
            // non-reserved keyword token (e.g. NAME) to its identifier form (NAME_IDENT) via
            // SqlKeywordTokenType.getIdentifierToken(). Our raw-token replay must do the same so the
            // leaf token type matches the golden. Only for a token that IS a whole SQL_IDENTIFIER leaf.
            if (spanKey(tokenStart, tokenEnd) in identSpans) {
                (builder.tokenType as? SqlKeywordTokenType)?.identifierToken?.let { builder.remapCurrentToken(it) }
            }

            builder.advanceLexer()

            closesAt[tokenEnd]?.let { closing ->
                for (node in closing) {
                    val top = stack.removeLastOrNull()
                    if (top !== node) { // structural mismatch: boundaries didn't nest as expected
                        rollback.rollbackTo()
                        return false
                    }
                    node.marker!!.done(node.type)
                }
            }
        }

        if (stack.isNotEmpty()) { // some node never closed on a token boundary -> misalignment
            rollback.rollbackTo()
            return false
        }
        rollback.drop() // commit: keep the materialised markers, discard only the rollback anchor
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
