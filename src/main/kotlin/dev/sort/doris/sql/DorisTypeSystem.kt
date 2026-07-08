package dev.sort.doris.sql

import com.intellij.database.Dbms
import com.intellij.database.dialects.base.types.DasTypeSystemImpl
import com.intellij.database.dialects.mysqlbase.types.MysqlBaseTypeSystem
import com.intellij.database.model.DataType
import com.intellij.database.model.properties.DataTypeFactory
import com.intellij.database.types.DasArrayType
import com.intellij.database.types.DasType
import com.intellij.database.types.DasTypeCategory
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlLiteralExpression
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.SqlTableTypeBase

/**
 * Doris type system: exactly the MySQL type system (which DORIS previously reached through the
 * `extensionFallback DORIS -> MYSQL` route) plus ONE addition — static output schemas for Doris
 * table-valued functions ([getBuiltinFunctionReturnType]).
 *
 * ## Why this seam
 * A FROM-clause call like `FROM tasks("type"="mv")` parses as SQL_TABLE_PROCEDURE_CALL_EXPRESSION
 * (backed by `SqlFunctionCallTableExpressionImpl`), which types itself from the INNER function
 * call: `SqlFunctionCallExpressionImpl.createBuiltinFunctionReturnType` first asks
 * `dialect.getTypeSystem().getBuiltinFunctionReturnType(prototype, nameElement, params, routine)`
 * and uses that DasType verbatim if non-null; the table expression then runs it through
 * `SqlImplUtil.convertToTableType`, which returns any `SqlTableType` as-is. So returning a
 * ready-made SqlTableType here makes the TVF's columns resolve in SELECT/WHERE with zero exec —
 * no parser or resolver surgery. (The declarative alternative — `:table(col:type, ...)` return
 * types in functions.xml, the way Snowflake ships its metadata table functions — dies on the
 * MySQL grammar: the return-type spec string is re-parsed as a type element by the DIALECT's own
 * parser, and MySQL has no table-type syntax; verified empirically, the spec parses to an
 * unresolved type reference.)
 *
 * The `prototype` handed to us only exists because [DorisTableFunctions.BuiltinsOverlay]
 * registered the TVF names in the dialect's builtin-function map — without a prototype the
 * platform never consults the type system for a call.
 *
 * ## Delegation
 * `MysqlBaseTypeSystem` is final, so we can't extend it; instead we extend the same base it does
 * ([DasTypeSystemImpl]) and delegate every method MySQL overrides to a private MySQL instance.
 * Flag-off behavior is therefore byte-identical to the old fallback for everything that isn't a
 * registered Doris TVF.
 *
 * Registered via `<typeSystem dbms="DORIS" .../>` in plugin.xml (public, dynamic EP
 * `com.intellij.database.typeSystem`; the platform instantiates it with the Dbms constructor).
 */
class DorisTypeSystem(dbms: Dbms) : DasTypeSystemImpl(dbms) {

    private val mysql = MysqlBaseTypeSystem(dbms)

    // -- MysqlBaseTypeSystem parity (it overrides exactly these) --------------------------------

    override fun getNormalizedTypeName(name: String): String = mysql.getNormalizedTypeName(name) ?: name

    override fun normalizeType(type: DataType): DataType = mysql.normalizeType(type)

    override fun getDefaultTypeName(cat: DasTypeCategory): String? = mysql.getDefaultTypeName(cat)

    // MysqlBaseTypeSystem customizes doCreateArrayType (protected); route through its public
    // entry points so array types keep the MySQL binary-type behavior.
    override fun getArrayType(componentType: DasType): DasArrayType = mysql.getArrayType(componentType)

    override fun getAnyArrayType(): DasArrayType = mysql.anyArrayType

    // -- The Doris TVF seam ----------------------------------------------------------------------

    /**
     * Serves the static output schema of a registered Doris TVF as an [SqlTableType]; returns null
     * for everything else (normal MySQL/builtin behavior continues).
     *
     * Arg-conditional TVFs ([DorisTableFunctions.Schema.ByPropertyLiteral]) pick their variant by
     * reading the discriminator property literal (e.g. `"type"="mv"`) straight from the call's
     * argument PSI — zero exec; absent/unknown literal degrades to the variants' union.
     * Open TVFs ([DorisTableFunctions.Schema.Open]) return null so the call keeps the platform's
     * unknown-type handling (no fabricated columns); the unresolved-column suppression for them
     * lives in [DorisHighlightInfoFilter].
     */
    override fun getBuiltinFunctionReturnType(
        prototype: BuiltinFunction.Prototype,
        nameElement: SqlReferenceExpression?,
        params: PsiElement?,
        routineElement: PsiElement,
    ): DasType? {
        val tvf = DorisTableFunctions.byName(prototype.function?.name) ?: return null
        val columns = when (val schema = tvf.schema) {
            is DorisTableFunctions.Schema.Fixed -> schema.columns
            is DorisTableFunctions.Schema.ByPropertyLiteral ->
                schema.variant(propertyLiteral(params, schema.key))
            is DorisTableFunctions.Schema.Open -> return null
        }
        // Anchor columns at the CALL expression, never at its name element: for a
        // SqlReferenceExpression column element the resolver chases that reference's own targets
        // (the function, wrong name); for a non-reference element with no symbol it synthesizes a
        // DasVirtualColumnSymbol carrying the column's own name — exactly what we want
        // (SqlImplUtil.processDeclarationsInType).
        return tableType(columns, routineElement)
    }

    private fun tableType(columns: List<DorisTableFunctions.Column>, anchor: PsiElement): DasType? {
        var result: SqlTableType? = null
        for (c in columns) {
            val das = createDasType(DataTypeFactory.of(c.type))
            val one = SqlTableTypeBase.createType(
                anchor, das, c.name,
                /* quoted = */ false, /* generated = */ false, /* resolvable = */ true,
                /* qualifier = */ null, /* aliasName = */ null,
            )
            result = result?.add(one) ?: one
        }
        return result
    }

    private companion object {
        /**
         * Reads the literal value of `"key"="value"` (either quote style) from a TVF call's
         * argument list PSI. Only direct `lhs = rhs` argument expressions are considered.
         */
        fun propertyLiteral(params: PsiElement?, key: String): String? {
            if (params == null) return null
            for (arg in PsiTreeUtil.getChildrenOfTypeAsList(params, SqlBinaryExpression::class.java)) {
                val name = literalText(arg.lOperand) ?: continue
                if (!name.equals(key, ignoreCase = true)) continue
                return literalText(arg.rOperand)
            }
            return null
        }

        /**
         * Text of a `"key"`/`'value'` operand with the quotes stripped. Doris property args are
         * always strings, but the MySQL grammar parses the double-quoted form as an identifier
         * reference in some positions — accept literals and references alike, textually.
         */
        fun literalText(e: PsiElement?): String? {
            if (e !is SqlLiteralExpression && e !is SqlReferenceExpression) return null
            val text = e.text?.trim() ?: return null
            return if (text.length >= 2 && text.first() in "'\"`" && text.last() == text.first()) {
                text.substring(1, text.length - 1)
            } else text
        }
    }
}
