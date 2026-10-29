package app.logdate.server.identity

import app.logdate.server.atproto.HostedRepoCommitSigner
import app.logdate.server.atproto.firehose.AccountPublicRepoGate
import app.logdate.server.atproto.firehose.InMemoryRepoEventStore
import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.Cid
import studio.hypertext.atproto.repo.RepoCommit
import studio.hypertext.atproto.repo.RepoSigningKeyUnavailableException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class ExternalIdentityLifecycleTest {
    @Test
    fun `external and unknown identities never enter hosted provisioning or discovery`() =
        runTest {
            for (kind in listOf(AccountIdentityKind.EXTERNAL, AccountIdentityKind.UNRECOGNIZED)) {
                val account = account().copy(identityKind = kind, signingKeyPublic = "zStale", publicIdentityDid = DID)
                val repository =
                    object : AccountRepository by InMemoryAccountRepository() {
                        override suspend fun findById(id: Uuid): Account = account

                        override suspend fun findByDid(did: String): Account = account

                        override suspend fun findByHandle(handle: String): Account = account
                    }
                val keys = SigningKeyService(InMemorySigningKeyRepository(), "external-lifecycle-test")
                val service = AtprotoIdentityService(repository, keys, AtprotoIdentityConfig())
                assertFailsWith<IdentityLifecycleConflictException> { service.ensureIdentity(account) }
                assertNull(keys.findActiveKey(account.id))
                assertNull(service.findByDid(DID))
                assertNull(service.findByHandle(requireNotNull(account.handle)))
                assertNull(service.resolveHandle(requireNotNull(account.handle)).getOrThrow())
                assertNull(service.didDocument(AtprotoDid.require(DID)).getOrThrow())
                assertFailsWith<IdentityLifecycleConflictException> { service.documentFor(account) }
                val gate = AccountPublicRepoGate(repository, AtprotoIdentityConfig(), InMemoryRepoEventStore())
                assertFalse(gate.mayShow(AtprotoDid.require(DID)))
                assertNull(gate.standing(AtprotoDid.require(DID)))
            }
        }

    @Test
    fun `stale hosted snapshot is checked against the stored external kind before provisioning`() =
        runTest {
            val repository = InMemoryAccountRepository()
            val account = account()
            repository.create(account)
            val keys = SigningKeyService(InMemorySigningKeyRepository(), "external-lifecycle-test")
            val service = AtprotoIdentityService(repository, keys, AtprotoIdentityConfig())
            assertFailsWith<IdentityLifecycleConflictException> {
                service.ensureIdentity(account.copy(identityKind = AccountIdentityKind.HOSTED, did = null, handle = null))
            }
            assertFailsWith<IdentityLifecycleConflictException> {
                service.ensureIdentity(account.copy(identityKind = AccountIdentityKind.HOSTED, signingKeyPublic = "zStale"))
            }
            assertEquals(account, repository.findById(account.id))
            assertNull(keys.findActiveKey(account.id))
        }

    @Test
    fun `external repository cannot be signed with a leftover hosted key`() =
        runTest {
            val repository = InMemoryAccountRepository()
            val account = account()
            repository.create(account)
            val keys = SigningKeyService(InMemorySigningKeyRepository(), "external-lifecycle-test")
            keys.ensureActiveKey(account.id)
            val signer = HostedRepoCommitSigner(repository, keys)
            val commit =
                RepoCommit(
                    repo = AtprotoDid.require(DID),
                    root = Cid("bafyreifake"),
                    revision = 1,
                    createdAtEpochMillis = 0,
                    recordCount = 0,
                )
            assertFailsWith<RepoSigningKeyUnavailableException> { signer.sign(commit, byteArrayOf(1)) }
            assertFailsWith<RepoSigningKeyUnavailableException> { signer.verify(commit, byteArrayOf(1), "invalid") }
        }

    private fun account() =
        Account(
            id = Uuid.random(),
            username = "external",
            displayName = "External",
            createdAt = Clock.System.now(),
            did = DID,
            handle = "external.example.com",
            identityKind = AccountIdentityKind.EXTERNAL,
            storageMode = AccountStorageMode.PERSONAL_SPACE_V1,
        )

    companion object {
        private const val DID = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
    }
}
