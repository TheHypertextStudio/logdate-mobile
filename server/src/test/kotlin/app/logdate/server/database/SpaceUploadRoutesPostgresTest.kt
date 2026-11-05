@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.Account
import app.logdate.server.auth.JwtTokenService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.routes.LogDateAccountAuth
import app.logdate.server.routes.spaceXrpcRoutes
import app.logdate.server.space.SpaceBlob
import app.logdate.server.space.SpaceQuota
import app.logdate.server.space.SpaceStore
import app.logdate.server.space.SpaceUploadReservation
import app.logdate.shared.model.AccountStorageMode
import app.logdate.space.SpaceRef
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.repo.Cid
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class SpaceUploadRoutesPostgresTest {
    @Test fun uploadIntentExistsBeforeOpeningTheWriterAndAttachesWithMetadata() = exercise(Scenario.NORMAL)

    @Test fun aFenceRefusesBeforeAnyWriterIsOpened() = exercise(Scenario.HELD)

    @Test fun deletedOwnerCleanupDoesNotGetReplacedByReservationReleaseFailure() = exercise(Scenario.DELETED)

    @Test fun lostMetadataAcknowledgementPreservesTheCanonicalObject() = exercise(Scenario.ACK_LOST)

    @Test fun aReceiptLoserCleansOnlyItsOwnPhysicalObject() = exercise(Scenario.RECEIPT_LOSER)

    private enum class Scenario { NORMAL, HELD, DELETED, ACK_LOST, RECEIPT_LOSER }

    private fun exercise(scenario: Scenario) =
        withBlueskyOAuthDatabase { source ->
            val fixture = Fixture(source, createOwner(source), scenario)
            fixture.operation = fixture.begin()
            if (scenario == Scenario.HELD) assertIs<IdentityAdoptionOutcome.Accepted<*>>(fixture.hold())
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing {
                        spaceXrpcRoutes(
                            true,
                            fixture.store,
                            fixture.storage,
                            fixture.auth,
                            entitlementEnforcer = fixture.quota,
                            objectUploads = SpaceObjectUploads(fixture.uploads),
                        )
                    }
                }
                val response =
                    client.post("/xrpc/app.logdate.space.uploadBlob?space=${fixture.space}") {
                        bearerAuth(fixture.token)
                        if (scenario == Scenario.RECEIPT_LOSER) header("X-LogDate-Upload-Intent", "00000000-0000-4000-8000-000000000001")
                        contentType(ContentType.Application.OctetStream)
                        setBody(fixture.bytes)
                    }
                val expected =
                    when (scenario) {
                        Scenario.NORMAL, Scenario.RECEIPT_LOSER -> HttpStatusCode.OK
                        Scenario.HELD -> HttpStatusCode.Conflict
                        Scenario.DELETED -> HttpStatusCode.Unauthorized
                        Scenario.ACK_LOST -> HttpStatusCode.InternalServerError
                    }
                assertEquals(expected, response.status)
                assertEquals(if (scenario == Scenario.HELD) 0 else 1, fixture.opened)
                val phase =
                    when (scenario) {
                        Scenario.NORMAL, Scenario.ACK_LOST -> listOf("ATTACHED")
                        Scenario.HELD -> emptyList()
                        Scenario.DELETED, Scenario.RECEIPT_LOSER -> listOf("RETIRED")
                    }
                assertEquals(phase, fixture.phases())
                if (scenario == Scenario.DELETED) assertEquals(emptyList(), fixture.memory.listOwnerBlobPaths(fixture.id))
                if (scenario == Scenario.ACK_LOST) {
                    val blob = assertNotNull(fixture.delegate.getBlob(fixture.space, Cid.rawSha256(fixture.bytes).value))
                    assertNotNull(fixture.memory.getBlob(blob.storagePath))
                }
                if (scenario == Scenario.RECEIPT_LOSER) {
                    assertEquals(listOf(fixture.canonicalPath), fixture.memory.listOwnerBlobPaths(fixture.id))
                }
            }
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
        val scenario: Scenario,
    ) {
        val id = owner.id.toJavaUUID()
        val space = SpaceRef.personal(requireNotNull(owner.did)).uri
        val bytes = ByteArray(512) { 7 }
        val memory = InMemoryLogDateBlobStorage()
        val accounts = PostgreSQLAccountRepository()
        val uploads = PostgreSQLObjectUploads()
        val delegate = PostgreSQLSpaceStore()
        val adoption = PostgreSQLIdentityAdoptionRepository(source)
        lateinit var operation: IdentityAdoptionOperation
        var opened = 0
        var canonicalPath: String? = null
        val tokens = JwtTokenService("synthetic-space-upload-test")
        val token = tokens.generateAccessToken(owner.id.toString())
        val auth =
            LogDateAccountAuth(
                AccessTokenVerifier(tokens, DeviceSessionGuard.Disabled),
                accounts,
                AtprotoIdentityService(
                    accounts,
                    SigningKeyService(InMemorySigningKeyRepository(), "synthetic-test-key"),
                    AtprotoIdentityConfig("example.test", "https://example.test"),
                ),
            )
        val quota =
            mockk<EntitlementEnforcer>().also {
                coEvery { it.spaceQuota(any()) } returns SpaceQuota(1_000_000, 0)
            }
        val store: SpaceStore =
            object : SpaceStore by delegate {
                override suspend fun putAdmittedBlob(
                    accountId: UUID,
                    space: String,
                    blob: SpaceBlob,
                    admissionId: String,
                    quota: SpaceQuota?,
                    reservation: SpaceUploadReservation?,
                    identityEpoch: Long,
                ): SpaceBlob {
                    val result = delegate.putAdmittedBlob(accountId, space, blob, admissionId, quota, reservation, identityEpoch)
                    if (scenario == Scenario.ACK_LOST) error("Synthetic metadata acknowledgement loss")
                    return result
                }
            }
        val storage =
            object : LogDateBlobStorage by memory {
                override fun openBlobWriter(request: LogDateBlobStreamRequest): LogDateBlobWriter {
                    opened++
                    beforeOpen()
                    return memory.openBlobWriter(request)
                }
            }

        fun beforeOpen() {
            assertNoIdleTransaction()
            assertEquals(listOf("WRITING"), phases())
            if (scenario == Scenario.DELETED) assertTrue(runBlocking { accounts.deleteAccount(owner.id) })
            if (scenario == Scenario.NORMAL) {
                assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold())
            }
            if (scenario == Scenario.RECEIPT_LOSER) {
                val data = byteArrayOf(9)
                val path =
                    memory.putBlob(
                        LogDateBlobWriteRequest(
                            id,
                            LogDateBlobNamespace.SPACE,
                            "synthetic-winner",
                            contentType = "application/octet-stream",
                            bytes = data,
                        ),
                    )
                canonicalPath = path
                runBlocking {
                    delegate.putBlob(
                        id,
                        space,
                        SpaceBlob(
                            Cid.rawSha256(data).value,
                            "application/octet-stream",
                            1,
                            path,
                            "00000000-0000-4000-8000-000000000001",
                        ),
                    )
                }
            }
        }

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        fun hold() =
            runBlocking {
                adoption.transition(owner.id, operation.id, operation.revision, IdentityAdoptionPhase.FENCED, Clock.System.now())
            }

        fun phases(): List<String> =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement("SELECT phase FROM object_upload_intents WHERE account_id = ? ORDER BY id").use {
                        it.setObject(1, id)
                        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                    }
                }
            }

        fun assertNoIdleTransaction() =
            runBlocking {
                source.blueskyTransaction {
                    prepareStatement(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND state = 'idle in transaction'",
                    ).use {
                        it.executeQuery().use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
    }

    private suspend fun createOwner(source: DataSource): Account {
        val id = Uuid.random()
        val owner =
            Account(
                id,
                id.toHexString(),
                "Synthetic space owner",
                did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
                handle = "synthetic.example.test",
                signingKeyPublic = "synthetic-key",
                storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
                createdAt = Clock.System.now(),
            )
        assertTrue(PostgreSQLAccountRepository().create(owner))
        source.blueskyTransaction {
            prepareStatement(
                "INSERT INTO hosted_plc_operations(id, account_id, did, operation_type, operation_json, created_at, " +
                    "publication_attempted) VALUES (?, ?, ?, 'plc_operation', '{}', ?, FALSE)",
            ).use {
                it.setObject(1, Uuid.random().toJavaUUID())
                it.setObject(2, id.toJavaUUID())
                it.setString(3, owner.did)
                it.setTimestamp(4, Clock.System.now().timestamp())
                assertEquals(1, it.executeUpdate())
            }
        }
        return owner
    }
}
