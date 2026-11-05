package app.logdate.server.atproto

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.AtprotoIdentityConfig
import app.logdate.server.identity.AtprotoIdentityService
import app.logdate.server.identity.InMemorySigningKeyRepository
import app.logdate.server.identity.SigningKeyService
import app.logdate.server.identity.adoption.withAccountWriteOrigin
import app.logdate.server.logdate.CompositeLogDateMediaBlobRepository
import app.logdate.server.logdate.InMemoryLogDateAtprotoBlobRepository
import app.logdate.server.logdate.InMemoryLogDateBlobStorage
import app.logdate.server.logdate.InMemoryLogDateMediaRepository
import kotlinx.coroutines.runBlocking
import studio.hypertext.atproto.pds.GetBlobRequest
import studio.hypertext.atproto.pds.UploadBlobRequest
import studio.hypertext.atproto.repo.Cid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Verifies the functionality of the Personal Data Server (PDS) blob storage implementation.
 *
 * These tests ensure that binary data can be correctly uploaded to and retrieved from
 * the blob store using AT Protocol identifiers (DID and CID). It validates the integration
 * between the identity service, media repositories, and the underlying blob storage.
 */
@OptIn(ExperimentalUuidApi::class)
class LogDatePdsBlobStoreTest {
    @Test
    fun `blob store uploads and downloads blobs by repo did and cid`() =
        runBlocking {
            val accountRepository = InMemoryAccountRepository()
            val identityService =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "blob-test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                identityService.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "alice",
                            displayName = "Alice",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )
            val store =
                LogDatePdsBlobStore(
                    identityService = identityService,
                    mediaBlobRepository =
                        CompositeLogDateMediaBlobRepository(
                            mediaRepository = InMemoryLogDateMediaRepository(),
                            atprotoBlobRepository = InMemoryLogDateAtprotoBlobRepository(),
                        ),
                    blobStorage = InMemoryLogDateBlobStorage(),
                )

            val uploaded =
                withAccountWriteOrigin(account.id, account.identityEpoch) {
                    store
                        .putBlob(
                            UploadBlobRequest(
                                repo =
                                    studio.hypertext.atproto.identity.AtprotoDid.require(
                                        requireNotNull(account.did),
                                    ),
                                contentType = "image/jpeg",
                                bytes = byteArrayOf(1, 2, 3),
                            ),
                        ).getOrThrow()
                }
            val downloaded =
                store
                    .getBlob(
                        GetBlobRequest(
                            did =
                                studio.hypertext.atproto.identity.AtprotoDid.require(
                                    requireNotNull(account.did),
                                ),
                            cid = uploaded.ref.cid,
                        ),
                    ).getOrThrow()
            val missing =
                store
                    .getBlob(
                        GetBlobRequest(
                            did =
                                studio.hypertext.atproto.identity.AtprotoDid.require(
                                    requireNotNull(account.did),
                                ),
                            cid = Cid.rawSha256(byteArrayOf(9, 9, 9)),
                        ),
                    ).getOrThrow()

            assertEquals("blob", uploaded.type)
            assertEquals("image/jpeg", uploaded.mimeType)
            assertEquals(3L, uploaded.size)
            assertNotNull(downloaded)
            assertEquals("image/jpeg", downloaded.contentType)
            assertContentEquals(byteArrayOf(1, 2, 3), downloaded.bytes)
            assertNull(missing)
        }
}
