package studio.hypertext.atproto.plc

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.identity.AtprotoDid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Comprehensive coverage tests for the PLC (Placeholder) data models and client logic.
 *
 * This suite focuses on the behavioral aspects of the PLC implementation, including:
 * - Structural helpers and signing logic for [PlcOperation] and [PlcTombstone].
 * - Correct serialization and deserialization of polymorphic log entries.
 * - Edge cases in the directory client, such as default parameter handling and error
 *   response mapping.
 * - Lifecycle management of indexed operations within the PLC ecosystem.
 */
class PlcCoverageTest {
    @Test
    fun `plc models expose tombstone and indexed helpers`() {
        val operation =
            PlcOperation(
                services = mapOf("atproto_pds" to PlcService("AtprotoPersonalDataServer", "https://logdate.app")),
                alsoKnownAs = listOf("at://alice.logdate.app"),
                rotationKeys = listOf("did:key:zRotation"),
                verificationMethods = mapOf("atproto" to "did:key:zSigning"),
            )
        val logEntry: PlcLogEntry = operation
        val signedOperation = operation.withSignature("sig-1")
        val unsignedTombstone = PlcUnsignedTombstone(prev = "bafy-prev")
        val tombstone = unsignedTombstone.signed("sig-2")
        val indexed =
            PlcIndexedOperation(
                did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz"),
                operation = tombstone,
                cid = "bafy-indexed",
                nullified = false,
                createdAt = "2023-04-26T06:19:25.508Z",
            )

        assertEquals(false, logEntry.isSigned)
        assertEquals(true, signedOperation.isSigned)
        assertEquals("sig-1", signedOperation.sig)
        assertEquals("bafy-prev", tombstone.prev)
        assertEquals("sig-2", tombstone.sig)
        assertEquals(unsignedTombstone, tombstone.unsigned())
        assertEquals("sig-3", tombstone.withSignature("sig-3").sig)
        assertEquals("2023-04-26T06:19:25.508Z", indexed.createdAt)
        assertTrue(indexed.operation is PlcTombstone)
    }

    @Test
    fun `serializer rejects unsupported plc log entry type`() {
        assertFailsWith<SerializationException> {
            Json.decodeFromString(
                PlcLogEntrySerializer,
                """{"sig":"sig","prev":null,"type":"unsupported"}""",
            )
        }
    }

    @Test
    fun `plc directory interface export defaults to null parameters`() =
        runTest {
            val client = RecordingDirectoryClient()

            client.export().getOrThrow()

            assertNull(client.lastAfter)
            assertNull(client.lastCount)
        }

    @Test
    fun `ktor plc client supports export defaults and tombstone submission`() =
        runTest {
            val did = AtprotoDid.require("did:plc:ewvi7nxzyoun6zhxrhs64oiz")
            val requestBodies = mutableListOf<String>()
            val client =
                KtorPlcDirectoryClient(
                    httpClient =
                        HttpClient(MockEngine) {
                            engine {
                                addHandler { request ->
                                    when (request.url.encodedPath) {
                                        "/export" -> {
                                            respond(
                                                content = "",
                                                headers = headersOf("Content-Type", ContentType.Text.Plain.toString()),
                                            )
                                        }

                                        "/did:plc:ewvi7nxzyoun6zhxrhs64oiz" -> {
                                            requestBodies += readBody(request.body)
                                            respond(
                                                content = "",
                                                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                                            )
                                        }

                                        else -> {
                                            error("Unexpected PLC request: ${request.url}")
                                        }
                                    }
                                }
                            }
                        },
                )

            assertTrue(client.export().isSuccess)
            assertTrue(client.submit(did, PlcUnsignedTombstone(prev = "bafy-prev").signed("sig-1")).isSuccess)
            assertTrue(requestBodies.single().contains("\"type\":\"plc_tombstone\""))
        }

    @Test
    fun `ktor plc client returns failure for non success export responses`() =
        runTest {
            val client =
                KtorPlcDirectoryClient(
                    httpClient =
                        HttpClient(MockEngine) {
                            engine {
                                addHandler {
                                    respond(
                                        content = "",
                                        status = io.ktor.http.HttpStatusCode.BadRequest,
                                        headers = headersOf("Content-Type", ContentType.Text.Plain.toString()),
                                    )
                                }
                            }
                        },
                )

            assertTrue(client.export().isFailure)
        }

    private class RecordingDirectoryClient : PlcDirectoryClient {
        var lastAfter: String? = "unset"
        var lastCount: Int? = -1

        override suspend fun getDocument(did: AtprotoDid) =
            Result.failure<studio.hypertext.atproto.identity.DidDocument>(NotImplementedError())

        override suspend fun getOperationLog(did: AtprotoDid) = Result.failure<List<PlcLogEntry>>(NotImplementedError())

        override suspend fun getAuditLog(did: AtprotoDid) = Result.failure<List<PlcIndexedOperation>>(NotImplementedError())

        override suspend fun export(
            after: String?,
            count: Int?,
        ): Result<List<PlcIndexedOperation>> {
            lastAfter = after
            lastCount = count
            return Result.success(emptyList())
        }

        override suspend fun submit(
            did: AtprotoDid,
            entry: PlcLogEntry,
        ): Result<Unit> = Result.failure(NotImplementedError())
    }

    private fun readBody(body: OutgoingContent): String =
        when (body) {
            is OutgoingContent.ByteArrayContent -> body.bytes().decodeToString()
            is OutgoingContent.NoContent -> ""
            else -> error("Unexpected PLC request body type: ${body::class}")
        }
}
