package app.logdate.server.bluesky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import studio.hypertext.atproto.syntax.Handle
import java.util.Hashtable
import javax.naming.Context
import javax.naming.directory.DirContext
import javax.naming.directory.InitialDirContext

internal class JndiBlueskyTxtLookup(
    private val timeoutMillis: Long = 5000,
    private val contexts: () -> DirContext = ::dnsContext,
) : BlueskyTxtLookup {
    init {
        require(timeoutMillis in 1..5000)
    }

    override suspend fun records(name: String): List<String> {
        val handle = name.removePrefix("_atproto.")
        require(name.startsWith("_atproto.") && name.length <= 253)
        require(Handle.parse(handle).isSuccess && !forbiddenDomain(handle))
        return withTimeoutOrNull(timeoutMillis) {
            runInterruptible(Dispatchers.IO) {
                val context = contexts()
                try {
                    val attribute =
                        context.getAttributes("$name.", arrayOf("TXT")).get("TXT")
                            ?: return@runInterruptible emptyList()
                    valid(attribute.size() <= 128)
                    val values = attribute.all
                    try {
                        buildList {
                            var bytes = 0
                            while (values.hasMore()) {
                                val value = values.next() as? String ?: throw BlueskyInvalidDnsAnswer()
                                bytes += value.length
                                valid(value.length <= 4096 && bytes <= 65_536)
                                add(decodeTxt(value))
                            }
                        }
                    } finally {
                        values.close()
                    }
                } finally {
                    context.close()
                }
            }
        } ?: throw BlueskyDnsTimeout()
    }

    /** JNDI renders TXT character strings as space-separated, optionally quoted chunks. */
    private fun decodeTxt(value: String): String =
        buildString {
            var offset = 0
            while (offset < value.length) {
                if (value[offset] == ' ') {
                    offset++
                } else if (value[offset] == '"') {
                    offset++
                    var closed = false
                    while (offset < value.length) {
                        when (val next = value[offset++]) {
                            '"' -> {
                                closed = true
                                break
                            }
                            '\\' -> {
                                valid(offset < value.length && value[offset] in "\\\"")
                                append(value[offset++])
                            }
                            else -> append(next)
                        }
                    }
                    valid(closed && (offset == value.length || value[offset] == ' '))
                } else {
                    while (offset < value.length && value[offset] != ' ') {
                        valid(value[offset] !in "\\\"")
                        append(value[offset++])
                    }
                }
            }
        }

    private fun valid(condition: Boolean) {
        if (!condition) throw BlueskyInvalidDnsAnswer()
    }
}

internal class BlueskyDnsTimeout : Exception("Identity DNS lookup timed out")

private fun dnsContext(): DirContext =
    InitialDirContext(
        Hashtable<String, String>().apply {
            put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
            put(Context.PROVIDER_URL, "dns:")
            put(Context.OBJECT_FACTORIES, "")
            put("com.sun.jndi.dns.timeout.initial", "1000")
            put("com.sun.jndi.dns.timeout.retries", "1")
        },
    )
