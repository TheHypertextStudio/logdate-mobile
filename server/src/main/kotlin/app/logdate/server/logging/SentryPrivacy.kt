package app.logdate.server.logging

import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Message

private val TRUSTED_RELEASE = Regex("[A-Za-z0-9@._+-]{1,100}")

/** Rebuild instead of redacting: future SDK fields must not bypass the allowlist. */
internal fun sanitizedServerCrash(
    event: SentryEvent,
    trustedRelease: String? = null,
    trustedEnvironment: String? = null,
): SentryEvent =
    SentryEvent().apply {
        level = event.level ?: SentryLevel.ERROR
        release = trustedRelease?.takeIf { it.matches(TRUSTED_RELEASE) }
        environment = trustedEnvironment?.takeIf { it in setOf("production", "development", "test", "staging") }
        message = Message().apply { formatted = "SERVER_UNEXPECTED_FAILURE" }
    }

internal fun configureSentryPrivacy(
    options: SentryOptions,
    release: String,
    environment: String,
) {
    options.isSendDefaultPii = false
    options.isAttachStacktrace = false
    options.isAttachServerName = false
    options.isSendModules = false
    options.setBeforeSend { event, _ -> sanitizedServerCrash(event, release, environment) }
    options.setBeforeBreadcrumb { _, _ -> null }
    options.setBeforeSendTransaction { _, _ -> null }
    val factory =
        options.transportFactory.let {
            if (it is io.sentry.NoOpTransportFactory) io.sentry.AsyncHttpTransportFactory() else it
        }
    options.setTransportFactory { configured, details ->
        PrivateSentryTransport(factory.create(configured, details), configured.serializer, release, environment)
    }
}
