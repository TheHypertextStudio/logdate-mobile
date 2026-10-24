package app.logdate.server.audit

import org.slf4j.LoggerFactory
import kotlin.time.Clock

/**
 * Emits structured audit events through a dedicated SLF4J logger channel so operators can route
 * them independently of the application log (e.g. to Cloud Logging, Elasticsearch, or a local
 * file with its own rotation policy).
 *
 * The payload is a single-line JSON object with a stable schema:
 * ```
 * {"ts":"2026-04-17T…","category":"<AuditCategory>","<key>":"<value>", …}
 * ```
 *
 * Call this from places that matter for forensics: account creation, account deletion, passkey
 * rotation, quota violations, billing provider webhooks. Do NOT use it for routine request
 * logging — that's what the regular Napier / SLF4J channel is for.
 */
object AuditLogger {
    private val logger = LoggerFactory.getLogger("app.logdate.audit")

    fun emit(
        category: String,
        fields: Map<String, String?> = emptyMap(),
    ) {
        if (!logger.isInfoEnabled) return
        logger.info(renderJson(category, fields))
    }

    internal fun renderJson(
        category: String,
        fields: Map<String, String?>,
    ): String {
        val now = Clock.System.now()
        val builder = StringBuilder("{")
        builder.append("\"ts\":\"").append(now).append('"')
        builder.append(",\"category\":\"").append(escape(category)).append('"')
        for ((key, value) in fields.toSortedMap()) {
            if (value == null) continue
            builder
                .append(',')
                .append('"')
                .append(escape(key))
                .append("\":\"")
                .append(escape(value))
                .append('"')
        }
        builder.append('}')
        return builder.toString()
    }

    private fun escape(raw: String): String =
        buildString(raw.length) {
            for (ch in raw) {
                when (ch) {
                    '\\' -> {
                        append("\\\\")
                    }

                    '"' -> {
                        append("\\\"")
                    }

                    '\n' -> {
                        append("\\n")
                    }

                    '\r' -> {
                        append("\\r")
                    }

                    '\t' -> {
                        append("\\t")
                    }

                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
        }
}
