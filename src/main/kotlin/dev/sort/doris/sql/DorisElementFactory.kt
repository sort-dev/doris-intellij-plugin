package dev.sort.doris.sql

import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.sql.dialects.mysql.MysqlElementFactory
import com.intellij.sql.psi.SqlCompositeElementTypes

/** Retain platform alias, join and subquery resolution; supply only the slot call's input type. */
class DorisElementFactory : MysqlElementFactory() {
    override fun createElementNode(type: IElementType): CompositeElement? =
        if (type == SqlCompositeElementTypes.SQL_FUNCTION_CALL) DorisBrikkFunctionCall(type)
        else super.createElementNode(type)
}
