package app.logdate.server.identity

import app.logdate.server.logging.IdentityPublishingNotices
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdentityPublishingCommandTest {
    private val publishingOn =
        AtprotoIdentityConfig(
            publishHostedPlcOperations = true,
            plcDirectoryUrl = "http://127.0.0.1:2582",
        )
    private val publishingOff = AtprotoIdentityConfig.fromEnvironment(plcDirectoryUrl = "http://127.0.0.1:2582")

    @Test
    fun `status and dry runs are read-only and need no confirmation`() {
        val status =
            parseIdentityPublishingArgs(
                listOf("status", "--limit", "10", "--handle", "Alice.LogDate.app", "--report-file", "/tmp/report.json"),
            )
        val dryRun = parseIdentityPublishingArgs(listOf("publish", "--dry-run", "--reconcile", "--batch-size", "5"))

        assertFalse(status.options.writes)
        assertEquals(10, status.options.limit)
        assertEquals("alice.logdate.app", status.options.handle)
        assertFalse(dryRun.options.writes)
        assertTrue(dryRun.options.reconcile)
        assertEquals(5, dryRun.options.batchSize)
        assertNull(identityPublishingRefusal(status, publishingOff))
        assertNull(identityPublishingRefusal(dryRun, publishingOff))
    }

    @Test
    fun `publishing needs the server flag and the directory named exactly`() {
        val confirmed = parseIdentityPublishingArgs(listOf("publish", "--confirm-directory", "http://127.0.0.1:2582/"))
        val unconfirmed = parseIdentityPublishingArgs(listOf("publish"))
        val wrongDirectory = parseIdentityPublishingArgs(listOf("publish", "--confirm-directory", "https://plc.directory"))

        assertTrue(confirmed.options.writes)
        assertNull(identityPublishingRefusal(confirmed, publishingOn))
        assertEquals(IdentityPublishingNotices.REFUSED_PUBLISHING_OFF, identityPublishingRefusal(confirmed, publishingOff))
        assertEquals(IdentityPublishingNotices.REFUSED_DIRECTORY_UNCONFIRMED, identityPublishingRefusal(unconfirmed, publishingOn))
        assertEquals(IdentityPublishingNotices.REFUSED_DIRECTORY_UNCONFIRMED, identityPublishingRefusal(wrongDirectory, publishingOn))
    }

    @Test
    fun `servers that give accounts did web have nothing to publish`() {
        val webConfig = AtprotoIdentityConfig.fromEnvironment(hostedAccountDidMethod = "web")

        assertEquals(
            IdentityPublishingNotices.REFUSED_NOT_PLC,
            identityPublishingRefusal(parseIdentityPublishingArgs(listOf("status")), webConfig),
        )
    }

    @Test
    fun `invalid command lines are refused before anything runs`() {
        listOf(
            emptyList(),
            listOf("publish-all"),
            listOf("status", "--confirm-directory", "https://plc.directory"),
            listOf("status", "--reconcile"),
            listOf("publish", "--dry-run", "--confirm-directory", "https://plc.directory"),
            listOf("publish", "--dry-run", "--operations-per-minute", "10"),
            listOf("publish", "--batch-size"),
            listOf("publish", "--batch-size", "many"),
            listOf("publish", "--batch-size", "0"),
            listOf("publish", "--operations-per-minute", "100000"),
            listOf("publish", "--limit", "1", "--limit", "2"),
            listOf("publish", "--force"),
        ).forEach { args ->
            assertFailsWith<IdentityPublishingUsageException>("accepted $args") { parseIdentityPublishingArgs(args) }
        }
    }

    @Test
    fun `the report is readable only by its owner`() {
        val directory = Files.createTempDirectory("identity-publishing-report")
        val path = directory.resolve("report.json")
        val report =
            IdentityPublishingReport(
                mode = "status",
                directory = "http://127.0.0.1:2582",
                accountsConsidered = 0,
                stoppedAtFailureLimit = false,
                counts = emptyMap(),
                accounts = emptyList(),
                exitCode = 0,
            )

        writeIdentityPublishingReport(path, report)
        writeIdentityPublishingReport(path, report.copy(exitCode = 3))

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
        assertTrue(Files.readString(path).contains("\"exitCode\": 3"))
        assertEquals(listOf(path), Files.list(directory).use { it.toList() })
    }
}
