package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.AccountRepository
import app.logdate.server.auth.InMemoryAccountRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.identity.DidDocument
import studio.hypertext.atproto.plc.PlcDirectoryClient
import studio.hypertext.atproto.plc.PlcIndexedOperation
import studio.hypertext.atproto.plc.PlcLogEntry
import studio.hypertext.atproto.plc.PlcOperation
import java.math.BigInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The safeguards around recovery keys: concurrent requests on one server and across server
 * instances never fork the operation log, a request that fails part-way can be repeated, the log
 * only takes operations the PLC directory would accept, and nothing reaches the directory while
 * publishing is off.
 */
@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class RecoveryKeySafeguardsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `two parallel registrations on one server leave one key on the account and in the log`() =
        runTest {
            val world = World()
            val account = world.newAccount()
            val keys = List(2) { world.newRecoveryKey() }

            val outcomes =
                keys
                    .map { key -> async { runCatching { world.identity.registerPlcRecoveryKey(account, key.didKey) } } }
                    .awaitAll()

            assertEquals(1, outcomes.count { it.isSuccess })
            val refused = outcomes.single { it.isFailure }.exceptionOrNull() as IdentityLifecycleConflictException
            assertEquals(AtprotoIdentityService.RECOVERY_KEY_ALREADY_SET, refused.code)
            world.assertConsistent(account, expectedOperations = 2)
        }

    @Test
    fun `two parallel registrations on two server instances cannot fork the log`() =
        runTest {
            val world = World()
            val account = world.newAccount()
            val gate = world.holdAppendsUntil(arrivals = 2)
            val instances = listOf(world.newInstance(), world.newInstance())
            val keys = List(2) { world.newRecoveryKey() }

            val outcomes =
                instances
                    .zip(keys)
                    .map { (instance, key) -> async { runCatching { instance.registerPlcRecoveryKey(account, key.didKey) } } }
                    .awaitAll()

            assertTrue(gate.isCompleted)
            assertEquals(1, outcomes.count { it.isSuccess })
            val refused = outcomes.single { it.isFailure }.exceptionOrNull() as IdentityLifecycleConflictException
            assertEquals(PlcIdentityService.IDENTITY_CHANGED, refused.code)
            world.assertConsistent(account, expectedOperations = 2)
        }

    @Test
    fun `two parallel recoveries on one server install one server key`() =
        runTest {
            val world = World()
            val key = world.newRecoveryKey()
            val account = world.registeredAccount(key)
            val prepared = List(2) { world.identity.prepareRecovery(account) }

            val outcomes =
                prepared
                    .map { recovery ->
                        async {
                            runCatching {
                                world.identity.completeRecovery(account, recovery.preparedKey, key.sign(recovery.signingPayloadBase64Url))
                            }
                        }
                    }.awaitAll()

            assertEquals(1, outcomes.count { it.isSuccess })
            val refused = outcomes.single { it.isFailure }.exceptionOrNull() as IdentityLifecycleConflictException
            assertEquals(PlcIdentityService.IDENTITY_CHANGED, refused.code)
            world.assertConsistent(account, expectedOperations = 3)
        }

    @Test
    fun `two parallel recoveries on two server instances install one server key`() =
        runTest {
            val world = World()
            val key = world.newRecoveryKey()
            val account = world.registeredAccount(key)
            val prepared = List(2) { world.identity.prepareRecovery(account) }
            val gate = world.holdAppendsUntil(arrivals = 2)
            val instances = listOf(world.newInstance(), world.newInstance())

            val outcomes =
                instances
                    .zip(prepared)
                    .map { (instance, recovery) ->
                        async {
                            runCatching {
                                instance.completeRecovery(
                                    account,
                                    recovery.preparedKey,
                                    key.sign(recovery.signingPayloadBase64Url),
                                )
                            }
                        }
                    }.awaitAll()

            assertTrue(gate.isCompleted)
            assertEquals(1, outcomes.count { it.isSuccess })
            val refused = outcomes.single { it.isFailure }.exceptionOrNull() as IdentityLifecycleConflictException
            assertEquals(PlcIdentityService.IDENTITY_CHANGED, refused.code)
            world.assertConsistent(account, expectedOperations = 3)
        }

    @Test
    fun `a recovery that failed after appending finishes when the same request is repeated`() =
        runTest {
            val world = World()
            val key = world.newRecoveryKey()
            val account = world.registeredAccount(key)
            val prepared = world.identity.prepareRecovery(account)
            val signature = key.sign(prepared.signingPayloadBase64Url)
            world.signingKeyRepository.failNextActivation = true

            assertFailsWith<IllegalStateException> { world.identity.completeRecovery(account, prepared.preparedKey, signature) }
            assertEquals(3, world.operations(account).size)
            val recovered = world.identity.completeRecovery(account, prepared.preparedKey, signature)

            assertEquals(prepared.nextSigningKeyDidKey, didKeyFor(recovered.activeKey.publicKeyMultibase))
            world.assertConsistent(account, expectedOperations = 3)
        }

    @Test
    fun `a registration that failed after appending finishes when repeated without appending again`() =
        runTest {
            val world = World()
            val account = world.newAccount()
            val key = world.newRecoveryKey()
            world.accountRepository.failNextIdentityWrite = true

            assertFailsWith<IllegalStateException> { world.identity.registerPlcRecoveryKey(account, key.didKey) }
            val registered = world.identity.registerPlcRecoveryKey(account, key.didKey)

            assertNull(registered.plcOperation)
            assertEquals(key.didKey, registered.account.plcRecoveryDidKey)
            world.assertConsistent(account, expectedOperations = 2)
        }

    @Test
    fun `a change for an account deleted meanwhile does not bring it back`() =
        runTest {
            val world = World()
            val account = world.newAccount()
            world.accountRepository.deleteAccount(account.id)

            assertFailsWith<IdentityAccountMissingException> {
                world.identity.registerPlcRecoveryKey(
                    account,
                    world.newRecoveryKey().didKey,
                )
            }
            assertNull(world.accountRepository.findById(account.id))
        }

    @Test
    fun `a recovery prepared before the handle changed is refused as identity changed`() =
        runTest {
            val world = World()
            val key = world.newRecoveryKey()
            val account = world.registeredAccount(key)
            val prepared = world.identity.prepareRecovery(account)
            val stored = world.accountRepository.findById(account.id)!!
            assertNotNull(
                world.accountRepository.updateIdentityIfUnchanged(stored, stored.copy(handle = "renamed.logdate.app")),
            )

            val refused =
                assertFailsWith<IdentityLifecycleConflictException> {
                    world.identity.completeRecovery(account, prepared.preparedKey, key.sign(prepared.signingPayloadBase64Url))
                }

            assertEquals(PlcIdentityService.IDENTITY_CHANGED, refused.code)
            assertEquals(2, world.operations(account).size)
        }

    @Test
    fun `a high s signature is refused even though it verifies`() =
        runTest {
            val world = World()
            val key = world.newRecoveryKey()
            val account = world.registeredAccount(key)
            val prepared = world.identity.prepareRecovery(account)
            val highS = key.highS(key.sign(prepared.signingPayloadBase64Url))

            val refused =
                assertFailsWith<IdentityLifecycleValidationException> {
                    world.identity.completeRecovery(account, prepared.preparedKey, highS)
                }

            assertEquals(PlcIdentityService.RECOVERY_SIGNATURE_INVALID, refused.code)
            assertEquals(2, world.operations(account).size)
        }

    @Test
    fun `the server does not sign with a key the identity does not list`() =
        runTest {
            val world = World()
            val account = world.registeredAccount(world.newRecoveryKey())
            world.signingKeys.rotateKey(account.id)

            val refused =
                assertFailsWith<IdentityLifecycleConflictException> {
                    world.identity.replacePlcRecoveryKey(account, world.newRecoveryKey().didKey)
                }

            assertEquals(PlcIdentityService.SERVER_KEY_NOT_ON_IDENTITY, refused.code)
            assertEquals(2, world.operations(account).size)
        }

    @Test
    fun `a lost recovery key is replaced and the log follows its chain`() =
        runTest {
            val world = World()
            val account = world.registeredAccount(world.newRecoveryKey())
            val replacement = world.newRecoveryKey()

            val replaced = world.identity.replacePlcRecoveryKey(account, replacement.didKey)

            assertEquals(replacement.didKey, replaced.account.plcRecoveryDidKey)
            assertEquals(
                replacement.didKey,
                world
                    .operations(account)
                    .last()
                    .rotationKeys
                    .first(),
            )
            world.assertConsistent(account, expectedOperations = 3)
        }

    @Test
    fun `nothing reaches the PLC directory while publishing is off`() =
        runTest {
            val directory = RecordingPlcDirectoryClient()
            val world = World(directory = directory)
            val key = world.newRecoveryKey()
            val account = world.registeredAccount(key)
            world.identity.replacePlcRecoveryKey(account, world.newRecoveryKey().didKey)
            val replacedKey = world.newRecoveryKey()
            world.identity.replacePlcRecoveryKey(account, replacedKey.didKey)
            val prepared = world.identity.prepareRecovery(account)
            world.identity.completeRecovery(account, prepared.preparedKey, replacedKey.sign(prepared.signingPayloadBase64Url))

            assertEquals(5, world.operations(account).size)
            assertEquals(0, directory.submits)
            assertEquals(0, directory.reads)
        }

    private class RecoveryKey(
        signingKeys: SigningKeyService,
    ) {
        private val pair = signingKeys.generateKeyPair(EcCurve.P256)
        val didKey = didKeyFor(pair.publicKeyMultibase)

        fun sign(payloadBase64Url: String): String =
            encode(EcKeySupport.signSha256(EcKeySupport.decodePrivateKey(pair.privateKeyPkcs8), EcCurve.P256, decode(payloadBase64Url)))

        /** The same signature with `s` replaced by `n - s`: it still verifies, but the directory refuses it. */
        fun highS(signature: String): String {
            val bytes = decode(signature)
            val order = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
            val s = BigInteger(1, bytes.copyOfRange(32, 64))
            val high =
                order
                    .subtract(s)
                    .toByteArray()
                    .takeLast(32)
                    .toByteArray()
            return encode(bytes.copyOfRange(0, 32) + ByteArray(32 - high.size) + high)
        }

        private fun encode(bytes: ByteArray) = Base64.UrlSafe.encode(bytes).trimEnd('=')

        private fun decode(value: String) = Base64.UrlSafe.decode(value + "=".repeat((4 - value.length % 4) % 4))
    }

    private inner class World(
        private val directory: PlcDirectoryClient? = null,
    ) {
        val accountRepository = FlakyAccountRepository()
        val signingKeyRepository = FlakySigningKeyRepository()
        val signingKeys = SigningKeyService(signingKeyRepository, "test-kek")
        val operationRepository = GatedOperationRepository()
        private val config =
            AtprotoIdentityConfig(
                handleDomain = "logdate.app",
                pdsServiceEndpoint = "https://logdate.app",
                hostedAccountDidMethod = HostedAccountDidMethod.PLC,
                recoveryKeysEnabled = true,
            )
        val identity = newInstance()

        /** Another server instance: its own locks, the same database. */
        fun newInstance(): AtprotoIdentityService =
            AtprotoIdentityService(
                accountRepository = accountRepository,
                signingKeyService = signingKeys,
                config = config,
                plcIdentityService =
                    PlcIdentityService(
                        signingKeyService = signingKeys,
                        config = config,
                        plcDirectoryClient = directory,
                        hostedPlcOperationRepository = operationRepository,
                    ),
            )

        fun newRecoveryKey() = RecoveryKey(signingKeys)

        suspend fun newAccount(): Account =
            identity.ensureIdentity(
                accountRepository.save(
                    Account(id = Uuid.random(), username = "alice", displayName = "Alice", createdAt = Clock.System.now()),
                ),
            )

        suspend fun registeredAccount(key: RecoveryKey): Account = identity.registerPlcRecoveryKey(newAccount(), key.didKey).account

        /** Holds every non-genesis append until [arrivals] of them are waiting, so they all read the same head first. */
        fun holdAppendsUntil(arrivals: Int): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { gate ->
                operationRepository.gate = gate
                operationRepository.expectedArrivals = arrivals
            }

        suspend fun operations(account: Account): List<PlcOperation> =
            operationRepository
                .listByDid(requireNotNull(accountRepository.findById(account.id)?.did))
                .map { json.decodeFromString(PlcOperation.serializer(), it.operationJson) }

        /** The log is one chain, and the account and the active key agree with its head. */
        suspend fun assertConsistent(
            account: Account,
            expectedOperations: Int,
        ) {
            val stored = requireNotNull(accountRepository.findById(account.id))
            val rows = operationRepository.listByDid(requireNotNull(stored.did))
            assertEquals(expectedOperations, rows.size)
            assertEquals(rows.size, rows.map { it.prevCid }.toSet().size, "two operations follow the same one")
            val head =
                PlcIdentityService(
                    signingKeys,
                    config,
                    hostedPlcOperationRepository = operationRepository,
                ).head(AtprotoDid.require(stored.did))
            assertEquals(stored.plcRecoveryDidKey, head.operation.rotationKeys.first())
            assertEquals(didKeyFor(stored.signingKeyPublic!!), head.operation.verificationMethods.getValue("atproto"))
            assertEquals(stored.signingKeyPublic, signingKeys.ensureActiveKey(account.id).publicKeyMultibase)
        }
    }

    private class GatedOperationRepository(
        private val delegate: InMemoryHostedPlcOperationRepository = InMemoryHostedPlcOperationRepository(),
    ) : HostedPlcOperationRepository by delegate {
        var gate: CompletableDeferred<Unit>? = null
        var expectedArrivals: Int = 0
        private var arrivals = 0

        override suspend fun save(operation: StoredHostedPlcOperation): StoredHostedPlcOperation {
            val gate = gate
            if (gate != null && operation.prevCid != null) {
                arrivals++
                if (arrivals >= expectedArrivals) gate.complete(Unit)
                gate.await()
            }
            return delegate.save(operation)
        }
    }

    private class FlakyAccountRepository(
        private val delegate: InMemoryAccountRepository = InMemoryAccountRepository(),
    ) : AccountRepository by delegate {
        var failNextIdentityWrite = false

        override suspend fun updateIdentityIfUnchanged(
            expected: Account,
            updated: Account,
        ): Account? {
            if (failNextIdentityWrite) {
                failNextIdentityWrite = false
                throw IllegalStateException("The database went away")
            }
            return delegate.updateIdentityIfUnchanged(expected, updated)
        }
    }

    private class FlakySigningKeyRepository(
        private val delegate: InMemorySigningKeyRepository = InMemorySigningKeyRepository(),
    ) : SigningKeyRepository by delegate {
        var failNextActivation = false

        override suspend fun replaceActiveKey(
            signingKey: StoredSigningKey,
            identityEpoch: Long,
        ): StoredSigningKey {
            if (failNextActivation) {
                failNextActivation = false
                throw IllegalStateException("The database went away")
            }
            return delegate.replaceActiveKey(signingKey, identityEpoch)
        }
    }

    private class RecordingPlcDirectoryClient : PlcDirectoryClient {
        var submits = 0
        var reads = 0

        override suspend fun getDocument(did: AtprotoDid): Result<DidDocument> {
            reads++
            return Result.failure(IllegalStateException("not published"))
        }

        override suspend fun getOperationLog(did: AtprotoDid): Result<List<PlcLogEntry>> {
            reads++
            return Result.success(emptyList())
        }

        override suspend fun getAuditLog(did: AtprotoDid): Result<List<PlcIndexedOperation>> {
            reads++
            return Result.success(emptyList())
        }

        override suspend fun export(
            after: String?,
            count: Int?,
        ): Result<List<PlcIndexedOperation>> {
            reads++
            return Result.success(emptyList())
        }

        override suspend fun submit(
            did: AtprotoDid,
            entry: PlcLogEntry,
        ): Result<Unit> {
            submits++
            return Result.success(Unit)
        }
    }
}
