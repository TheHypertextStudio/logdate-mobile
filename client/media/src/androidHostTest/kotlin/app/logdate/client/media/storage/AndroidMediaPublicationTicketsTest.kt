package app.logdate.client.media.storage

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidMediaPublicationTicketsTest {
    @Test
    fun `publication target is durably ticketed before any media bytes become visible`() =
        runBlocking {
            val root = Files.createTempDirectory("media-publications-").toFile()
            try {
                val reference = "file://${root.absolutePath}/media/objects/sha256/ab/photo.jpg"
                val target = java.io.File(java.net.URI(reference))
                val ticket =
                    AndroidMediaPublicationTickets(root).reserve(
                        destination = "canonical-media",
                        sourcePath = "${root.absolutePath}/media/staging/in-flight.tmp",
                        reference = reference,
                    )

                assertTrue(!target.exists())
                assertEquals(reference, AndroidMediaPublicationTickets(root).pending().single { it.reference == reference }.reference)
                assertEquals(ticket.id, AndroidMediaPublicationTickets(root).pending().single { it.reference == reference }.id)
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `deduplicated publications retain separate durable destination tickets`() =
        runBlocking {
            val root = Files.createTempDirectory("media-publications-").toFile()
            try {
                val first = AndroidMediaPublicationTickets(root).reserve("entry-draft/first/photo", "/staged/first.jpg")
                val second = AndroidMediaPublicationTickets(root).reserve("entry-draft/second/photo", "/staged/second.jpg")
                val reference = "file:///private/media/objects/sha256/ab/photo.jpg"
                AndroidMediaPublicationTickets(root).publish(first.id, reference)
                AndroidMediaPublicationTickets(root).publish(second.id, reference)

                val afterRestart = AndroidMediaPublicationTickets(root)
                assertEquals(
                    setOf(first.id, second.id),
                    afterRestart
                        .pending()
                        .filter { it.reference == reference }
                        .map { it.id }
                        .toSet(),
                )
                val destinations =
                    afterRestart
                        .pending()
                        .filter { it.reference == reference }
                        .map { it.destination }
                        .toSet()
                assertEquals(setOf("entry-draft/first/photo", "entry-draft/second/photo"), destinations)

                afterRestart.acknowledge(first.id, reference)
                assertEquals(
                    listOf(second.id),
                    AndroidMediaPublicationTickets(root).pending().filter { it.reference == reference }.map { it.id },
                )
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `crash before publication retains source custody and does not create a false URI ticket`() =
        runBlocking {
            val root = Files.createTempDirectory("media-publications-").toFile()
            try {
                val reserved = AndroidMediaPublicationTickets(root).reserve("entry-draft/first/photo", "/staged/capture.jpg")
                val afterRestart = AndroidMediaPublicationTickets(root)
                assertEquals(reserved, afterRestart.pending().single())
                assertTrue(afterRestart.pending().none { it.reference == "file:///private/media/objects/sha256/ab/photo.jpg" })
            } finally {
                root.deleteRecursively()
            }
        }

    @Test
    fun `damaged ticket blocks collection rather than disappearing from the census`() =
        runBlocking {
            val root = Files.createTempDirectory("media-publications-").toFile()
            try {
                val ticket = AndroidMediaPublicationTickets(root).reserve("entry-draft/first/photo", "/staged/capture.jpg")
                root.resolve("media-reclamation/tickets/${ticket.id}.ticket").writeText("damaged")
                assertFailsWith<IllegalStateException> { AndroidMediaPublicationTickets(root).pending() }
                Unit
            } finally {
                root.deleteRecursively()
            }
        }
}
