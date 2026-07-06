package dev.sort.doris

import com.intellij.database.Dbms
import com.intellij.database.dialects.mysql.model.MysqlMetaModel
import com.intellij.database.dialects.mysqlbase.model.MysqlBaseModelHelper
import com.intellij.database.model.ModelFacade
import com.intellij.database.model.ModelHelper
import com.intellij.database.model.meta.BasicMetaModel

class DorisModelFacade(dbms: Dbms) : ModelFacade(dbms) {
    private val helper = MysqlBaseModelHelper()

    override fun getMetaModel(): BasicMetaModel<*> {
        return MysqlMetaModel.MODEL
    }

    override fun getModelHelper(): ModelHelper {
        return helper
    }
}
