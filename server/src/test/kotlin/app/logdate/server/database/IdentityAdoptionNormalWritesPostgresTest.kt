@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.routes.LogDateAccountAuth
import app.logdate.server.routes.spaceXrpcRoutes
import app.logdate.server.space.SpaceQuota
import app.logdate.space.SpaceRef
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import studio.hypertext.atproto.repo.Cid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class IdentityAdoptionNormalWritesPostgresTest {
    @Test fun aFreshCanonicalSessionCanPutAndDeleteAfterRealAdoptionCommit() =
        withAdoptionCommit {
            assertNotNull(commit())
            val fixture = WritesFixture(this)
            fixture.serve {
                val put =
                    client.post("/xrpc/app.logdate.space.putRecord") {
                        bearerAuth(fixture.fresh)
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("space", fixture.space)
                                put("rkey", "after-adoption")
                                put("record", genericSealed("after"))
                            }.toString(),
                        )
                    }
                assertEquals(HttpStatusCode.OK, put.status)
                assertNotNull(fixture.store.get(fixture.space, operation.binding.targetDid, "after-adoption"))
                val deleted =
                    client.post("/xrpc/app.logdate.space.deleteRecord") {
                        bearerAuth(fixture.fresh)
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("space", fixture.space)
                                put("rkey", "after-adoption")
                            }.toString(),
                        )
                    }
                assertEquals(HttpStatusCode.OK, deleted.status)
                assertNull(fixture.store.get(fixture.space, operation.binding.targetDid, "after-adoption"))
            }
        }

    @Test fun aFreshCanonicalSessionCanReserveUploadAndAttachMediaAfterRealAdoptionCommit() =
        withAdoptionCommit {
            assertNotNull(commit())
            val fixture = WritesFixture(this)
            fixture.serve {
                val bytes = ByteArray(512) { 7 }
                val response =
                    client.post("/xrpc/app.logdate.space.uploadBlob") {
                        bearerAuth(fixture.fresh)
                        parameter("space", fixture.space)
                        contentType(ContentType.Application.OctetStream)
                        setBody(bytes)
                    }
                assertEquals(HttpStatusCode.OK, response.status)
                val blob = assertNotNull(fixture.store.getBlob(fixture.space, Cid.rawSha256(bytes).value))
                assertEquals(bytes.toList(), fixture.storage.getBlob(blob.storagePath)?.toList())
                auth.source.blueskyTransaction {
                    prepareStatement("SELECT identity_epoch,phase FROM object_upload_intents WHERE storage_path=?").use {
                        it.setString(1, blob.storagePath)
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(1L, rows.getLong(1))
                            assertEquals("ATTACHED", rows.getString(2))
                        }
                    }
                }
            }
        }

    @Test fun retiredDidAndUnboundLegacyTokensCannotAccessTheAdoptedSpace() =
        withAdoptionCommit {
            assertNotNull(commit())
            val fixture = WritesFixture(this)
            fixture.serve {
                for (token in listOf(fixture.old, fixture.unbound)) {
                    val response =
                        client.get("/xrpc/app.logdate.space.getRecord") {
                            bearerAuth(token)
                            parameter("space", fixture.space)
                            parameter("rkey", "converted-note")
                        }
                    assertEquals(HttpStatusCode.Unauthorized, response.status)
                    val write =
                        client.post("/xrpc/app.logdate.space.putRecord") {
                            bearerAuth(token)
                            contentType(ContentType.Application.Json)
                            setBody(
                                buildJsonObject {
                                    put("space", fixture.space)
                                    put("rkey", "stale-attempt")
                                    put("record", genericSealed("stale"))
                                }.toString(),
                            )
                        }
                    assertEquals(HttpStatusCode.Unauthorized, write.status)
                }
                assertNull(fixture.store.get(fixture.space, operation.binding.targetDid, "stale-attempt"))
            }
        }

    @Test fun aHeldIdentityReturnsARecoverableConflictForOrdinaryRecordMutations() =
        withAdoptionCommit {
            auth.source.blueskyTransaction {
                prepareStatement("UPDATE identity_adoption_operations SET lease_expires_at=? WHERE id=?").use {
                    it.setTimestamp(1, (Clock.System.now() + 10.minutes).timestamp())
                    it.setString(2, operation.id)
                    assertEquals(1, it.executeUpdate())
                }
            }
            val fixture = WritesFixture(this)
            val sourceSpace = SpaceRef.personal(operation.binding.sourceDid).uri
            fixture.serve {
                for (method in listOf("putRecord", "deleteRecord")) {
                    val response =
                        client.post("/xrpc/app.logdate.space.$method") {
                            bearerAuth(fixture.old)
                            contentType(ContentType.Application.Json)
                            setBody(
                                buildJsonObject {
                                    put("space", sourceSpace)
                                    put("rkey", "held-write")
                                    if (method == "putRecord") put("record", genericSealed("held"))
                                }.toString(),
                            )
                        }
                    assertEquals(HttpStatusCode.Conflict, response.status)
                    assertEquals(
                        "IdentityAdoptionInProgress",
                        Json
                            .parseToJsonElement(response.bodyAsText())
                            .jsonObject["error"]
                            ?.jsonPrimitive
                            ?.content,
                    )
                }
                assertNull(fixture.store.get(sourceSpace, operation.binding.sourceDid, "held-write"))
            }
        }

    private class WritesFixture(
        val commit: IdentityAdoptionCommitFixture,
    ) {
        val accounts = PostgreSQLAccountRepository()
        val store = PostgreSQLSpaceStore()
        val storage = InMemoryLogDateBlobStorage()
        val space = SpaceRef.personal(commit.operation.binding.targetDid).uri
        private val tokens = JwtTokenService("synthetic-normal-adoption-write-test")
        private val account =
            commit.operation.binding.accountId
                .toString()
        val fresh = tokens.generateAccessToken(account, commit.operation.binding.targetDid)
        val old = tokens.generateAccessToken(account, commit.operation.binding.sourceDid)
        val unbound = tokens.generateAccessToken(account)
        private val auth =
            LogDateAccountAuth(
                AccessTokenVerifier(tokens, DeviceSessionGuard.Disabled),
                accounts,
                AtprotoIdentityService(
                    accounts,
                    SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-key"),
                    AtprotoIdentityConfig("example.test", "https://example.test"),
                ),
            )
        private val quota =
            mockk<EntitlementEnforcer>().also {
                coEvery { it.spaceQuota(any()) } returns SpaceQuota(1_000_000, 0)
            }

        fun serve(block: suspend ApplicationTestBuilder.() -> Unit) =
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        spaceXrpcRoutes(
                            true,
                            store,
                            storage,
                            auth,
                            entitlementEnforcer = quota,
                            objectUploads = SpaceObjectUploads(PostgreSQLObjectUploads()),
                        )
                    }
                }
                block()
            }
    }
}
