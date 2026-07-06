package dev.sort.doris

import com.intellij.database.Dbms
import com.intellij.openapi.util.IconLoader

object DorisDbms {
    private val icon = IconLoader.getIcon("/icons/doris.svg", DorisDbms::class.java)

    @JvmField
    val DORIS: Dbms = Dbms.create(
        "DORIS",
        "Apache Doris",
        { icon },
        Dbms.defaultPattern("doris")
    )
}
