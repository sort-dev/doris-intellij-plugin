package dev.sort.doris.sql

import com.intellij.database.dataSource.DatabaseConnectionCore
import com.intellij.database.dataSource.DatabaseConnectionInterceptor
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.remote.jdbc.RemoteConnection
import com.intellij.database.remote.jdbc.RemoteResultSet
import com.intellij.database.remote.jdbc.RemoteStatement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.sort.doris.DorisDbms
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

internal class DorisFunctionCatalogFixture(until: Disposable) {
    val errors = BackgroundErrors(until)
    val service = DorisLiveCatalogService(errors.newScope(until))

    init {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(DorisLiveCatalogService::class.java, service, until)
    }

    fun harvest(source: LocalDataSource, names: List<String?>, stalled: Boolean = false): Query {
        val query = Query(names, stalled)
        val remote = proxy<RemoteConnection> { method, _ ->
            when (method.name) {
                "createStatement" -> query.statement
                else -> error("Unexpected connection call: $method")
            }
        }
        val connection = proxy<DatabaseConnectionCore> { method, _ ->
            when (method.name) {
                "getRemoteConnection" -> remote
                "getConnectionPoint" -> source
                "getDbms" -> DorisDbms.DORIS
                else -> error("Unexpected core call: $method")
            }
        }
        val proto = proxy<DatabaseConnectionInterceptor.ProtoConnection> { method, _ ->
            when (method.name) {
                "getConnectionPoint" -> source
                else -> error("Unexpected proto call: $method")
            }
        }
        runBlocking {
            val interceptor = DorisFunctionHarvestInterceptor()
            if (interceptor.interceptConnection(proto, false)) interceptor.handleConnected(connection, proto)
        }
        return query
    }

    class Query(names: List<String?>, stalled: Boolean) {
        val cancelled = AtomicBoolean()
        val closed = AtomicBoolean()
        var timeout = 0
        var executions = 0
        private val released = CountDownLatch(1)
        private var index = -1
        private val rows = proxy<RemoteResultSet> { method, args ->
            when (method.name) {
                "next" -> ++index < names.size
                "getString" -> { check(args[0] == 1); names[index] }
                "close" -> null
                else -> error("Unexpected row call: $method")
            }
        }
        val statement = proxy<RemoteStatement> { method, args ->
            when (method.name) {
                "setQueryTimeout" -> { timeout = args[0] as Int; null }
                "executeQuery" -> {
                    check(args[0] == "SHOW BUILTIN FUNCTIONS")
                    executions++
                    if (stalled) check(released.await(15, TimeUnit.SECONDS)) { "Query was not cancelled" }
                    rows
                }
                "cancel" -> { cancelled.set(true); released.countDown(); null }
                "close" -> { closed.set(true); null }
                else -> error("Unexpected statement call: $method")
            }
        }
    }
}

private inline fun <reified T> proxy(crossinline call: (Method, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Doris catalog fixture ${T::class.java.simpleName}"
            else -> call(method, args.orEmpty())
        }
    } as T
