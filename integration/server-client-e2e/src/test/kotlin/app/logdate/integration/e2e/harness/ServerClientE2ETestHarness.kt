package app.logdate.integration.e2e.harness

import app.logdate.client.sync.cloud.LogDateCloudApiClient
import app.logdate.server.module
import app.logdate.shared.config.DefaultLogDateConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import io.ktor.serialization.kotlinx.json.json as ktorJson
import java.net.http.HttpClient as JHttpClient

data class ServerClientE2EHarness(
    val baseUrl: String,
    val apiClient: LogDateCloudApiClient,
    internal val httpClient: HttpClient,
    private val engine: EmbeddedServer<*, *>,
) : AutoCloseable {
    override fun close() {
        try {
            httpClient.close()
        } finally {
            engine.stop(gracePeriodMillis = 500, timeoutMillis = 5_000)
        }
    }
}

suspend fun <T> withServerClientHarness(
    serverPort: Int? = null,
    beforeRequest: suspend (ApplicationCall) -> Unit = {},
    beforeServerStart: (Int) -> Unit = {},
    block: suspend ServerClientE2EHarness.() -> T,
): T =
    withHarnessDatabase { databaseAvailable ->
        val json =
            Json {
                prettyPrint = false
                isLenient = true
                ignoreUnknownKeys = true
            }

        require(serverPort == null || serverPort in 1..65535) { "serverPort must be between 1 and 65535" }
        val port = serverPort ?: 0
        val host = "127.0.0.1"
        val engine =
            embeddedServer(Netty, host = host, port = port) {
                intercept(ApplicationCallPipeline.Setup) { beforeRequest(call) }
                module(isDatabaseAvailable = databaseAvailable)
            }

        var harness: ServerClientE2EHarness? = null
        try {
            beforeServerStart(port)
            engine.start(wait = false)
            val boundPort =
                engine.engine
                    .resolvedConnectors()
                    .single()
                    .port
            waitForServerStartup(host, boundPort)

            val configRepository = DefaultLogDateConfigRepository(initialBackendUrl = "http://$host:$boundPort")
            val baseUrl = configRepository.getCurrentApiBaseUrl()
            val httpClient =
                HttpClient(OkHttp) {
                    install(ContentNegotiation) {
                        ktorJson(json)
                    }
                }
            val apiClient = LogDateCloudApiClient(configRepository = configRepository, httpClient = httpClient)
            harness =
                ServerClientE2EHarness(
                    baseUrl = baseUrl,
                    apiClient = apiClient,
                    httpClient = httpClient,
                    engine = engine,
                )

            harness.block()
        } finally {
            if (harness != null) {
                harness.close()
            } else {
                engine.stop(gracePeriodMillis = 500, timeoutMillis = 5_000)
            }
        }
    }

private fun waitForServerStartup(
    host: String,
    port: Int,
    timeoutMillis: Long = 5_000,
) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    val client =
        JHttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(250))
            .build()
    val request =
        HttpRequest
            .newBuilder()
            .uri(URI("http://$host:$port/api/v1/ops/sync/status"))
            .timeout(Duration.ofMillis(500))
            .GET()
            .build()

    while (System.currentTimeMillis() < deadline) {
        runCatching { client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() }
            .onSuccess { status ->
                if (status in 100..599) {
                    return
                }
            }
        Thread.sleep(50)
    }
    error("Timed out waiting for test server startup on $host:$port")
}
