package app.logdate.server.bluesky

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.syntax.Handle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BlueskyIdentityResolverTest {
    @Test
    fun `DNS-only custom handles resolve did web and confirm the backlink`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.did = WEB
                fixture.records = listOf("did=$WEB")
                assertEquals(
                    WEB,
                    fixture.resolver
                        .resolveDocument(HANDLE)
                        .getOrThrow()
                        .id,
                )
                assertEquals(listOf("_atproto.alice.example.com"), fixture.queries)
                assertEquals(listOf("https://identity.example.com/.well-known/did.json"), fixture.requests)
            }
        }

    @Test
    fun `ambiguous DNS never falls back to an unrelated HTTPS identity`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.records = listOf("did=$PLC", "did=$WEB")
                assertTrue(fixture.resolver.resolveDid(HANDLE).isFailure)
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test
    fun `duplicate matching and unrelated TXT records do not hide a valid answer`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.records = listOf("v=unrelated", "did=not-a-did", "did=$PLC", "did=$PLC")
                assertEquals(PLC, fixture.resolver.resolveDid(HANDLE).getOrThrow())
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test
    fun `missing or failed DNS falls back to HTTPS and PLC resolution`() =
        runBlocking<Unit> {
            for (failure in listOf(null, IllegalStateException("DNS unavailable"))) {
                Fixture().use { fixture ->
                    fixture.dnsFailure = failure
                    assertEquals(
                        PLC,
                        fixture.resolver
                            .resolveDocument(HANDLE)
                            .getOrThrow()
                            .id,
                    )
                    assertEquals(
                        listOf("https://alice.example.com/.well-known/atproto-did", "https://plc.directory/$PLC"),
                        fixture.requests,
                    )
                }
            }
        }

    @Test
    fun `a claimed handle must link back from the DID document`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.records = listOf("did=$PLC")
                fixture.aliases = listOf("at://other.example.com", "at://$HANDLE")
                assertTrue(fixture.resolver.resolveDocument(HANDLE).isFailure)
            }
        }

    @Test
    fun `reserved handle and did web domains fail before any lookup`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                for (tld in listOf("alt", "arpa", "example", "internal", "invalid", "local", "localhost", "onion", "test")) {
                    assertTrue(fixture.resolver.resolveDid(Handle.require("alice.$tld")).isFailure)
                    assertTrue(fixture.resolver.resolveDocument(AtprotoDid.require("did:web:alice.$tld")).isFailure)
                }
                for (did in listOf(
                    "did:web:identity.example.com%3A443",
                    "did:web:identity.example.com%3A8443",
                    "did:web:localhost%3A3000",
                )) {
                    assertTrue(fixture.resolver.resolveDocument(AtprotoDid.require(did)).isFailure)
                }
                assertTrue(fixture.queries.isEmpty())
                assertTrue(fixture.requests.isEmpty())
            }
        }

    @Test
    fun `a valid handle too long for the prefixed DNS name still uses HTTPS`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                val long = Handle.require(listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(56), "com").joinToString("."))
                assertEquals(PLC, fixture.resolver.resolveDid(long).getOrThrow())
                assertTrue(fixture.queries.isEmpty())
                assertEquals(listOf("https://$long/.well-known/atproto-did"), fixture.requests)
            }
        }

    @Test
    fun `HTTPS follows a bounded public relative redirect`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.redirect = "/resolved-did"
                assertEquals(PLC, fixture.resolver.resolveDid(HANDLE).getOrThrow())
                assertEquals(
                    listOf("https://alice.example.com/.well-known/atproto-did", "https://alice.example.com/resolved-did"),
                    fixture.requests,
                )
            }
        }

    @Test
    fun `redirect loops and unsafe redirect destinations fail without following them`() =
        runBlocking<Unit> {
            for (target in listOf("http://other.example.com/", "https://user@other.example.com/", "https://host.local/")) {
                Fixture().use { fixture ->
                    fixture.redirect = target
                    assertTrue(fixture.resolver.resolveDid(HANDLE).isFailure)
                    assertEquals(1, fixture.requests.size)
                }
            }
            Fixture().use { fixture ->
                fixture.redirect = "/.well-known/atproto-did"
                assertTrue(fixture.resolver.resolveDid(HANDLE).isFailure)
                assertEquals(4, fixture.requests.size)
            }
        }

    @Test
    fun `oversized valid identity responses are refused`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.handlePadding = " ".repeat(16_384)
                assertTrue(fixture.resolver.resolveDid(HANDLE).isFailure)
            }
            Fixture().use { fixture ->
                fixture.records = listOf("did=$PLC")
                fixture.aliases += "x".repeat(100_000)
                assertTrue(fixture.resolver.resolveDocument(HANDLE).isFailure)
            }
        }

    @Test
    fun `a document cannot substitute a different DID`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.records = listOf("did=$PLC")
                fixture.did = WEB
                assertTrue(fixture.resolver.resolveDocument(HANDLE).isFailure)
            }
        }

    @Test
    fun `DNS and HTTP cancellation propagate instead of starting fallback work`() =
        runBlocking<Unit> {
            Fixture().use { fixture ->
                fixture.dnsFailure = CancellationException("cancel DNS")
                assertFailsWith<CancellationException> { fixture.resolver.resolveDid(HANDLE) }
                assertTrue(fixture.requests.isEmpty())
            }
            Fixture().use { fixture ->
                fixture.httpFailure = CancellationException("cancel HTTP")
                assertFailsWith<CancellationException> { fixture.resolver.resolveDocument(PLC) }
            }
        }

    private class Fixture : AutoCloseable {
        var did = PLC
        var aliases = listOf("at://$HANDLE")
        var records = emptyList<String>()
        var redirect: String? = null
        var handlePadding = ""
        var dnsFailure: Exception? = null
        var httpFailure: Exception? = null
        val queries = mutableListOf<String>()
        val requests = mutableListOf<String>()
        private val http =
            HttpClient(
                MockEngine { request ->
                    httpFailure?.let { throw it }
                    requests += request.url.toString()
                    if (request.url.encodedPath == "/.well-known/atproto-did" && redirect != null) {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, requireNotNull(redirect)))
                    } else if (request.url.encodedPath in
                        setOf("/.well-known/atproto-did", "/resolved-did")
                    ) {
                        respond(handlePadding + did.toString())
                    } else {
                        respond(
                            Json.encodeToString(DidDocument(id = did, alsoKnownAs = aliases)),
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                },
            ) { followRedirects = false }
        val resolver =
            BlueskyIdentityResolver(
                http,
                BlueskyTxtLookup { name ->
                    queries += name
                    dnsFailure?.let { throw it }
                    records
                },
            )

        override fun close() {
            http.close()
        }
    }

    private companion object {
        val HANDLE = Handle.require("alice.example.com")
        val PLC = AtprotoDid.require("did:plc:abcdefghijklmnopqrstuvwx")
        val WEB = AtprotoDid.require("did:web:identity.example.com")
    }
}
