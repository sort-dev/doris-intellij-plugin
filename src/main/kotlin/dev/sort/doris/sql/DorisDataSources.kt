package dev.sort.doris.sql

import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.sort.doris.DorisDbms

internal val dorisDataSourceResolver = DialectDataSourceResolver(DorisDbms.DORIS, onlyDataSourceFallback = false)
