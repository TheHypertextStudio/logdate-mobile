@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.identity.IdentityLifecycleConflictException
import app.logdate.server.identity.adoption.IdentityAdoptionBinding
import app.logdate.server.identity.adoption.IdentityAdoptionOperation
import app.logdate.server.identity.adoption.IdentityAdoptionOutcome
import app.logdate.server.identity.adoption.IdentityAdoptionPhase
import app.logdate.server.identity.adoption.IdentityAdoptionRefusal
import app.logdate.server.identity.adoption.IdentityWriteExpectation
import app.logdate.server.identity.adoption.IdentityWriteRefusedException
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.LogDateBlobNamespace
import app.logdate.server.logdate.LogDateBlobStreamRequest
import app.logdate.server.logdate.LogDateBlobWriteRequest
import app.logdate.server.logdate.LogDateBlobWriter
import app.logdate.server.logdate.ObjectUpload
import app.logdate.server.logdate.ObjectUploadKind
import app.logdate.server.logdate.SpaceObjectUploads
import app.logdate.server.migration.SpaceMigration
import app.logdate.server.space.SpaceBlob
import app.logdate.shared.model.AccountStorageMode
import app.logdate.shared.model.sync.SpaceMigrationPhase
import app.logdate.space.SpaceRef
import studio.hypertext.atproto.repo.Cid
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

class SpaceUploadAdmissionPostgresTest {
    @Test fun sourceSnapshotCannotFenceAnInFlightUploadAndFinalizationAttachesItsTicket() =
        withFixture {
            val operation = begin()
            val (ticket, blob) = candidate()
            assertEquals(IdentityAdoptionOutcome.Refused(IdentityAdoptionRefusal.MUTATION_PENDING), hold(operation))
            store.putAdmittedBlob(id, space, blob, ticket.id)
            assertEquals("ATTACHED", phase(ticket))
            assertIs<IdentityAdoptionOutcome.Accepted<*>>(hold(operation))
        }

    @Test fun anUnknownAdmissionCannotCommitMetadataOrChargeQuota() =
        withFixture {
            val blob = rawBlob()
            assertFailsWith<IdentityWriteRefusedException> { store.putAdmittedBlob(id, space, blob, "unknown") }
            assertEquals(null, store.getBlob(space, blob.cid))
            assertEquals(0L, store.storedBytes(id))
        }

    @Test fun anAdmissionCannotBeReusedByAnotherAccount() =
        withFixture {
            val (ticket, blob) = candidate()
            val other = owner.copy(id = Uuid.random(), username = Uuid.random().toHexString(), did = "did:web:other.example")
            assertTrue(accounts.create(other))
            assertFailsWith<IdentityWriteRefusedException> {
                store.putAdmittedBlob(other.id.toJavaUUID(), space, blob, ticket.id)
            }
            assertEquals(null, store.getBlob(space, blob.cid))
            assertEquals("STORED", phase(ticket))
        }

    @Test fun aWrongOwnerOrMalformedSpaceRefusesBeforeOpeningAnObject() =
        withFixture {
            for (target in listOf(SpaceRef.personal("did:web:other.example").uri, "not-a-space", "$space/extra")) {
                assertFailsWith<IdentityWriteRefusedException> {
                    uploads.begin(IdentityWriteExpectation(owner.id, 0), null, "synthetic/$target", ObjectUploadKind.SPACE, target)
                }
            }
        }

    @Test fun aReverseMigrationHoldPreventsUploadAdmission() =
        withFixture {
            reverseHold()
            assertFailsWith<IdentityWriteRefusedException> { candidate() }
        }

    @Test fun aReverseMigrationHoldAfterTransferPreventsMetadataFinalization() =
        withFixture {
            val (ticket, blob) = candidate()
            reverseHold()
            assertFailsWith<IdentityWriteRefusedException> { store.putAdmittedBlob(id, space, blob, ticket.id) }
            assertEquals(null, store.getBlob(space, blob.cid))
            assertEquals("STORED", phase(ticket))
        }

    @Test fun aReceiptLoserLeavesItsOwnCandidateUnattachedAndPreservesCanonicalBytes() =
        withFixture {
            val original = rawBlob().copy(uploadIntent = "same-intent")
            store.putBlob(id, space, original)
            val (ticket, other) = candidate(bytes = byteArrayOf(2))
            val actual = store.putAdmittedBlob(id, space, other.copy(uploadIntent = "same-intent"), ticket.id)
            assertEquals(original.storagePath, actual.storagePath)
            assertEquals("STORED", phase(ticket))
            assertTrue(
                uploads.cleanup(ticket) {
                    storage.deleteBlobIfExists(it)
                    true
                },
            )
            assertNotNull(storage.getBlob(original.storagePath))
            assertEquals("RETIRED", phase(ticket))
        }

    @Test fun deletionDuringTransferCannotDropTheTicketOrRegisterIntoANewAuthority() =
        withFixture {
            val (ticket, blob) = candidate()
            assertTrue(accounts.deleteAccount(owner.id))
            assertFailsWith<IdentityWriteRefusedException> { store.putAdmittedBlob(id, space, blob, ticket.id) }
            assertTrue(
                uploads.cleanup(ticket) {
                    storage.deleteBlobIfExists(it)
                    true
                },
            )
            assertEquals("RETIRED", phase(ticket))
            assertEquals(null, storage.getBlob(blob.storagePath))
        }

    @Test fun cleanupRestoresItsFullSpaceBindingAfterRepositoryRestart() =
        withFixture {
            val (ticket, _) = candidate()
            assertEquals(false, uploads.cleanup(ticket) { false })
            val restarted = PostgreSQLObjectUploads()
            val restored = restarted.pendingCleanup().single()
            assertEquals(ticket, restored)
            assertEquals(space, restored.space)
            assertTrue(
                restarted.cleanup(restored) {
                    storage.deleteBlobIfExists(it)
                    true
                },
            )
        }

    @Test fun genericAttachCannotInvokeMetadataCallbacksForSpaceUploads() =
        withFixture {
            val (ticket, _) = candidate()
            var called = false
            assertFailsWith<IdentityLifecycleConflictException> { uploads.attach(ticket, { called = true }) }
            assertEquals(false, called)
            assertEquals("STORED", phase(ticket))
        }

    @Test fun anAbandonedButClosedWriterRetiresItsTicketInsteadOfBlockingAdoptionForever() =
        withFixture {
            val request =
                LogDateBlobWriteRequest(
                    id,
                    LogDateBlobNamespace.SPACE,
                    Uuid.random().toString(),
                    contentType = "application/octet-stream",
                    bytes = byteArrayOf(),
                )
            val ticket =
                uploads.begin(
                    IdentityWriteExpectation(owner.id, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.SPACE,
                    space,
                )
            val writer = storage.openBlobWriter(LogDateBlobStreamRequest(id, request.namespace, request.blobId, request.contentType))
            SpaceObjectUploads(uploads).discard(ticket, writer, storage)
            assertEquals("RETIRED", phase(ticket))
        }

    @Test fun anUncertainWriterCloseKeepsItsBarrierAndNeverCallsDestructiveAbort() =
        withFixture {
            val request =
                LogDateBlobWriteRequest(
                    id,
                    LogDateBlobNamespace.SPACE,
                    Uuid.random().toString(),
                    contentType = "application/octet-stream",
                    bytes = byteArrayOf(),
                )
            val ticket =
                uploads.begin(
                    IdentityWriteExpectation(owner.id, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.SPACE,
                    space,
                )
            val delegate = storage.openBlobWriter(LogDateBlobStreamRequest(id, request.namespace, request.blobId, request.contentType))
            var aborts = 0
            val writer =
                object : LogDateBlobWriter by delegate {
                    override fun closeForCleanup() {
                        error("Synthetic uncertain close")
                    }

                    override fun abort() {
                        aborts++
                    }
                }
            assertFailsWith<IllegalStateException> { SpaceObjectUploads(uploads).discard(ticket, writer, storage) }
            assertEquals(0, aborts)
            assertEquals("WRITING", phase(ticket))
        }

    private class Fixture(
        val source: DataSource,
        val owner: Account,
    ) {
        val id = owner.id.toJavaUUID()
        val space = SpaceRef.personal(requireNotNull(owner.did)).uri
        val accounts = PostgreSQLAccountRepository()
        val storage = InMemoryLogDateBlobStorage()
        val uploads = PostgreSQLObjectUploads()
        val store = PostgreSQLSpaceStore()
        private val adoption = PostgreSQLIdentityAdoptionRepository(source)

        fun rawBlob(bytes: ByteArray = byteArrayOf(1)): SpaceBlob {
            val request =
                LogDateBlobWriteRequest(
                    id,
                    LogDateBlobNamespace.SPACE,
                    Uuid.random().toString(),
                    contentType = "application/octet-stream",
                    bytes = bytes,
                )
            val path = storage.putBlob(request)
            return SpaceBlob(Cid.rawSha256(bytes).value, request.contentType, bytes.size.toLong(), path)
        }

        fun candidate(bytes: ByteArray = byteArrayOf(1)): Pair<ObjectUpload, SpaceBlob> {
            val request =
                LogDateBlobWriteRequest(
                    id,
                    LogDateBlobNamespace.SPACE,
                    Uuid.random().toString(),
                    contentType = "application/octet-stream",
                    bytes = bytes,
                )
            val ticket =
                uploads.begin(
                    IdentityWriteExpectation(owner.id, 0),
                    null,
                    storage.plannedPath(request),
                    ObjectUploadKind.SPACE,
                    space,
                )
            val path = storage.putBlob(request)
            uploads.stored(ticket)
            return ticket to SpaceBlob(Cid.rawSha256(bytes).value, request.contentType, bytes.size.toLong(), path)
        }

        suspend fun reverseHold() {
            val now = Clock.System.now()
            assertTrue(
                PostgreSQLSpaceMigrationStore().replace(
                    null,
                    SpaceMigration(
                        id,
                        "synthetic-move",
                        SpaceMigrationPhase.SWITCHED,
                        now,
                        now,
                        holdStartedAt = now,
                        switchedAt = now,
                    ),
                ),
            )
        }

        suspend fun begin() =
            assertIs<IdentityAdoptionOutcome.Accepted<IdentityAdoptionOperation>>(
                adoption.begin(
                    IdentityAdoptionBinding(owner.id, requireNotNull(owner.did), "did:web:target.test", "device", 0),
                    Clock.System.now(),
                ),
            ).value

        suspend fun hold(operation: IdentityAdoptionOperation) =
            adoption.transition(
                owner.id,
                operation.id,
                operation.revision,
                IdentityAdoptionPhase.FENCED,
                Clock.System.now(),
            )

        suspend fun phase(ticket: ObjectUpload): String =
            source.blueskyTransaction {
                prepareStatement("SELECT phase FROM object_upload_intents WHERE id = ?").use {
                    it.setString(1, ticket.id)
                    it.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        rows.getString(1)
                    }
                }
            }
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        withBlueskyOAuthDatabase { source ->
            val id = Uuid.random()
            val owner =
                Account(
                    id,
                    id.toHexString(),
                    "Synthetic space owner",
                    did = "did:plc:aaaaaaaaaaaaaaaaaaaaaaaa",
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
            Fixture(source, owner).block()
        }
}
