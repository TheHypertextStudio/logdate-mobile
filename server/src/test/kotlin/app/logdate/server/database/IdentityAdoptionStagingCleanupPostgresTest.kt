@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.crypto.EncryptionService
import app.logdate.server.di.installServerKoin
import app.logdate.server.identity.adoption.IdentityAdoptionAction
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.routes.accountApiRoutes
import app.logdate.server.transfer.TransferSlots
import app.logdate.space.SpaceRef
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class IdentityAdoptionStagingCleanupPostgresTest {
    @Test fun boundedPartialCleanupResumesItsRemainingExactRows() =
        withAdoptionVerification {
            assertNotNull(verify())
            val before = copies.used()
            auth.clock.time += 8.days
            val cleanup = PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock, batchSize = 1)
            assertFalse(cleanup.cleanup(operation.id))
            assertTrue(copies.used() in 1 until before)
            assertTrue(operation.id in cleanup.pending())
            assertTrue(cleanup.cleanup(operation.id))
            assertEquals(0L, copies.used())
            assertTrue(cleanup.cleanup(operation.id))
        }

    @Test fun applicationOwnedWorkerActuallySweepsCanceledNonMediaStaging() =
        withGenericAdoption {
            assertNotNull(put(request()))
            auth.operations.transition(
                current.binding.accountId,
                current.id,
                current.revision,
                IdentityAdoptionPhase.CANCELED,
                auth.clock.now(),
            )
            val runtime =
                composeIdentityAdoptionRuntime(
                    auth.source,
                    mockk(),
                    mockk(),
                    auth.web,
                    "https://logdate.app",
                    InMemoryLogDateBlobStorage(),
                    { Long.MAX_VALUE },
                    EncryptionService.fromEnvironment(),
                    TransferSlots(),
                    auth.clock,
                )
            try {
                testApplication {
                    application {
                        installServerKoin(true, adoptionRuntimeFactory = { _, _, _ -> runtime }) { null }
                        install(ContentNegotiation) { json() }
                        accountApiRoutes()
                    }
                    assertEquals(HttpStatusCode.OK, client.get("/api/v1/server/info").status)
                    withTimeout(3000) { while (used() != 0L) delay(10) }
                }
            } finally {
                runtime.close()
            }
        }

    @Test fun expiredPrivateRecordsMappingsOriginsAndManifestReleaseTheirExactQuota() =
        withAdoptionVerification {
            val verified = assertNotNull(verify())
            assertTrue(copies.used() > 0)
            auth.clock.time += 8.days
            val cleanup = PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock)
            assertTrue(operation.id in cleanup.pending())
            assertTrue(cleanup.cleanup(operation.id))
            assertEquals(0L, copies.used())
            assertEquals(0L, count())
            val current = assertNotNull(auth.operations.status(operation.binding.accountId, operation.id)).operation
            assertEquals(IdentityAdoptionPhase.PREPARING, current.phase)
            assertTrue(current.revision > verified.operation.revision)
            assertFalse(operation.id in cleanup.pending())
            assertTrue(cleanup.cleanup(operation.id))
            auth.source.blueskyTransaction {
                createStatement().use {
                    it.executeQuery("SELECT content FROM sync_content WHERE id='legacy-note'").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("opaque source", rows.getString(1))
                    }
                }
            }
        }

    @Test fun onlyAuthenticatedDeviceActivityRenewsThePrivateCopyRetention() =
        withGenericAdoption {
            val value = request()
            assertNotNull(put(value))
            auth.clock.time += 6.days
            assertNotNull(read(value))
            auth.clock.time += 2.days
            val cleanup = PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock)
            assertFalse(current.id in cleanup.pending())
            assertFalse(cleanup.cleanup(current.id))
            assertTrue(used() > 0)
            auth.clock.time += 6.days
            val ticket =
                assertNotNull(auth.actions.issue(current, IdentityAdoptionAction.READ_STAGED, genericReadDigest(value), auth.clock.now()))
            assertNull(store.read(current, ticket, "A".repeat(86), value, auth.clock.now()))
            assertTrue(current.id in cleanup.pending())
            assertTrue(cleanup.cleanup(current.id))
            assertEquals(0L, used())
            assertNull(store.read(current, ticket, auth.actionSignature(ticket, current), value, auth.clock.now()))
        }

    @Test fun activeVerificationAndCommittedRecoveryNeverBecomePrivateGcCandidates() =
        withAdoptionCommit {
            val cleanup = PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock)
            val before = fixture.copies.used()
            assertFalse(operation.id in cleanup.pending())
            assertFalse(cleanup.cleanup(operation.id))
            assertEquals(before, fixture.copies.used())
            assertNotNull(commit())
            val committedBytes = fixture.copies.used()
            auth.clock.time += 8.days
            assertFalse(operation.id in cleanup.pending())
            assertFalse(cleanup.cleanup(operation.id))
            assertEquals(committedBytes, fixture.copies.used())
            assertEquals(1L, count("identity_adoption_receipts"))
            assertEquals(1L, count("identity_adoption_record_receipts"))
            val target = SpaceRef.personal(operation.binding.targetDid).uri
            assertNotNull(PostgreSQLSpaceStore().get(target, operation.binding.targetDid, "converted-note"))
        }

    @Test fun accountDeletionCascadesPrivateMetadataWithoutRecreatingUsage() =
        withAdoptionVerification {
            assertNotNull(verify())
            assertTrue(PostgreSQLAccountRepository().deleteAccount(operation.binding.accountId))
            assertEquals(0L, copies.used())
            assertEquals(0L, count())
            assertTrue(PostgreSQLIdentityAdoptionStagingCleanup(auth.source, auth.clock).pending().isEmpty())
            auth.source.blueskyTransaction {
                prepareStatement("SELECT 1 FROM account_storage_usage WHERE account_id=?").use {
                    it.setObject(1, operation.binding.accountId.toJavaUUID())
                    it.executeQuery().use { rows -> assertFalse(rows.next()) }
                }
                val tables =
                    listOf(
                        "identity_adoption_staged_records",
                        "identity_adoption_staged_origins",
                        "identity_adoption_source_mappings",
                        "identity_adoption_staged_blobs",
                    )
                for (table in tables) {
                    prepareStatement("SELECT 1 FROM $table WHERE operation_id=?").use {
                        it.setString(1, operation.id)
                        it.executeQuery().use { rows -> assertFalse(rows.next()) }
                    }
                }
            }
        }
}
