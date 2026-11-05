package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.crypto.EcCurve
import studio.hypertext.atproto.crypto.EcKeySupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class SigningKeyImportValidationTest {
    @Test fun samePublicKeyClaimCannotReplacePrivateMaterialWithAnotherKey() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val repository = InMemorySigningKeyRepository()
            val keys = SigningKeyService(repository, KEK)
            val history = InMemoryHostedPlcOperationRepository()
            val config = AtprotoIdentityConfig()
            val identity =
                AtprotoIdentityService(
                    accounts,
                    keys,
                    config,
                    PlcIdentityService(
                        keys,
                        config,
                        hostedPlcOperationRepository = history,
                    ),
                )
            val id = Uuid.random()
            val owner = identity.ensureIdentity(accounts.save(Account(id, id.toHexString(), "Owner", createdAt = Clock.System.now())))
            val original = assertNotNull(repository.findActiveByAccountId(id))
            val originalHistory = history.listByAccountId(id)
            val donor = Uuid.random()
            val exported =
                keys.exportActiveKey(donor, PASSPHRASE).copy(
                    publicKeyMultibase = original.publicKeyMultibase,
                    publicKeyDidKey = didKeyFor(original.publicKeyMultibase),
                )
            assertFailsWith<IdentityLifecycleValidationException> { identity.importSigningKey(owner, exported, PASSPHRASE) }
            assertEquals(original, repository.findActiveByAccountId(id))
            assertEquals(owner, accounts.findById(id))
            assertEquals(originalHistory, history.listByAccountId(id))
        }

    @Test fun declaredCurveMustMatchTheImportedKey() =
        runTest {
            val repository = InMemorySigningKeyRepository()
            val keys = SigningKeyService(repository, KEK)
            val id = Uuid.random()
            val original = keys.ensureActiveKey(id)
            val exported = keys.exportActiveKey(id, PASSPHRASE).copy(algorithm = EcCurve.P256.signingKeyAlgorithm)
            assertFailsWith<IdentityLifecycleValidationException> { keys.importActiveKey(id, exported, PASSPHRASE) }
            assertEquals(original, repository.findActiveByAccountId(id))
        }

    @Test fun malformedPublicKeyCannotBePrepared() =
        runTest {
            val repository = InMemorySigningKeyRepository()
            val keys = SigningKeyService(repository, KEK)
            val id = Uuid.random()
            val original = keys.ensureActiveKey(id)
            val exported =
                keys.exportActiveKey(id, PASSPHRASE).copy(
                    publicKeyMultibase = "zNotAnEcPublicKey",
                    publicKeyDidKey = "did:key:zNotAnEcPublicKey",
                )
            assertFailsWith<IdentityLifecycleValidationException> { keys.prepareImportedKey(id, exported, PASSPHRASE) }
            assertEquals(original, repository.findActiveByAccountId(id))
        }

    @Test fun validKeyPairsOnBothSupportedCurvesStillImportAndSign() =
        runTest {
            for (curve in EcCurve.entries) {
                val repository = InMemorySigningKeyRepository()
                val keys = SigningKeyService(repository, KEK)
                val donor = Uuid.random()
                val generated = keys.generateKeyPair(curve)
                repository.save(
                    StoredSigningKey(
                        Uuid.random(),
                        donor,
                        algorithm = generated.algorithm,
                        publicKeyMultibase = generated.publicKeyMultibase,
                        privateKeyEncrypted = AtRestKeyCipher(KEK).encrypt(generated.privateKeyPkcs8),
                        createdAt = Clock.System.now(),
                    ),
                )
                val exported = keys.exportActiveKey(donor, PASSPHRASE)
                val recipient = Uuid.random()
                val imported = keys.importActiveKey(recipient, exported, PASSPHRASE)
                val challenge = "independent-test-payload".encodeToByteArray()
                val signature = EcKeySupport.signSha256(keys.decryptPrivateKey(imported), curve, challenge)
                val publicKey = EcKeySupport.decodePublicKey(imported.publicKeyMultibase)
                assertEquals(curve, publicKey.curve)
                assertTrue(EcKeySupport.verifySha256(publicKey.publicKey, curve, challenge, signature))
                assertEquals(imported, repository.findActiveByAccountId(recipient))
            }
        }

    private companion object {
        const val KEK = "synthetic-import-validation-kek"
        const val PASSPHRASE = "synthetic-export-passphrase"
    }
}
