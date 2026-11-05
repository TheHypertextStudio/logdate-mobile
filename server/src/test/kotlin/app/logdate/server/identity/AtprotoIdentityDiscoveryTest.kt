package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.identity.AtprotoIdentityTestSupport.FullScanForbiddenAccountRepository
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class, ExperimentalEncodingApi::class)
class AtprotoIdentityDiscoveryTest {
    @Test
    fun `findByHandle resolves provisioned handle`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "bob",
                        displayName = "Bob",
                        createdAt = Clock.System.now(),
                    ),
                )
            service.ensureIdentity(account)

            val resolved = service.findByHandle("bob.logdate.app")

            assertTrue(resolved?.did?.startsWith("did:plc:") == true)
        }

    @Test
    fun `findByDid resolves from did web handle`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "carol",
                        displayName = "Carol",
                        createdAt = Clock.System.now(),
                    ),
                )
            service.ensureIdentity(account)

            val resolved =
                service.findByDid(
                    requireNotNull(accountRepository.findByUsername("carol")?.let { service.ensureIdentity(it).did }),
                )

            assertEquals("carol.logdate.app", resolved?.handle)
        }

    @Test
    fun `web mode provisions did web identities and resolves normalized did lookups`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://logdate.app",
                            hostedAccountDidMethod = HostedAccountDidMethod.WEB,
                        ),
                )
            val account =
                accountRepository.save(
                    Account(
                        id = Uuid.random(),
                        username = "web-user",
                        displayName = "Web User",
                        createdAt = Clock.System.now(),
                    ),
                )

            val ensured = service.ensureIdentity(account)
            val resolved = service.findByDid("  DID:WEB:WEB-USER.LOGDATE.APP  ")

            assertEquals("did:web:web-user.logdate.app", ensured.did)
            assertEquals("web-user.logdate.app", resolved?.handle)
        }

    @Test
    fun `documentFor builds atproto pds did document`() =
        kotlinx.coroutines.test.runTest {
            val accountRepository = InMemoryAccountRepository()
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://pds.logdate.app"),
                )
            val ensured =
                service.ensureIdentity(
                    accountRepository.save(
                        Account(
                            id = Uuid.random(),
                            username = "dana",
                            displayName = "Dana",
                            createdAt = Clock.System.now(),
                        ),
                    ),
                )

            val document = service.documentFor(ensured)

            assertTrue(document.id.toString().startsWith("did:plc:"))
            assertEquals(listOf("at://dana.logdate.app"), document.alsoKnownAs)
            assertEquals("https://pds.logdate.app", document.service.single().serviceEndpoint)
            assertEquals("${document.id}#atproto", document.verificationMethod.single().id)
        }

    @Test
    fun `document helpers require complete identity and expose server did document`() =
        kotlinx.coroutines.test.runTest {
            val service =
                AtprotoIdentityService(
                    accountRepository = InMemoryAccountRepository(),
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config =
                        AtprotoIdentityConfig(
                            handleDomain = "logdate.app",
                            pdsServiceEndpoint = "https://pds.logdate.app",
                        ),
                )

            assertEquals("profile-user.logdate.app", service.defaultHandleFor("profile_user"))
            assertEquals("user.logdate.app", service.defaultHandleFor("!!!"))
            assertEquals("user.logdate.app", service.defaultHandleFor("staging"))
            assertEquals("user.logdate.app", service.defaultHandleFor("Staging"))

            val serverDocument = service.serverDocument()
            assertEquals("did:web:logdate.app", serverDocument.id.toString())
            assertTrue(serverDocument.verificationMethod.isEmpty())
            assertEquals("https://pds.logdate.app", serverDocument.service.single().serviceEndpoint)

            assertFailsWith<IllegalArgumentException> {
                service.documentFor(
                    Account(
                        id = Uuid.random(),
                        username = "missing-did",
                        displayName = "Missing DID",
                        createdAt = Clock.System.now(),
                        handle = "missing.logdate.app",
                        signingKeyPublic = "zKey",
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                service.documentFor(
                    Account(
                        id = Uuid.random(),
                        username = "missing-handle",
                        displayName = "Missing Handle",
                        createdAt = Clock.System.now(),
                        did = "did:web:missing.logdate.app",
                        signingKeyPublic = "zKey",
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                service.documentFor(
                    Account(
                        id = Uuid.random(),
                        username = "missing-key",
                        displayName = "Missing Key",
                        createdAt = Clock.System.now(),
                        handle = "missing.logdate.app",
                        did = "did:web:missing.logdate.app",
                    ),
                )
            }
        }

    @Test
    fun `identity helper reflection covers did parsing fallback`() {
        val service =
            AtprotoIdentityService(
                accountRepository = InMemoryAccountRepository(),
                signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
            )
        val handleFromDid =
            service::class.java
                .getDeclaredMethod("handleFromDid", String::class.java)
                .apply { isAccessible = true }

        assertEquals("alice.logdate.app", handleFromDid.invoke(service, "did:web:alice.logdate.app"))
        assertNull(handleFromDid.invoke(service, "did:plc:alice123"))
    }

    @Test
    fun `resolving an unknown handle never loads every account`() =
        kotlinx.coroutines.test.runTest {
            val delegate = InMemoryAccountRepository()
            val accountRepository = FullScanForbiddenAccountRepository(delegate)
            val service =
                AtprotoIdentityService(
                    accountRepository = accountRepository,
                    signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
                    config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
                )
            service.ensureIdentity(
                delegate.save(Account(id = Uuid.random(), username = "provisioned", displayName = "P", createdAt = Clock.System.now())),
            )
            val legacy =
                delegate.save(Account(id = Uuid.random(), username = "legacy", displayName = "L", createdAt = Clock.System.now()))
            accountRepository.lookups = 0

            assertNull(service.findByHandle("unknown.logdate.app"))
            assertEquals(1, accountRepository.lookups)
            assertTrue(service.findByHandle("provisioned.logdate.app")?.did?.startsWith("did:plc:") == true)
            // An account without an identity is not resolvable, and looking it up never creates one.
            assertNull(service.findByHandle("legacy.logdate.app"))
            assertEquals(legacy, delegate.findById(legacy.id))
        }
}
