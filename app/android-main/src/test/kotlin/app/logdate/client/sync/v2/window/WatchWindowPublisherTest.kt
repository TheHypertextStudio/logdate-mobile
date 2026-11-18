package app.logdate.client.sync.v2.window

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WindowMemberRef
import app.logdate.client.sync.datalayer.v2.WindowReason
import app.logdate.client.sync.datalayer.v2.WindowTombstoneDataMap
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.v2.MemoryKeyValueStorage
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Tests [WatchWindowPublisher]: the window goes out once, changes go out as they happen, and leavers get tombstones. */
class WatchWindowPublisherTest {
    private val peer = PeerDeliveryContext(LinkWire.encodeKey(ByteArray(32) { 1 }), "phone", "watch", LinkMode.PAIR)
    private var current = peer
    private var supported = true
    private val peers =
        mockk<PhoneDeliveryPeers> {
            coEvery { current(any()) } answers { current.takeIf { it.watchNode == firstArg<String>() } }
            coEvery { all() } answers { listOf(current) }
            coEvery { isCurrent(any()) } answers { firstArg<PeerDeliveryContext>() == current }
        }
    private val start = LocalDateTime(2026, 10, 10, 10, 0).toInstant(TimeZone.UTC)
    private var now = start
    private val notesFlow = MutableStateFlow<List<JournalNote>>(emptyList())
    private val notes = mockk<JournalNotesRepository> { every { allNotesObserved } returns notesFlow }
    private val storage = MemoryKeyValueStorage()
    private val items = FakeWindowItems()
    private val active = MutableStateFlow(true)
    private val linked = MutableStateFlow(true)
    private val deadlines = mutableListOf<Long>()
    private var cancelled = false
    private val work =
        object : WindowRollWork {
            override fun runAfter(delayMs: Long) {
                deadlines += delayMs
            }

            override fun cancel() {
                cancelled = true
            }
        }
    private val publisher =
        WatchWindowPublisher(
            active = active,
            work = work,
            peers = peers,
            supports = { supported },
            notes = notes,
            dayStartHour = { 4 },
            extras = WindowExtras.None,
            manifest = WindowManifestStore(storage),
            items = items,
            zone = { TimeZone.UTC },
            clock =
                object : Clock {
                    override fun now(): Instant = now
                },
            linked = linked,
        )
    private val recent = text("written yesterday", start - 1.days)
    private val old = text("written long ago", start - 400.days)

    @Test fun `oversized notes publish a bounded body reference instead of being omitted`() =
        runTest {
            val large = recent.copy(content = "large note".repeat(15000))
            notesFlow.value = listOf(large)
            val result = assertNotNull(publisher.publishIfActive("watch"))
            assertEquals(1, result.written)
            assertEquals(0, result.tooLarge)
            val envelope = (EnvelopeDataMap.fromDataMap(items.puts.single().second) as Decoded.Supported).value
            assertNull(envelope.body)
            val reference = assertNotNull(envelope.bodyRef)
            val bytes =
                app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
                    .encode(large)
                    .toString()
                    .encodeToByteArray()
            assertEquals(bytes.size.toLong(), reference.sizeBytes)
            assertEquals(
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                reference.sha256,
            )
            assertEquals(1, result.members.size)
        }

    @Test
    fun `a large body edited within the same timestamp publishes a new revision`() =
        runTest {
            val large = recent.copy(content = "old large note".repeat(15000))
            notesFlow.value = listOf(large)
            publisher.publishIfActive("watch")
            val first = items.puts.single().second
            items.puts.clear()
            notesFlow.value = listOf(large.copy(content = "new large note".repeat(15000)))
            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).written)
            val next = items.puts.single().second
            val before = (EnvelopeDataMap.fromDataMap(first) as Decoded.Supported).value
            val after = (EnvelopeDataMap.fromDataMap(next) as Decoded.Supported).value
            assertTrue(before.bodyRef != after.bodyRef)
            assertTrue(
                next.getValue(WindowTombstoneDataMap.KEY_REVISION).toLong() > first.getValue(WindowTombstoneDataMap.KEY_REVISION).toLong(),
            )
        }

    @Test
    fun `while the watch does not take the window nothing is published`() =
        runTest {
            active.value = false
            notesFlow.value = listOf(recent)

            assertNull(publisher.publishIfActive("watch"))
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `notes are read only while the watch takes the window`() =
        runTest {
            active.value = false
            publisher.start(backgroundScope)
            runCurrent()
            assertEquals(0, notesFlow.subscriptionCount.value)
            advanceTimeBy(3_000)
            runCurrent()
            assertTrue(cancelled)

            active.value = true
            runCurrent()
            assertEquals(1, notesFlow.subscriptionCount.value)

            active.value = false
            runCurrent()
            assertEquals(0, notesFlow.subscriptionCount.value)
        }

    @Test
    fun `notes are not read while no watch is linked`() =
        runTest {
            linked.value = false
            publisher.start(backgroundScope)
            runCurrent()
            assertEquals(0, notesFlow.subscriptionCount.value)

            linked.value = true
            runCurrent()
            assertEquals(1, notesFlow.subscriptionCount.value)

            linked.value = false
            runCurrent()
            assertEquals(0, notesFlow.subscriptionCount.value)
        }

    @Test
    fun `each note in the window is written with its reasons`() =
        runTest {
            notesFlow.value = listOf(recent, old)

            val result = assertNotNull(publisher.publishIfActive("watch"))

            val (path, data) = items.puts.single()
            assertEquals(WearPaths.windowItem(RecordKind.NOTE, recent.uid, peer), path)
            val envelope = (EnvelopeDataMap.fromDataMap(data) as Decoded.Supported).value
            assertEquals(setOf(WindowReason.RECENT), envelope.reasons)
            assertEquals(RecordOrigin.PHONE, envelope.origin)
            assertEquals(peer, envelope.context)
            val expected = WindowMemberRef(RecordKind.NOTE, recent.uid.toString(), recent.lastUpdated.toEpochMilliseconds(), revision = 1)
            assertEquals(listOf(expected), result.members)
        }

    @Test
    fun `a note unchanged since the last publish is not written again`() =
        runTest {
            notesFlow.value = listOf(recent)

            publisher.publishIfActive("watch")
            publisher.publishIfActive("watch")

            assertEquals(1, items.puts.size)
        }

    @Test
    fun `a note that left the window is deleted and gets a tombstone`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            notesFlow.value = emptyList()

            val result = assertNotNull(publisher.publishIfActive("watch"))

            assertEquals(listOf(WearPaths.windowItem(RecordKind.NOTE, recent.uid, peer)), items.deleted)
            assertEquals(WearPaths.windowTombstone(RecordKind.NOTE, recent.uid, peer), items.puts.last().first)
            assertTrue(items.urgent.last())
            assertEquals(1, result.removed)
            assertTrue(result.members.isEmpty())
        }

    @Test
    fun `a note that could not be written is left out and written on the next publish`() =
        runTest {
            items.putOk = false
            notesFlow.value = listOf(recent)

            val failed = assertNotNull(publisher.publishIfActive("watch"))

            assertEquals(1, failed.failed)
            assertTrue(failed.members.isEmpty())

            items.putOk = true
            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).written)
        }

    @Test
    fun `a large referenced note is not rewritten when its publication is unchanged`() =
        runTest {
            notesFlow.value = listOf(text("x".repeat(100 * 1024), start - 1.days))

            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).written)
            items.puts.clear()
            assertEquals(0, assertNotNull(publisher.publishIfActive("watch")).written)
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `a tombstone is deleted after a week`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            notesFlow.value = emptyList()
            publisher.publishIfActive("watch")
            now = start + 8.days

            publisher.publishIfActive("watch")

            assertTrue(WearPaths.windowTombstone(RecordKind.NOTE, recent.uid, peer) in items.deleted)
        }

    @Test
    fun `a note back in the window loses its tombstone`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            notesFlow.value = emptyList()
            publisher.publishIfActive("watch")
            notesFlow.value = listOf(recent)

            publisher.publishIfActive("watch")

            assertTrue(WearPaths.windowTombstone(RecordKind.NOTE, recent.uid, peer) in items.deleted)
            assertEquals(WearPaths.windowItem(RecordKind.NOTE, recent.uid, peer), items.puts.last().first)
        }

    @Test
    fun `an unreadable record of what was sent sends the whole window again`() =
        runTest {
            storage.putString(
                "${WindowManifestStore.KEY}.${app.logdate.client.sync.datalayer.v2.WearJson.encode(
                    PeerDeliveryContext.serializer(),
                    peer,
                )}",
                "nope",
            )
            notesFlow.value = listOf(recent)

            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).written)
        }

    @Test
    fun `another watch cannot request this link window`() =
        runTest {
            notesFlow.value = listOf(recent)
            assertNull(publisher.publishIfActive("stranger"))
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `an unnegotiated watch receives no window`() =
        runTest {
            supported = false
            notesFlow.value = listOf(recent)
            assertNull(publisher.publishIfActive("watch"))
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `a fresh exchange republishes unchanged content under its own link`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            current = peer.copy(linkId = LinkWire.encodeKey(ByteArray(32) { 2 }))
            publisher.publishIfActive("watch")
            assertEquals(2, items.puts.size)
            val envelope = (EnvelopeDataMap.fromDataMap(items.puts.last().second) as Decoded.Supported).value
            assertEquals(current, envelope.context)
            assertEquals(WearPaths.windowItem(RecordKind.NOTE, recent.uid, current), items.puts.last().first)
        }

    @Test
    fun `a link change during a write cannot credit the old audience`() =
        runTest {
            notesFlow.value = listOf(recent)
            items.afterPut = { current = peer.copy(linkId = LinkWire.encodeKey(ByteArray(32) { 2 })) }
            assertNull(publisher.publishIfActive("watch"))
            items.afterPut = {}
            val next = assertNotNull(publisher.publishIfActive("watch"))
            assertEquals(current, next.context)
            assertEquals(1, next.written)
        }

    @Test
    fun `a failed removal is retried until its tombstone is published`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            notesFlow.value = emptyList()
            items.putOk = false
            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).failed)
            items.putOk = true
            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).removed)
        }

    @Test
    fun `a returning note waits until its old tombstone can be removed`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            notesFlow.value = emptyList()
            publisher.publishIfActive("watch")
            notesFlow.value = listOf(recent)
            items.deleteOk = false
            val failed = assertNotNull(publisher.publishIfActive("watch"))
            assertEquals(1, failed.failed)
            assertTrue(failed.members.isEmpty())
            items.deleteOk = true
            assertEquals(1, assertNotNull(publisher.publishIfActive("watch")).written)
        }

    @Test
    fun `negotiated window pull holds while the verified link is unavailable`() =
        runTest {
            assertTrue(publisher.publishInstead("stranger"))
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `an older watch keeps its legacy pull`() =
        runTest {
            supported = false
            assertEquals(false, publisher.publishInstead("watch"))
        }

    @Test
    fun `publishing all watches persists the next logical day refresh`() =
        runTest {
            notesFlow.value = listOf(recent)
            assertTrue(publisher.publishAll())
            assertEquals(1, items.puts.size)
            assertEquals(listOf(64_800_000L), deadlines)
        }

    @Test
    fun `failed publication schedules a short background retry`() =
        runTest {
            notesFlow.value = listOf(recent)
            items.putOk = false
            assertEquals(false, publisher.publishAll())
            assertEquals(listOf(60_000L), deadlines)
        }

    @Test
    fun `turning the experience off cancels background library refresh`() =
        runTest {
            active.value = false
            assertTrue(publisher.publishAll())
            assertTrue(cancelled)
            assertTrue(deadlines.isEmpty())
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `a failed item deletion cannot outlive its removal marker`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            items.undeletable = WearPaths.windowItem(RecordKind.NOTE, recent.uid, peer)
            notesFlow.value = emptyList()
            publisher.publishIfActive("watch")
            now = start + 8.days
            publisher.publishIfActive("watch")
            assertTrue(recent.uid.toString() in WindowManifestStore(storage).read(peer).tombstones)
            assertTrue(WearPaths.windowTombstone(RecordKind.NOTE, recent.uid, peer) !in items.deleted)
            items.undeletable = null
            publisher.publishIfActive("watch")
            assertTrue(WindowManifestStore(storage).read(peer).tombstones.isEmpty())
        }

    @Test
    fun `a returning note is newer than its delayed removal even when the clock moves back`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            val first =
                items.puts
                    .last()
                    .second[WindowTombstoneDataMap.KEY_REVISION]
                    ?.toLongOrNull()
            notesFlow.value = emptyList()
            publisher.publishIfActive("watch")
            val removal =
                items.puts
                    .last()
                    .second[WindowTombstoneDataMap.KEY_REVISION]
                    ?.toLongOrNull()
            now = start - 1.hours
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            val returned =
                items.puts
                    .last()
                    .second[WindowTombstoneDataMap.KEY_REVISION]
                    ?.toLongOrNull()
            assertEquals(listOf(1L, 2L, 3L), listOf(first, removal, returned))
        }

    @Test
    fun `publication revisions survive losing the manifest and restarting the store`() =
        runTest {
            val store = WindowManifestStore(storage)
            assertEquals(1L, store.reserveRevision(peer))
            storage.putString(
                WindowManifestStore.KEY + "." +
                    WearJson.encode(PeerDeliveryContext.serializer(), peer),
                "nope",
            )
            assertEquals(2L, WindowManifestStore(storage).reserveRevision(peer))
            assertEquals(2L, WindowManifestStore(storage).revision(peer))
        }

    @Test
    fun `a failed write consumes its revision before another attempt`() =
        runTest {
            notesFlow.value = listOf(recent)
            items.putOk = false
            val failed = assertNotNull(publisher.publishIfActive("watch"))
            assertEquals(1L, failed.revision)
            items.putOk = true
            val succeeded = assertNotNull(publisher.publishIfActive("watch"))
            assertEquals(2L, succeeded.revision)
            assertEquals(2L, succeeded.members.single().revision)
            assertEquals("2", items.puts.single().second[WindowTombstoneDataMap.KEY_REVISION])
        }

    @Test
    fun `revision allocation belongs to the exact link exchange`() =
        runTest {
            val store = WindowManifestStore(storage)
            val replacement = peer.copy(linkId = LinkWire.encodeKey(ByteArray(32) { 2 }))
            assertEquals(1L, store.reserveRevision(peer))
            assertEquals(1L, store.reserveRevision(replacement))
            assertEquals(2L, store.reserveRevision(peer))
            assertEquals(1L, store.revision(replacement))
        }

    @Test
    fun `an unreadable revision counter prevents publication`() =
        runTest {
            notesFlow.value = listOf(recent)
            storage.putString(
                WindowManifestStore.KEY + "." + WearJson.encode(PeerDeliveryContext.serializer(), peer) + ".revision",
                "broken",
            )
            assertFailsWith<IllegalStateException> { publisher.publishIfActive("watch") }
            assertTrue(items.puts.isEmpty())
        }

    @Test
    fun `a crash after a removal write cannot hide an unchanged returning note`() =
        runTest {
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            notesFlow.value = emptyList()
            items.afterPut = { throw IllegalStateException("process died before manifest save") }
            assertFailsWith<IllegalStateException> { publisher.publishIfActive("watch") }
            items.afterPut = {}
            notesFlow.value = listOf(recent)
            publisher.publishIfActive("watch")
            assertEquals(WearPaths.windowItem(RecordKind.NOTE, recent.uid, peer), items.puts.last().first)
            assertEquals("3", items.puts.last().second[WindowTombstoneDataMap.KEY_REVISION])
        }

    private fun text(
        content: String,
        at: Instant,
    ) = JournalNote.Text(uid = Uuid.random(), creationTimestamp = at, lastUpdated = at, content = content)

    private class FakeWindowItems : WindowDataItems {
        var putOk = true
        var deleteOk = true
        var undeletable: String? = null
        var afterPut: () -> Unit = {}
        val puts = mutableListOf<Pair<String, Map<String, String>>>()
        val urgent = mutableListOf<Boolean>()
        val deleted = mutableListOf<String>()

        override suspend fun put(
            path: String,
            data: Map<String, String>,
            urgent: Boolean,
        ): Boolean {
            if (!putOk) return false
            puts += path to data
            this.urgent += urgent
            afterPut()
            return true
        }

        override suspend fun deleteOwn(path: String): Boolean {
            deleted += path
            return deleteOk && path != undeletable
        }
    }
}
