package app.logdate.server.bluesky

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.syntax.Handle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.naming.NamingException
import javax.naming.directory.BasicAttribute
import javax.naming.directory.BasicAttributes
import javax.naming.directory.DirContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JndiBlueskyTxtLookupTest {
    @Test
    fun `the DNS deadline permits HTTPS fallback without suppressing caller cancellation`() =
        runBlocking<Unit> {
            val context = context()
            val release = CountDownLatch(1)
            every { context.getAttributes(any<String>(), any<Array<String>>()) } answers {
                release.await()
                BasicAttributes()
            }
            val did = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx")
            val requested = mutableListOf<String>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        requested += request.url.toString()
                        respond(did.toString())
                    },
                ) { followRedirects = false }
            try {
                val resolver = BlueskyIdentityResolver(http, JndiBlueskyTxtLookup(timeoutMillis = 50) { context })
                assertEquals(did, resolver.resolveDid(Handle.require("alice.example.com")).getOrThrow())
                assertEquals(listOf("https://alice.example.com/.well-known/atproto-did"), requested)
            } finally {
                release.countDown()
                http.close()
            }
        }

    @Test
    fun `lookup uses absolute TXT names joins chunks and closes its context`() =
        runBlocking<Unit> {
            val context = context()
            val names = slot<Array<String>>()
            val values =
                BasicAttribute("TXT").apply {
                    add("did=did:plc: abcdefghijklmnopqrstuvwx")
                    add("\"did=did:web:\" identity.example.com")
                    add("\"value with spaces\" \" and a quote \\\"\"")
                    add("\"\" untouched")
                }
            every { context.getAttributes(any<String>(), capture(names)) } returns BasicAttributes().apply { put(values) }
            assertEquals(
                listOf(
                    "did=did:plc:abcdefghijklmnopqrstuvwx",
                    "did=did:web:identity.example.com",
                    "value with spaces and a quote \"",
                    "untouched",
                ),
                JndiBlueskyTxtLookup { context }.records(NAME),
            )
            assertEquals(listOf("TXT"), names.captured.toList())
            verify(exactly = 1) { context.getAttributes("$NAME.", any<Array<String>>()) }
            verify(exactly = 1) { context.close() }
        }

    @Test
    fun `missing TXT and DNS errors still close the context`() =
        runBlocking<Unit> {
            val empty = context()
            every { empty.getAttributes(any<String>(), any<Array<String>>()) } returns BasicAttributes()
            assertTrue(JndiBlueskyTxtLookup { empty }.records(NAME).isEmpty())
            verify(exactly = 1) { empty.close() }
            val failed = context()
            every { failed.getAttributes(any<String>(), any<Array<String>>()) } throws NamingException("DNS failed")
            assertFailsWith<NamingException> { JndiBlueskyTxtLookup { failed }.records(NAME) }
            verify(exactly = 1) { failed.close() }
        }

    @Test
    fun `malformed and oversized answers fail closed instead of being partially trusted`() =
        runBlocking<Unit> {
            for (value in listOf("\"unterminated", "raw\"quote", "\"bad\\q\"", "x".repeat(4097))) {
                val context = context()
                every { context.getAttributes(any<String>(), any<Array<String>>()) } returns BasicAttributes("TXT", value)
                assertFailsWith<BlueskyInvalidDnsAnswer> { JndiBlueskyTxtLookup { context }.records(NAME) }
                verify(exactly = 1) { context.close() }
            }
            val context = context()
            val many = BasicAttribute("TXT").apply { repeat(129) { add("different-$it") } }
            every { context.getAttributes(any<String>(), any<Array<String>>()) } returns BasicAttributes().apply { put(many) }
            assertFailsWith<BlueskyInvalidDnsAnswer> { JndiBlueskyTxtLookup { context }.records(NAME) }
            verify(exactly = 1) { context.close() }
        }

    @Test
    fun `invalid or reserved names never construct a directory context`() =
        runBlocking<Unit> {
            var created = 0
            val lookup =
                JndiBlueskyTxtLookup {
                    created++
                    context()
                }
            for (name in listOf("ldap://host.local", "alice.example.com", "_atproto.alice.local", "_atproto.alice.example.com/path")) {
                assertFailsWith<IllegalArgumentException> { lookup.records(name) }
            }
            assertEquals(0, created)
        }

    @Test
    fun `canceling a blocking DNS lookup interrupts it and closes the context`() =
        runBlocking<Unit> {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val context = context()
            every { context.getAttributes(any<String>(), any<Array<String>>()) } answers {
                entered.countDown()
                release.await()
                BasicAttributes()
            }
            val request = launch { JndiBlueskyTxtLookup { context }.records(NAME) }
            try {
                assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
                request.cancelAndJoin()
                verify(exactly = 1) { context.close() }
            } finally {
                release.countDown()
                request.cancelAndJoin()
            }
        }

    private fun context(): DirContext = mockk<DirContext>().also { context -> every { context.close() } returns Unit }

    private companion object {
        const val NAME = "_atproto.alice.example.com"
    }
}
