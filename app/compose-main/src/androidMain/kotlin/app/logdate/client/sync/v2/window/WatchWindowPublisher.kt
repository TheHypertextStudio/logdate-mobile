package app.logdate.client.sync.v2.window

import app.logdate.client.domain.streak.nextStreakDayStart
import app.logdate.client.domain.watch.WatchWindowPolicy
import app.logdate.client.domain.watch.WatchWindowPreferences
import app.logdate.client.domain.watch.WindowMember
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.datalayer.NoteDataMapper
import app.logdate.client.sync.datalayer.v2.BodyRef
import app.logdate.client.sync.datalayer.v2.DeliveryPriority
import app.logdate.client.sync.datalayer.v2.PeerDeliveryContext
import app.logdate.client.sync.datalayer.v2.RecordEnvelope
import app.logdate.client.sync.datalayer.v2.RecordEnvelopeCodec
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WindowMemberRef
import app.logdate.client.sync.datalayer.v2.WindowTombstoneDataMap
import app.logdate.client.sync.peer.delivery.EnvelopeDataMap
import app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What one publish did. [members] is the window as the watch can read it now. */
data class WindowPublishResult(
    val context: PeerDeliveryContext,
    val members: List<WindowMemberRef>,
    val written: Int,
    val removed: Int,
    val failed: Int,
    val tooLarge: Int,
    val publishedAt: Long,
    val revision: Long = 0,
)

/**
 * Publishes the watch's library window: one data item per note [WatchWindowPolicy] chooses, a tombstone
 * for each note that left, and nothing for a note unchanged since the last publish. [WindowManifestStore]
 * records what went out, so a failed write is tried again and a note that left is removed once.
 */
class WatchWindowPublisher(
    private val active: Flow<Boolean>,
    private val peers: PhoneDeliveryPeers,
    private val supports: suspend (String) -> Boolean,
    private val changes: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow(),
    private val work: WindowRollWork = WindowRollWork.None,
    private val notes: JournalNotesRepository,
    private val dayStartHour: suspend () -> Int,
    private val extras: WindowExtras,
    private val manifest: WindowManifestStore,
    private val items: WindowDataItems,
    private val policy: WatchWindowPolicy = WatchWindowPolicy(),
    private val mapper: NoteDataMapper = NoteDataMapper(),
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val clock: Clock = Clock.System,
    private val moments: Flow<List<app.logdate.client.repository.moments.MarkedMoment>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    private val momentDeletions: Flow<Map<Uuid, Instant>> = kotlinx.coroutines.flow.flowOf(emptyMap()),
    private val acceptsMoments: suspend (PeerDeliveryContext) -> Boolean = { false },
    private val preferences: suspend () -> WatchWindowPreferences = { WatchWindowPreferences() },
) {
    private val publishing = Mutex()

    /** Publishes the window while the watch takes it; null while it does not. */
    suspend fun publishIfActive(watchNode: String): WindowPublishResult? {
        if (!active.first()) return null
        val peer = peers.current(watchNode) ?: return null
        return publishing.withLock {
            try {
                requireCurrent(peer)
                publish(peer)
            } catch (_: AudienceChanged) {
                null
            }
        }
    }

    /** A negotiated watch never falls back to the legacy pull-all path while linking is unavailable. */
    suspend fun publishInstead(watchNode: String): Boolean {
        if (!active.first() || !supports(watchNode)) return false
        publishIfActive(watchNode)
        return true
    }

    suspend fun publishAll(): Boolean {
        if (!active.first()) {
            work.cancel()
            return true
        }
        var success = true
        for (peer in peers.all()) {
            if (!supports(peer.watchNode)) continue
            try {
                val result = publishIfActive(peer.watchNode)
                if (result == null || result.failed > 0) success = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.w("Could not refresh a watch library; it will retry in the background", e)
                success = false
            }
        }
        if (!active.first()) {
            work.cancel()
            return true
        }
        val next = nextStreakDayStart(clock.now(), zone(), dayStartHour())
        work.runAfter(if (success) (next - clock.now()).inWholeMilliseconds.coerceAtLeast(0) else RETRY_MS)
        return success
    }

    private suspend fun requireCurrent(peer: PeerDeliveryContext) {
        if (!active.first() || !peers.isCurrent(peer) || !supports(peer.watchNode)) throw AudienceChanged()
    }

    private class AudienceChanged : IllegalStateException()

    /**
     * Publishes when the watch starts or stops taking the window, and while it takes it, on every
     * change to the notes and at each day start.
     *
     * Notes are watched only while the watch takes the window: this runs for the whole life of the
     * process, and reading every note again on each journal change would otherwise compete with the
     * screens for the database on phones that have no watch linked.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            active
                .distinctUntilChanged()
                .flatMapLatest { taking ->
                    if (taking) {
                        merge(notes.allNotesObserved.map { }, moments.map { }, momentDeletions.map { }, changes, dayStarts())
                            .onStart { emit(Unit) }
                    } else {
                        flowOf(Unit)
                    }
                }.debounce(SETTLE_MS)
                .collect { publishLogged() }
        }

    private suspend fun publish(peer: PeerDeliveryContext): WindowPublishResult {
        val now = clock.now()
        val selection = preferences()
        val window =
            selectWindow(notes.allNotesObserved.first(), policy, extras.ids(), now, zone(), dayStartHour(), selection) { note ->
                NoteEnvelopeBody.encode(note, mapper)
            }
        val plan = window.plan
        val all = window.notes
        val bodies = window.bodies
        if (plan.dropped > 0) Napier.w("${plan.dropped} note(s) do not fit the watch's library window; the most recent are chosen")
        requireCurrent(peer)
        val previous = manifest.read(peer)
        val interrupted = manifest.beginPublication(peer)
        requireCurrent(peer)
        val published = previous.items.toMutableMap()
        val tombstones = previous.tombstones.toMutableMap()
        val counts = Counts()
        plan.members.forEach { member ->
            write(peer, all.getValue(member.id), member, bodies.getValue(member.id), published, tombstones, counts, interrupted)
        }
        val inWindow = plan.members.mapTo(HashSet()) { it.id.toString() }
        (published.keys - inWindow).forEach { id -> remove(peer, id, published, tombstones, now, counts) }
        clearOldTombstones(peer, tombstones, inWindow, now, counts)
        requireCurrent(peer)
        manifest.write(peer, previous.copy(items = published, tombstones = tombstones))
        val momentResult = publishMoments(peer, plan.members, bodies, now, interrupted, selection)
        if (counts.failed == 0 && momentResult.failed == 0) manifest.finishPublication(peer)
        requireCurrent(peer)
        val members =
            plan.members.mapNotNull { member ->
                published[member.id.toString()]?.let {
                    WindowMemberRef(
                        RecordKind.NOTE,
                        member.id.toString(),
                        it.contentVersion,
                        it.revision,
                    )
                }
            }
        return WindowPublishResult(
            peer,
            members + momentResult.members,
            counts.written + momentResult.written,
            counts.removed + momentResult.removed,
            counts.failed + momentResult.failed,
            counts.tooLarge,
            now.toEpochMilliseconds(),
            manifest.revision(peer),
        )
    }

    private suspend fun publishMoments(
        peer: PeerDeliveryContext,
        notes: List<WindowMember>,
        bodies: Map<Uuid, JsonElement>,
        now: Instant,
        interrupted: Boolean,
        selection: WatchWindowPreferences,
    ): MomentWindowPublished {
        if (!acceptsMoments(peer)) return MomentWindowPublished()
        val used = notes.sumOf { bodies.getValue(it.id).sizeBytes() }
        return PhoneMomentWindowPublisher(manifest, items).publish(
            peer,
            moments.first(),
            now,
            zone(),
            dayStartHour(),
            (400 - notes.size).coerceAtLeast(0),
            (8L * 1024 * 1024 - used).coerceAtLeast(0),
            interrupted,
            momentDeletions.first(),
            preferences = selection,
        ) {
            requireCurrent(peer)
            if (!acceptsMoments(peer)) throw AudienceChanged()
        }
    }

    private suspend fun write(
        peer: PeerDeliveryContext,
        note: JournalNote,
        member: WindowMember,
        body: JsonElement,
        published: MutableMap<String, PublishedItem>,
        tombstones: MutableMap<String, Long>,
        counts: Counts,
        interrupted: Boolean,
    ) {
        val id = member.id.toString()
        val version = note.lastUpdated.toEpochMilliseconds()
        val bodyBytes = body.toString().encodeToByteArray()
        val bodyHash =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bodyBytes)
                .joinToString("") { "%02x".format(it) }
        val wanted = PublishedItem(version, member.reasons, bodySha256 = bodyHash)
        val existing = published[id]
        if (!interrupted &&
            existing != null &&
            existing.contentVersion == version &&
            existing.reasons == member.reasons &&
            existing.bodySha256 == bodyHash &&
            existing.revision > 0
        ) {
            return
        }
        val data = encodeNote(peer, note, member, body, bodyHash, bodyBytes, counts) ?: return
        requireCurrent(peer)
        val revision = manifest.reserveRevision(peer)
        requireCurrent(peer)
        if (id in tombstones || interrupted) {
            if (!items.deleteOwn(WearPaths.windowTombstone(RecordKind.NOTE, member.id, peer))) {
                counts.failed++
                return
            }
            requireCurrent(peer)
            tombstones.remove(id)
        }
        if (!items.put(
                WearPaths.windowItem(RecordKind.NOTE, member.id, peer),
                data + (WindowTombstoneDataMap.KEY_REVISION to revision.toString()),
                urgent = false,
            )
        ) {
            counts.failed++
            Napier.w("Could not write a note to the watch's library window; it is written again on the next publish")
            return
        }
        requireCurrent(peer)
        published[id] = wanted.copy(revision = revision)
        counts.written++
    }

    private fun encodeNote(
        peer: PeerDeliveryContext,
        note: JournalNote,
        member: WindowMember,
        body: JsonElement,
        bodyHash: String,
        bodyBytes: ByteArray,
        counts: Counts,
    ): Map<String, String>? {
        val id = member.id.toString()
        val version = note.lastUpdated.toEpochMilliseconds()
        val bodyRef = if (RecordEnvelopeCodec.needsChannel(body)) BodyRef(bodyHash, bodyBytes.size.toLong()) else null
        val envelope =
            RecordEnvelope(
                kind = RecordKind.NOTE,
                id = id,
                contentVersion = version,
                syncVersion = note.syncVersion,
                origin = RecordOrigin.PHONE,
                context = peer,
                reasons = member.reasons,
                priority = DeliveryPriority.NORMAL,
                body = body.takeIf { bodyRef == null },
                bodyRef = bodyRef,
            )
        return try {
            // The send time is the version, so a repeat of the same version is identical and the Data Layer sends nothing.
            EnvelopeDataMap.toDataMap(envelope, sentAt = version)
        } catch (e: IllegalArgumentException) {
            counts.tooLarge++
            Napier.w("Could not encode a note for the watch library window")
            null
        }
    }

    private suspend fun remove(
        peer: PeerDeliveryContext,
        id: String,
        published: MutableMap<String, PublishedItem>,
        tombstones: MutableMap<String, Long>,
        now: Instant,
        counts: Counts,
    ) {
        val uuid = Uuid.parseOrNull(id)
        if (uuid == null) {
            published.remove(id)
            return
        }
        requireCurrent(peer)
        val revision = manifest.reserveRevision(peer)
        requireCurrent(peer)
        val at = now.toEpochMilliseconds()
        if (!items.put(
                WearPaths.windowTombstone(RecordKind.NOTE, uuid, peer),
                WindowTombstoneDataMap.toDataMap(at, peer, revision),
                urgent = true,
            )
        ) {
            counts.failed++
            Napier.w("Could not tell the watch a note left its library window; it is told on the next publish")
            return
        }
        requireCurrent(peer)
        published.remove(id)
        tombstones[id] = at
        counts.removed++
    }

    /** A week is long enough for every watch to see a tombstone; a watch away longer asks for the whole window. */
    private suspend fun clearOldTombstones(
        peer: PeerDeliveryContext,
        tombstones: MutableMap<String, Long>,
        desired: Set<String>,
        now: Instant,
        counts: Counts,
    ) {
        val cutoff = now.toEpochMilliseconds() - TOMBSTONE_KEEP_MS
        for ((id, removedAt) in tombstones.toMap()) {
            if (id in desired) continue
            requireCurrent(peer)
            val uuid = Uuid.parseOrNull(id)
            if (uuid == null) {
                tombstones.remove(id)
                continue
            }
            // Never retire a removal marker while the old item could reappear during reconciliation.
            if (!items.deleteOwn(WearPaths.windowItem(RecordKind.NOTE, uuid, peer))) {
                counts.failed++
                continue
            }
            requireCurrent(peer)
            if (removedAt < cutoff) {
                if (items.deleteOwn(WearPaths.windowTombstone(RecordKind.NOTE, uuid, peer))) {
                    requireCurrent(peer)
                    tombstones.remove(id)
                } else {
                    counts.failed++
                }
            }
        }
    }

    private fun dayStarts(): Flow<Unit> =
        flow {
            while (true) {
                val next = nextStreakDayStart(clock.now(), zone(), dayStartHour())
                delay(next - clock.now() + DAY_START_SLACK)
                emit(Unit)
            }
        }

    private suspend fun publishLogged() {
        try {
            publishAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.e("Could not publish the watch's library window; it is published again on the next change", e)
        }
    }

    private class Counts {
        var written = 0
        var removed = 0
        var failed = 0
        var tooLarge = 0
    }

    private companion object {
        const val RETRY_MS = 60_000L
        const val SETTLE_MS = 2_000L
        const val TOMBSTONE_KEEP_MS = 7L * 24 * 60 * 60 * 1000
        val DAY_START_SLACK = 1.seconds
    }
}
