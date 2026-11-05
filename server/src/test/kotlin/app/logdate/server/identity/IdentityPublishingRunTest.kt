package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.plc.PlcOperation
import studio.hypertext.atproto.plc.PlcOperations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityPublishingRunTest {
    private class Fixture {
        val accounts = InMemoryAccountRepository()
        val signingKeys = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
        val operations = InMemoryHostedPlcOperationRepository()
        val directory = FakePlcDirectory()
        var createdAt = Clock.System.now()

        fun config(
            publishing: Boolean,
            pdsServiceEndpoint: String = "https://pds.logdate.app",
        ) = AtprotoIdentityConfig(
            handleDomain = "logdate.app",
            pdsServiceEndpoint = pdsServiceEndpoint,
            publishHostedPlcOperations = publishing,
        )

        fun identityService(config: AtprotoIdentityConfig): AtprotoIdentityService =
            AtprotoIdentityService(accounts, signingKeys, config, plcIdentityService(config))

        fun plcIdentityService(config: AtprotoIdentityConfig) = PlcIdentityService(signingKeys, config, directory, operations)

        fun publishingService(config: AtprotoIdentityConfig) =
            IdentityPublishingService(identityService(config), plcIdentityService(config), operations, directory, config)

        /** Signs an account up the way the server does while publishing is off. */
        suspend fun signUp(
            username: String,
            provision: Boolean = true,
        ): Account {
            createdAt += 1.seconds
            val account = accounts.save(Account(id = Uuid.random(), username = username, displayName = username, createdAt = createdAt))
            return if (provision) identityService(config(publishing = false)).ensureIdentity(account) else account
        }

        fun TestScope.publishingRun(
            config: AtprotoIdentityConfig,
            onPublished: suspend (Uuid) -> Unit = {},
        ): IdentityPublishingRun =
            IdentityPublishingRun(
                service = publishingService(config),
                loadAccounts = accounts::getAllAccounts,
                pacer = PlcWritePacer(operationsPerMinute = 600, nowMillis = { testScheduler.currentTime }, sleepMillis = { delay(it) }),
                directoryUrl = config.normalizedPlcDirectoryUrl,
                onPublished = onPublished,
            )
    }

    private val status = IdentityPublishingOptions(publish = false)
    private val publish = IdentityPublishingOptions(publish = true)

    @Test
    fun `identities created while publishing was off are published once and then left alone`() =
        runTest {
            val fixture = Fixture()
            val alice = fixture.signUp("alice")
            val brie = fixture.signUp("brie")
            val on = fixture.config(publishing = true)

            val before = with(fixture) { publishingRun(on) }.run(status)
            assertEquals(IdentityPublishingRun.EXIT_WORK_REMAINS, before.exitCode)
            assertEquals(2, before.counts["unpublished"])

            val published = with(fixture) { publishingRun(on) }.run(publish)
            assertEquals(IdentityPublishingRun.EXIT_OK, published.exitCode)
            assertEquals(2, published.counts["publish_genesis"])
            assertEquals(2, published.counts["now_published"])
            listOf(alice, brie).forEach { account ->
                val stored = fixture.operations.listByDid(requireNotNull(account.did)).single()
                assertEquals(
                    fixture.directory
                        .log(stored.did)
                        .single()
                        .cid,
                    stored.cid,
                )
            }

            val again = with(fixture) { publishingRun(on) }.run(publish)
            assertEquals(IdentityPublishingRun.EXIT_OK, again.exitCode)
            assertEquals(2, again.counts["published"])
            assertEquals(2, fixture.directory.submissions)
        }

    @Test
    fun `a dry run and a status check never write`() =
        runTest {
            val fixture = Fixture()
            fixture.signUp("alice")
            fixture.signUp("brie", provision = false)
            val off = fixture.config(publishing = false)
            val aliceId =
                fixture.accounts
                    .getAllAccounts()
                    .first()
                    .id
            val storedBefore = fixture.operations.listByAccountId(aliceId)

            val dryRun = with(fixture) { publishingRun(off) }.run(IdentityPublishingOptions(publish = true, dryRun = true))

            assertEquals(1, dryRun.counts["would_publish_genesis"])
            assertEquals(1, dryRun.counts["would_provision_and_publish"])
            assertEquals(0, fixture.directory.submissions)
            assertEquals(storedBefore, fixture.operations.listByAccountId(aliceId))
            assertNull(fixture.accounts.findByUsername("brie")?.did)
        }

    @Test
    fun `private provisioning cannot implicitly publish a missing identity`() =
        runTest {
            val fixture = Fixture()
            fixture.signUp("cato", provision = false)

            val report = with(fixture) { publishingRun(config(publishing = true)) }.run(publish)

            assertEquals(IdentityPublishingRun.EXIT_FAILED, report.exitCode)
            val did = assertNotNull(fixture.accounts.findByUsername("cato")?.did)
            assertEquals(0, fixture.directory.log(did).size)
            assertEquals("provision_and_publish", report.accounts.single().action)
        }

    @Test
    fun `a publishing run stops at its failure limit and the next run picks up where it stopped`() =
        runTest {
            val fixture = Fixture()
            val first = fixture.signUp("alice")
            val second = fixture.signUp("brie")
            fixture.directory.failNextSubmits += 503
            val on = fixture.config(publishing = true)

            val stopped = with(fixture) { publishingRun(on) }.run(publish)

            assertEquals(IdentityPublishingRun.EXIT_FAILED, stopped.exitCode)
            assertTrue(stopped.stoppedAtFailureLimit)
            assertEquals(1, stopped.accounts.size)
            assertEquals("directory_unreachable", stopped.accounts.single().failure)
            assertTrue(fixture.directory.log(requireNotNull(second.did)).isEmpty())

            val resumed = with(fixture) { publishingRun(on) }.run(publish)

            assertEquals(IdentityPublishingRun.EXIT_OK, resumed.exitCode)
            assertEquals(1, fixture.directory.log(requireNotNull(first.did)).size)
            assertEquals(1, fixture.directory.log(requireNotNull(second.did)).size)
        }

    @Test
    fun `a genesis the directory already has is recorded without sending it again`() =
        runTest {
            val fixture = Fixture()
            val alice = fixture.signUp("alice")
            val signed = fixture.operations.listByDid(requireNotNull(alice.did)).single()
            val stored = signed.copy(cid = null)
            fixture.operations.save(stored)
            val genesis = fixture.plcIdentityService(fixture.config(publishing = false)).storedGenesisOperation(stored)
            fixture.directory.seed(stored.did, genesis)

            val report = with(fixture) { publishingRun(config(publishing = true)) }.run(publish)

            assertEquals("published_unrecorded", report.accounts.single().before)
            assertEquals("record_cid", report.accounts.single().action)
            assertEquals(0, fixture.directory.submissions)
            assertEquals(
                fixture.directory
                    .log(stored.did)
                    .single()
                    .cid,
                fixture.operations
                    .listByDid(stored.did)
                    .single()
                    .cid,
            )
        }

    @Test
    fun `a directory copy that differs is reported and only reconciled when asked`() =
        runTest {
            val fixture = Fixture()
            val alice = fixture.signUp("alice")
            with(fixture) { publishingRun(config(publishing = true)) }.run(publish)
            val moved = fixture.config(publishing = true, pdsServiceEndpoint = "https://cloud.logdate.app")

            val drift = with(fixture) { publishingRun(moved) }.run(status)
            assertEquals(IdentityPublishingRun.EXIT_WORK_REMAINS, drift.exitCode)
            assertEquals(listOf("pds_endpoint"), drift.accounts.single().differences)

            val withoutReconcile = with(fixture) { publishingRun(moved) }.run(publish)
            assertEquals(IdentityPublishingRun.EXIT_WORK_REMAINS, withoutReconcile.exitCode)
            assertEquals(1, fixture.directory.log(requireNotNull(alice.did)).size)

            val reconciled = with(fixture) { publishingRun(moved) }.run(publish.copy(reconcile = true))
            assertEquals(IdentityPublishingRun.EXIT_OK, reconciled.exitCode)
            val latest =
                fixture.directory
                    .log(requireNotNull(alice.did))
                    .last()
                    .operation as PlcOperation
            assertEquals("https://cloud.logdate.app", latest.services.getValue("atproto_pds").endpoint)
        }

    @Test
    fun `identities that can't be published are flagged for an operator`() =
        runTest {
            val fixture = Fixture()
            fixture.accounts.save(
                Account(
                    id = Uuid.random(),
                    username = "orphan",
                    displayName = "orphan",
                    did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz",
                    handle = "orphan.logdate.app",
                    signingKeyPublic = fixture.signingKeys.ensureActiveKey(Uuid.random()).publicKeyMultibase,
                    createdAt = Clock.System.now(),
                ),
            )

            val report = with(fixture) { publishingRun(config(publishing = true)) }.run(publish)

            assertEquals(IdentityPublishingRun.EXIT_FAILED, report.exitCode)
            assertEquals("missing_genesis", report.accounts.single().before)
            assertEquals(0, fixture.directory.submissions)
        }

    @Test
    fun `an identity whose owner took control is left alone`() =
        runTest {
            val fixture = Fixture()
            val alice = fixture.signUp("alice")
            val on = fixture.config(publishing = true)
            with(fixture) { publishingRun(on) }.run(publish)
            val ownerKey = didKeyFor(fixture.signingKeys.ensureActiveKey(Uuid.random()).publicKeyMultibase)
            val takeover =
                PlcOperations
                    .atprotoUpdate(
                        prevCid =
                            fixture.directory
                                .log(requireNotNull(alice.did))
                                .last()
                                .cid,
                        handle = "alice.example.com",
                        pdsServiceEndpoint = "https://elsewhere.example.com",
                        signingKeyDidKey = ownerKey,
                        rotationKeys = listOf(ownerKey),
                    ).signed("owner-signature")
            fixture.directory.seed(requireNotNull(alice.did), takeover)

            val report = with(fixture) { publishingRun(on) }.run(publish.copy(reconcile = true))

            assertEquals(IdentityPublishingRun.EXIT_OK, report.exitCode)
            assertEquals("controlled_elsewhere", report.accounts.single().before)
            assertEquals(1, fixture.directory.submissions)
        }

    @Test
    fun `a run can be limited to one account by its handle`() =
        runTest {
            val fixture = Fixture()
            fixture.signUp("alice")
            val brie = fixture.signUp("brie")
            val on = fixture.config(publishing = true)

            val one = with(fixture) { publishingRun(on) }.run(publish.copy(handle = brie.handle))
            val nobody = with(fixture) { publishingRun(on) }.run(status.copy(handle = "nobody.logdate.app"))

            assertEquals(IdentityPublishingRun.EXIT_OK, one.exitCode)
            assertEquals(listOf(brie.did), one.accounts.map { it.did })
            assertEquals(1, fixture.directory.submissions)
            assertEquals(IdentityPublishingRun.EXIT_FAILED, nobody.exitCode)
        }

    @Test
    fun `each identity the run publishes is announced once and others are not`() =
        runTest {
            val fixture = Fixture()
            val alice = fixture.signUp("alice")
            val brie = fixture.signUp("brie")
            val on = fixture.config(publishing = true)
            val announced = mutableListOf<Uuid>()

            with(fixture) { publishingRun(on) { announced += it } }.run(status)
            with(fixture) { publishingRun(on) { announced += it } }.run(IdentityPublishingOptions(publish = true, dryRun = true))
            assertEquals(emptyList(), announced, "reading and dry runs announce nothing")

            with(fixture) { publishingRun(on) { announced += it } }.run(publish)
            assertEquals(listOf(alice.id, brie.id), announced)

            with(fixture) { publishingRun(on) { announced += it } }.run(publish)
            assertEquals(2, announced.size, "an identity that was already published is not announced again")
        }

    @Test
    fun `a failure while announcing never fails the run`() =
        runTest {
            val fixture = Fixture()
            fixture.signUp("alice")
            val on = fixture.config(publishing = true)

            val report = with(fixture) { publishingRun(on) { error("the event log is down") } }.run(publish)

            assertEquals(IdentityPublishingRun.EXIT_OK, report.exitCode)
            assertEquals(1, report.counts["now_published"])
        }

    @Test
    fun `writes are spaced to the configured rate`() =
        runTest {
            val pacer = PlcWritePacer(operationsPerMinute = 2, nowMillis = { testScheduler.currentTime }, sleepMillis = { delay(it) })

            pacer.awaitTurn()
            val firstAt = testScheduler.currentTime
            pacer.awaitTurn()
            pacer.awaitTurn()

            assertEquals(60_000, testScheduler.currentTime - firstAt)
        }
}
