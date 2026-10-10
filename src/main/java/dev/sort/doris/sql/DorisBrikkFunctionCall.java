package dev.sort.doris.sql;

import com.intellij.database.types.DasType;
import com.intellij.psi.tree.IElementType;
import com.intellij.sql.psi.SqlTableType;
import com.intellij.sql.psi.impl.SqlDasTypeAwareDeepRecursiveElement;
import com.intellij.sql.psi.impl.SqlFunctionCallExpressionImpl;
import kotlin.DeepRecursiveScope;
import kotlin.coroutines.Continuation;

/** Java preserves the JVM super-call for the platform's suspend member-extension method. */
final class DorisBrikkFunctionCall extends SqlFunctionCallExpressionImpl {
    DorisBrikkFunctionCall(IElementType type) { super(type); }

    @Override
    public Object computeDasType(DeepRecursiveScope<SqlDasTypeAwareDeepRecursiveElement, DasType> scope,
                                 Continuation<? super DasType> continuation) {
        SqlTableType type = BrikkRelationColumns.INSTANCE.tableType(this);
        return type != null ? type : super.computeDasType(scope, continuation);
    }
}
