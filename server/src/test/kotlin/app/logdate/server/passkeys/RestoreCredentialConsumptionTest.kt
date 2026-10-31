package app.logdate.server.passkeys

import kotlinx.coroutines.runBlocking
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
class RestoreCredentialConsumptionTest {
    @Test
    fun `two distinct signed zero-counter challenges both authenticate`() =
        runBlocking<Unit> {
            val backing = InMemoryRestoreCredentialRepository()
            val barrier = CyclicBarrier(2)
            val repository =
                object : RestoreCredentialRepository by backing {
                    override suspend fun findByCredentialId(credentialId: String): StoredRestoreCredentialData? {
                        val stored = backing.findByCredentialId(credentialId)
                        barrier.await(5, TimeUnit.SECONDS)
                        return stored
                    }
                }
            val fixture = registered(repository)
            val challenges = List(2) { fixture.service.generateAuthOptions().challenge }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val results =
                    challenges
                        .map { challenge ->
                            executor.submit<Boolean> {
                                runBlocking {
                                    fixture.service
                                        .verifyAuthentication(
                                            challenge,
                                            fixture.authenticator.assertion(challenge),
                                        ).success
                                }
                            }
                        }.map { it.get(10, TimeUnit.SECONDS) }
                assertEquals(2, results.count { it })
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun `revocation after lookup prevents a valid signed assertion from authenticating`() =
        runBlocking<Unit> {
            val backing = InMemoryRestoreCredentialRepository()
            val repository =
                object : RestoreCredentialRepository by backing {
                    override suspend fun findByCredentialId(credentialId: String): StoredRestoreCredentialData? {
                        val stored = backing.findByCredentialId(credentialId)
                        if (stored != null) backing.deactivateAllForUser(stored.userId)
                        return stored
                    }
                }
            val fixture = registered(repository)
            val challenge = fixture.service.generateAuthOptions().challenge
            assertFalse(fixture.service.verifyAuthentication(challenge, fixture.authenticator.assertion(challenge)).success)
        }

    @Test
    fun `legacy padded ID retains the actual verified stored record`() =
        runBlocking {
            val backing = InMemoryRestoreCredentialRepository()
            val fixture = registered(backing)
            val stored = assertNotNull(backing.findByCredentialId(fixture.credentialId))
            backing.deactivate(stored.credentialId)
            val padded = stored.credentialId + "="
            backing.store(stored.userId, padded, stored.publicKey, stored.signCount)
            val challenge = fixture.service.generateAuthOptions().challenge
            val assertion = fixture.authenticator.assertion(challenge).copy(id = padded, rawId = padded)
            assertTrue(fixture.service.verifyAuthentication(challenge, assertion).success)
            assertNotNull(backing.findByCredentialId(padded))
            Unit
        }

    @Test
    fun `fresh challenge recovers after lost response and server restart`() =
        runBlocking<Unit> {
            val repository = InMemoryRestoreCredentialRepository()
            val fixture = registered(repository)
            val first = fixture.service.generateAuthOptions().challenge
            assertTrue(fixture.service.verifyAuthentication(first, fixture.authenticator.assertion(first)).success)
            val restarted = RestoreCredentialService(repository, origins = setOf("https://logdate.app"), strictVerificationEnabled = true)
            val retry = restarted.generateAuthOptions().challenge
            assertTrue(restarted.verifyAuthentication(retry, fixture.authenticator.assertion(retry)).success)
            assertFalse(restarted.verifyAuthentication(retry, fixture.authenticator.assertion(retry)).success)
        }

    @Test
    fun `registration on another installation preserves existing recovery key`() =
        runBlocking {
            val repository = InMemoryRestoreCredentialRepository()
            val fixture = registered(repository)
            val old = assertNotNull(repository.findByCredentialId(fixture.credentialId))
            val other = PassiveRestoreAuthenticator(42)
            val options = fixture.service.generateRegistrationOptions(old.userId, "fixture", "Fixture")
            assertTrue(fixture.service.verifyRegistration(old.userId, options.challenge, other.registration(options)).success)
            val challenge = fixture.service.generateAuthOptions().challenge
            assertTrue(fixture.service.verifyAuthentication(challenge, fixture.authenticator.assertion(challenge)).success)
            assertEquals(2, repository.getCredentialIdsForUser(old.userId).size)
        }

    @Test
    fun `failed registration preserves existing recovery key and reports failure`() =
        runBlocking {
            val repository = InMemoryRestoreCredentialRepository()
            val fixture = registered(repository)
            val old = assertNotNull(repository.findByCredentialId(fixture.credentialId))
            val duplicate = fixture.service.generateRegistrationOptions(old.userId, "fixture", "Fixture")
            assertFalse(
                fixture.service.verifyRegistration(old.userId, duplicate.challenge, fixture.authenticator.registration(duplicate)).success,
            )
            assertNotNull(repository.findByCredentialId(old.credentialId))
            Unit
        }

    private suspend fun registered(repository: RestoreCredentialRepository): Fixture {
        val service = RestoreCredentialService(repository, origins = setOf("https://logdate.app"), strictVerificationEnabled = true)
        val authenticator = PassiveRestoreAuthenticator()
        val owner = Uuid.random()
        val options = service.generateRegistrationOptions(owner, "fixture", "Fixture")
        val credential = authenticator.registration(options)
        assertTrue(service.verifyRegistration(owner, options.challenge, credential).success)
        return Fixture(service, authenticator, credential.id)
    }

    private data class Fixture(
        val service: RestoreCredentialService,
        val authenticator: PassiveRestoreAuthenticator,
        val credentialId: String,
    )
}
