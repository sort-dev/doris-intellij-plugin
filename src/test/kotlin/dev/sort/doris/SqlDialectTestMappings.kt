package dev.sort.doris

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.sql.dialects.SqlDialectMappings
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.util.FileContentUtil
import java.lang.reflect.InvocationTargetException

/** 263 stores active dialect mappings in the workspace model; the old setter only updates storage. */
internal fun setSqlDialectMapping(project: Project, file: VirtualFile?, dialect: SqlLanguageDialect?) {
    val workspaceSetter = try {
        Class.forName("com.intellij.sql.dialects.SqlDialectMappingUtilsKt").methods.singleOrNull {
            it.name == "setDialectMappingSync" && it.parameterCount == 3
        }
    } catch (_: ClassNotFoundException) {
        null
    }
    if (workspaceSetter == null) {
        SqlDialectMappings.getInstance(project).setMapping(file, dialect)
    } else {
        WriteAction.run<RuntimeException> {
            try {
                workspaceSetter.invoke(null, project, file, dialect)
                // The workspace update no longer reparses cached PSI through the old mapping
                // service. Complete that fixture boundary before the test reads the file again.
                FileContentUtil.reparseFiles(project, listOfNotNull(file), true)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }
}
