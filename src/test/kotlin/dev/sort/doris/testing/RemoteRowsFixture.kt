package dev.sort.doris.testing

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.database.remote.jdbc.RemoteResultSetMetaData
import com.intellij.database.remote.jdbc.RemoteStatement
import dev.sort.doris.DorisDbms
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal class RemoteRowsFixture {
    val executed = CopyOnWriteArrayList<String>()
    val statementsClosed = AtomicInteger()
    val resultsClosed = AtomicInteger()
    var query: (String) -> Pair<List<String>, List<List<String?>>> = { listOf("plan") to emptyList() }
    var command: (String) -> Unit = {}

    private val remote = proxy<RemoteConnection> { method, _ ->
        check(method.name == "createStatement") { "Unexpected remote call: $method" }
        proxy<RemoteStatement> { call, args ->
            when (call.name) {
                "execute" -> { val sql = args[0] as String; executed += sql; command(sql); false }
                "executeQuery" -> {
                    val sql = args[0] as String
                    executed += sql
                    val (labels, data) = query(sql)
                    var index = -1
                    val metadata = proxy<RemoteResultSetMetaData> { m, a ->
                        when (m.name) {
                            "getColumnCount" -> labels.size
                            "getColumnLabel" -> labels[(a[0] as Int) - 1]
                            else -> error("Unexpected metadata: $m")
                        }
                    }
                    proxy<RemoteResultSet> { m, a ->
                        when (m.name) {
                            "next" -> ++index < data.size
                            "getMetaData" -> metadata
                            "getString" -> data[index][(a[0] as Int) - 1]
                            "close" -> { resultsClosed.incrementAndGet(); null }
                            else -> error("Unexpected row: $m")
                        }
                    }
                }
                "setQueryTimeout", "cancel" -> null
                "close" -> { statementsClosed.incrementAndGet(); null }
                else -> error("Unexpected statement: $call")
            }
        }
    }
    val connection = proxy<DatabaseConnectionCore> { method, _ ->
        when (method.name) {
            "getRemoteConnection" -> remote
            "getDbms" -> DorisDbms.DORIS
            else -> error("Unexpected connection: $method")
        }
    }
    val remoteConnection: RemoteConnection get() = remote

    companion object {
        inline fun <reified T> proxy(crossinline call: (Method, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
                when (method.name) {
                    "equals" -> instance === args?.get(0)
                    "hashCode" -> System.identityHashCode(instance)
                    "toString" -> "Doris remote ${T::class.java.simpleName}"
                    else -> call(method, args.orEmpty())
                }
            } as T
    }
}
