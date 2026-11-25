package app.logdate.server.logging

import io.sentry.Hint
import io.sentry.Hub
import io.sentry.SentryEnvelope
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import io.sentry.protocol.User
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SentryTransportPrivacyTest {
    @Test
    fun `real SDK transport sends only sanitized crash context`() {
        val marker = "PRIVATE_REQUEST_AUTH_ACCOUNT_PATH_SENTINEL"
        val envelopes = mutableListOf<String>()
        val options =
            SentryOptions().apply {
                dsn = "https://public@example.invalid/1"
                release = "logdate-server@abc1234"
                environment = "test"
                setTransportFactory { configured, _ ->
                    object : ITransport {
                        override fun send(
                            envelope: SentryEnvelope,
                            hint: Hint,
                        ) {
                            val output = ByteArrayOutputStream()
                            configured.serializer.serialize(envelope, output)
                            envelopes += output.toString(Charsets.UTF_8.name())
                        }

                        override fun flush(timeoutMillis: Long) = Unit

                        override fun getRateLimiter(): RateLimiter? = null

                        override fun close(isRestarting: Boolean) = Unit

                        override fun close() = Unit
                    }
                }
                configureSentryPrivacy(this, release!!, environment!!)
            }
        val hub = Hub(options)
        try {
            hub.setUser(
                User().apply {
                    id = marker
                    email = marker
                    segment = marker
                },
            )
            hub.setTag(marker, marker)
            hub.captureEvent(
                SentryEvent(IllegalStateException(marker, RuntimeException(marker))).apply {
                    request =
                        Request().apply {
                            url = "https://private.invalid/$marker"
                            data = marker
                        }
                    serverName = marker
                    setExtra(marker, mapOf(marker to marker))
                },
                Hint.withAttachment(io.sentry.Attachment(marker.encodeToByteArray(), "$marker.txt")),
            )
            assertTrue(envelopes.isNotEmpty())
            val payload = envelopes.joinToString()
            assertFalse(payload.contains(marker))
            assertTrue(payload.contains("SERVER_UNEXPECTED_FAILURE"))
            assertTrue(payload.contains("logdate-server@abc1234"))
        } finally {
            hub.close()
        }
    }
}
