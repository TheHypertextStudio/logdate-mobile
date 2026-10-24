package app.logdate.client.sync.v2.window

import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.repository.moments.MarkedMoment
import app.logdate.client.sync.datalayer.v2.RecordKind
import app.logdate.client.sync.datalayer.v2.RecordOrigin
import app.logdate.client.sync.datalayer.v2.WindowBodyPaths
import app.logdate.client.sync.datalayer.v2.WindowBodyRequest
import app.logdate.client.sync.datalayer.v2.WindowBodyTicket
import app.logdate.client.sync.peer.delivery.MomentEnvelopeBody
import app.logdate.client.sync.peer.delivery.NoteEnvelopeBody
import app.logdate.client.sync.v2.receipts.PhoneDeliveryPeers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Tickets retain metadata only; a body is materialized only while its channel is served. */
class PhoneWindowBodyServer(
    private val peers: PhoneDeliveryPeers,
    private val notes: JournalNotesRepository,
    private val manifest: WindowManifestStore,
    private val temporaryDirectory: File,
    private val moments: app.logdate.client.repository.moments.MarkedMomentRepository? = null,
    private val acceptsMoments: suspend (app.logdate.client.sync.datalayer.v2.PeerDeliveryContext) -> Boolean = { false },
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Ticket(
        val request: WindowBodyRequest,
        val issuedAt: Long,
    )

    private val lock = Mutex()
    private val tickets = mutableMapOf<String, Ticket>()

    suspend fun prepare(
        node: String,
        request: WindowBodyRequest,
    ): WindowBodyTicket? {
        if (snapshot(node, request) == null) return null
        return lock.withLock {
            val now = nowMs()
            tickets.entries.removeAll { now - it.value.issuedAt > TICKET_LIFETIME_MS }
            if (tickets.size >= MAX_TICKETS || !current(request)) return@withLock null
            val path = WindowBodyPaths.CHANNEL_PREFIX + Uuid.random()
            tickets[path] = Ticket(request, now)
            WindowBodyTicket(request = request, channelPath = path)
        }
    }

    suspend fun serve(
        node: String,
        path: String,
        send: suspend (File, Long, Long, suspend () -> Boolean) -> Unit,
    ): Boolean {
        val ticket =
            lock.withLock {
                val found = tickets[path] ?: return@withLock null
                if (found.request.context.watchNode != node) return@withLock null
                tickets.remove(path)
            } ?: return false
        if (nowMs() - ticket.issuedAt > TICKET_LIFETIME_MS) return false
        val request = ticket.request
        val record = snapshot(node, request) ?: return false
        if (!temporaryDirectory.isDirectory && !temporaryDirectory.mkdirs()) return false
        val file = File.createTempFile("window-body-", ".json", temporaryDirectory)
        try {
            file.writeBytes(bodyBytes(record))

            suspend fun allowed(): Boolean = current(request) && readRecord(request) == record && current(request)
            if (!allowed()) return false
            send(file, request.offset, request.body.sizeBytes - request.offset) { allowed() }
            return allowed()
        } finally {
            file.delete()
        }
    }

    private suspend fun snapshot(
        node: String,
        request: WindowBodyRequest,
    ): Any? {
        if (request.kind !in setOf(RecordKind.NOTE, RecordKind.MOMENT) ||
            request.schema != 1 ||
            request.contentVersion < 0 ||
            request.revision <= 0 ||
            request.body.sizeBytes !in 1..WindowBodyPaths.MAX_BODY_BYTES ||
            request.offset !in 0..request.body.sizeBytes ||
            !request.body.sha256.matches(Regex("[0-9a-f]{64}"))
        ) {
            return null
        }
        if (peers.current(node) != request.context || !request.context.isValid() || !current(request)) return null
        val record = readRecord(request) ?: return null
        val bytes = bodyBytes(record)
        if (bytes.size.toLong() != request.body.sizeBytes || digest(bytes) != request.body.sha256) return null
        return record.takeIf { current(request) && readRecord(request) == record }
    }

    private suspend fun readRecord(request: WindowBodyRequest): Any? {
        val id = Uuid.parseOrNull(request.noteId)?.takeIf { it.toString() == request.noteId } ?: return null
        return when (request.kind) {
            RecordKind.NOTE -> {
                notes.getNoteById(id)?.takeIf {
                    it.uid == id &&
                        it.lastUpdated.toEpochMilliseconds() == request.contentVersion
                }
            }

            RecordKind.MOMENT -> {
                if (acceptsMoments(request.context)) {
                    moments?.getMark(id)?.takeIf { it.id == id && it.lastUpdated.toEpochMilliseconds() == request.contentVersion }
                } else {
                    null
                }
            }

            else -> {
                null
            }
        }
    }

    private suspend fun current(request: WindowBodyRequest): Boolean {
        if (!peers.isCurrent(request.context)) return false
        val published = manifest.read(request.context)
        val item =
            when (request.kind) {
                RecordKind.NOTE -> published.items[request.noteId]
                RecordKind.MOMENT -> published.moments[request.noteId].takeIf { acceptsMoments(request.context) }
                else -> null
            } ?: return false
        return item.contentVersion == request.contentVersion && item.revision == request.revision && peers.isCurrent(request.context)
    }

    private fun bodyBytes(record: Any): ByteArray =
        when (record) {
            is JournalNote -> NoteEnvelopeBody.encode(record)
            is MarkedMoment -> checkNotNull(MomentEnvelopeBody.envelope(record, RecordOrigin.PHONE).body)
            else -> error("Unsupported window body record")
        }.toString().encodeToByteArray()

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TICKET_LIFETIME_MS = 60_000L
        const val MAX_TICKETS = 128
    }
}
