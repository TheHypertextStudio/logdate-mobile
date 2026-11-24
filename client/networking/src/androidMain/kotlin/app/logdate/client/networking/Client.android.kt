package app.logdate.client.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_SECONDS = 30L
private const val SOCKET_IO_TIMEOUT_SECONDS = 60L

/**
 * An HTTP client that supports JSON serialization.
 *
 * Uses OkHttp on Android so coroutine cancellation immediately aborts underlying calls and
 * socket read/write/connect timeouts stay bounded during stalled background transfers.
 */
actual val httpClient: HttpClient =
    HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                readTimeout(SOCKET_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                writeTimeout(SOCKET_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        install(HttpTimeout) {
            connectTimeoutMillis = TimeUnit.SECONDS.toMillis(CONNECT_TIMEOUT_SECONDS)
            socketTimeoutMillis = TimeUnit.SECONDS.toMillis(SOCKET_IO_TIMEOUT_SECONDS)
        }
        configureClientDefaults()
    }
