package app.logdate.integration.e2e.harness

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerClientHarnessLifecycleTest {
    @Test
    fun `an automatically assigned server port stays owned until the harness closes`() =
        runBlocking {
            var assignedPort = 0
            withServerClientHarness {
                assignedPort = URI(baseUrl).port
                assertTrue(assignedPort in 1..65535)
                ServerSocket().use { competing ->
                    competing.reuseAddress = false
                    assertFailsWith<BindException> {
                        competing.bind(InetSocketAddress("127.0.0.1", assignedPort))
                    }
                }
            }
            ServerSocket().use { released ->
                released.reuseAddress = true
                released.bind(InetSocketAddress("127.0.0.1", assignedPort))
                assertTrue(released.isBound)
            }
        }

    @Test
    fun `stopping a test server releases its database connections`() =
        runBlocking {
            assumeTrue(!System.getenv("DATABASE_URL").isNullOrBlank())
            assertDatabaseConnectionsReleased()
            repeat(3) {
                withServerClientHarness {
                    assertTrue(connectionCount() > 0)
                }
                assertDatabaseConnectionsReleased()
            }
        }

    @Test
    fun `a failed test block releases its database connections`() =
        runBlocking {
            assumeTrue(!System.getenv("DATABASE_URL").isNullOrBlank())
            assertDatabaseConnectionsReleased()
            assertFailsWith<IllegalStateException> {
                withServerClientHarness { error("test failure") }
            }
            assertDatabaseConnectionsReleased()
        }

    @Test
    fun `a failed server startup releases its database connections`() =
        runBlocking {
            assumeTrue(!System.getenv("DATABASE_URL").isNullOrBlank())
            assertDatabaseConnectionsReleased()
            ServerSocket().use { occupied ->
                occupied.reuseAddress = false
                occupied.bind(InetSocketAddress("127.0.0.1", 0))
                assertFailsWith<BindException> {
                    withServerClientHarness(serverPort = occupied.localPort) {
                        error("The occupied port must prevent startup")
                    }
                }
            }
            assertDatabaseConnectionsReleased()
        }

    @Test
    fun `cleanup assertion waits for a closing database session`() =
        runBlocking {
            assumeTrue(!System.getenv("DATABASE_URL").isNullOrBlank())
            assertDatabaseConnectionsReleased()
            val connection =
                DriverManager.getConnection(
                    System.getenv("DATABASE_URL"),
                    System.getenv("DATABASE_USER"),
                    System.getenv("DATABASE_PASSWORD"),
                )
            try {
                assertTrue(connectionCount() > 0)
                val closing =
                    launch {
                        delay(200)
                        connection.close()
                    }
                assertDatabaseConnectionsReleased()
                closing.join()
            } finally {
                connection.close()
            }
        }

    @Test
    fun `cleanup assertion rejects a retained database session`() =
        runBlocking {
            assumeTrue(!System.getenv("DATABASE_URL").isNullOrBlank())
            assertDatabaseConnectionsReleased()
            DriverManager
                .getConnection(
                    System.getenv("DATABASE_URL"),
                    System.getenv("DATABASE_USER"),
                    System.getenv("DATABASE_PASSWORD"),
                ).use {
                    assertTrue(connectionCount() > 0)
                    assertFailsWith<TimeoutCancellationException> {
                        assertDatabaseConnectionsReleased(timeoutMillis = 250)
                    }
                }
            assertDatabaseConnectionsReleased()
        }

    private suspend fun assertDatabaseConnectionsReleased(timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) {
            while (connectionCount() != 0) delay(25)
        }
    }

    private fun connectionCount(): Int =
        DriverManager
            .getConnection(
                System.getenv("DATABASE_URL"),
                System.getenv("DATABASE_USER"),
                System.getenv("DATABASE_PASSWORD"),
            ).use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT count(*) FROM pg_stat_activity " +
                                "WHERE datname = current_database() AND pid <> pg_backend_pid()",
                        ).use { result ->
                            check(result.next())
                            result.getInt(1)
                        }
                }
            }
}
