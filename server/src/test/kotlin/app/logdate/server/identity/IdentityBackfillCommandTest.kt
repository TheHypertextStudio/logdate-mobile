package app.logdate.server.identity

import app.logdate.server.auth.Account
import app.logdate.server.auth.InMemoryAccountRepository
import app.logdate.server.logging.IdentityNotices
import app.logdate.server.logging.safeServerMessage
import io.github.aakira.napier.LogLevel
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import studio.hypertext.atproto.plc.PlcDirectoryClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IdentityBackfillCommandTest {
    @Test
    fun `options parse only the backfill command and refuse unknown flags`() {
        assertNull(IdentityBackfillOptions.parse(emptyArray()))
        assertNull(IdentityBackfillOptions.parse(arrayOf("--cleanup-account-blobs")))
        assertEquals(
            IdentityBackfillOptions(dryRun = true, allowPlcPublishing = false),
            IdentityBackfillOptions.parse(arrayOf("--backfill-identities", "--dry-run")),
        )
        assertEquals(
            IdentityBackfillOptions(dryRun = false, allowPlcPublishing = true),
            IdentityBackfillOptions.parse(arrayOf("--backfill-identities", "--allow-plc-publishing")),
        )
        assertFailsWith<IllegalArgumentException> {
            IdentityBackfillOptions.parse(arrayOf("--backfill-identities", "--publish"))
        }
    }

    @Test
    fun `a dry run counts accounts without changing them`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val legacy = accounts.save(account("legacy"))

            val outcome = runIdentityBackfill(service(accounts), IdentityBackfillOptions(dryRun = true, allowPlcPublishing = false))

            assertEquals(IdentityBackfillOutcome.DryRun(pending = 1), outcome)
            assertEquals(legacy, accounts.findById(legacy.id))
        }

    @Test
    fun `a real run provisions every account that lacks an identity`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val legacy = accounts.save(account("legacy"))

            val outcome = runIdentityBackfill(service(accounts), IdentityBackfillOptions(dryRun = false, allowPlcPublishing = false))

            assertEquals(IdentityBackfillOutcome.Completed(IdentityBackfillResult(provisioned = 1, skipped = 0, failed = 0)), outcome)
            assertEquals("legacy.logdate.app", accounts.findById(legacy.id)?.handle)
        }

    @Test
    fun `a run refuses to publish PLC operations unless the operator allows it`() =
        runTest {
            val accounts = InMemoryAccountRepository()
            val legacy = accounts.save(account("legacy"))
            val publishingConfig =
                AtprotoIdentityConfig(
                    handleDomain = "logdate.app",
                    pdsServiceEndpoint = "https://logdate.app",
                    publishHostedPlcOperations = true,
                )
            val signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek")
            val publishing =
                AtprotoIdentityService(
                    accountRepository = accounts,
                    signingKeyService = signingKeyService,
                    config = publishingConfig,
                    plcIdentityService =
                        PlcIdentityService(
                            signingKeyService = signingKeyService,
                            config = publishingConfig,
                            plcDirectoryClient = mockk<PlcDirectoryClient>(),
                        ),
                )

            for (allow in listOf(false, true)) {
                val outcome = runIdentityBackfill(publishing, IdentityBackfillOptions(dryRun = false, allowPlcPublishing = allow))
                assertEquals(IdentityBackfillOutcome.RefusedPublishing, outcome)
                assertEquals(legacy, accounts.findById(legacy.id))
            }
        }

    @Test
    fun `backfill and resolution messages survive the production log filter`() {
        assertEquals(
            "Identity backfill dry run: 12 accounts need an identity",
            safeServerMessage(LogLevel.INFO, null, IdentityNotices.backfillDryRun(12)),
        )
        assertEquals(
            "Identity backfill complete: 10 provisioned, 1 skipped, 2 failed",
            safeServerMessage(LogLevel.INFO, null, IdentityNotices.backfillComplete(provisioned = 10, skipped = 1, failed = 2)),
        )
        assertEquals(
            "SERVER_IDENTITY_BACKFILL_ACCOUNT_FAILED",
            safeServerMessage(LogLevel.ERROR, null, IdentityNotices.BACKFILL_ACCOUNT_FAILED),
        )
        assertEquals(
            "SERVER_HANDLE_RESOLUTION_FAILED",
            safeServerMessage(LogLevel.ERROR, null, IdentityNotices.HANDLE_RESOLUTION_FAILED),
        )
        assertEquals(
            "SERVER_IDENTITY_BACKFILL_REFUSED_PUBLISHING",
            safeServerMessage(LogLevel.ERROR, null, IdentityNotices.BACKFILL_REFUSED_PUBLISHING),
        )
        assertEquals("SERVER_NOTICE", safeServerMessage(LogLevel.INFO, null, "Identity backfill dry run: alice accounts"))
    }

    private fun account(username: String) =
        Account(id = Uuid.random(), username = username, displayName = username, createdAt = Clock.System.now())

    private fun service(accounts: InMemoryAccountRepository) =
        AtprotoIdentityService(
            accountRepository = accounts,
            signingKeyService = SigningKeyService(InMemorySigningKeyRepository(), "test-kek"),
            config = AtprotoIdentityConfig(handleDomain = "logdate.app", pdsServiceEndpoint = "https://logdate.app"),
        )
}
