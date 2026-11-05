package app.logdate.server.database

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource

/** Advances a synthetic deadline at an actual JDBC boundary, independently of clock-read count. */
internal class IdentityAdoptionAfterStatementFixture(
    private val source: DataSource,
    private val prefix: String,
    private val execution: String = "executeUpdate",
    private val after: () -> Unit,
) : DataSource by source {
    private val observed = AtomicBoolean(false)
    val executed: Boolean get() = observed.get()

    override fun getConnection(): Connection {
        val connection = source.connection
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
            val result = invoke(connection, method, arguments)
            val sql = arguments?.firstOrNull() as? String
            if (method.name == "prepareStatement" && sql?.trimStart()?.startsWith(prefix, ignoreCase = true) == true) {
                statement(result as PreparedStatement)
            } else {
                result
            }
        } as Connection
    }

    private fun statement(delegate: PreparedStatement): PreparedStatement =
        Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, method, arguments ->
            val result = invoke(delegate, method, arguments)
            if (method.name == execution && observed.compareAndSet(false, true)) after()
            result
        } as PreparedStatement

    private fun invoke(
        delegate: Any,
        method: Method,
        arguments: Array<out Any?>?,
    ): Any? =
        try {
            method.invoke(delegate, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
}
