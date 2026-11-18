package app.logdate.wear.sync.v2.link

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.peer.link.LinkProblem
import app.logdate.client.sync.peer.link.PairKeyVault
import app.logdate.client.sync.peer.state.PeerLinkState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * The phone this watch is linked with and how.
 *
 * @property stale The watch moved to another phone since; it waits for that phone to link it again.
 * @property problem Why the last account link failed, as a [LinkProblem] name.
 * @property installing The pair is confirmed, but account installation is still pending.
 * @property serverOrigin The server supplied by account setup; older records require relinking.
 * @property linkId Identifies the verified exchange, so redelivery cannot cross a relink.
 */
@Serializable
data class WatchLinkRecord(
    val mode: LinkMode,
    val phoneNode: String,
    val linkedAt: Long,
    val stale: Boolean = false,
    val accountId: String? = null,
    val problem: String? = null,
    val installing: Boolean = false,
    val serverOrigin: String? = null,
    val linkId: String? = null,
    val sessionId: String? = null,
)

/** What the watch publishes about its link in its state document. */
fun WatchLinkRecord?.peerLinkState(): PeerLinkState {
    if (this == null) return PeerLinkState.UNLINKED
    if (stale || problem != null) return PeerLinkState.NEEDS_RELINK
    if (installing) return PeerLinkState.PAIRED
    return when (mode) {
        LinkMode.PAIR -> PeerLinkState.PAIRED
        LinkMode.CLOUD -> PeerLinkState.LINKED
    }
}

/** The watch's link record, as one JSON value, and the pair key in secure storage. */
class WatchLinkStore(
    private val storage: KeyValueStorage,
    private val pairKeys: PairKeyVault,
    private val clock: Clock = Clock.System,
    private val onLinked: suspend () -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observe(): Flow<WatchLinkRecord?> = storage.observeString(KEY).map(::decode).distinctUntilChanged()

    suspend fun current(): WatchLinkRecord? = decode(storage.getString(KEY))

    suspend fun savePairing(
        phoneNode: String,
        pairKey: ByteArray,
        linkId: String? = null,
    ) {
        pairKeys.put(phoneNode, pairKey)
        write(
            WatchLinkRecord(
                mode = LinkMode.PAIR,
                phoneNode = phoneNode,
                linkedAt = clock.now().toEpochMilliseconds(),
                linkId = linkId,
            ),
        )
    }

    /** Persists the exchange's phone before confirming; account setup has not finished yet. */
    suspend fun beginCloudInstall(
        phoneNode: String,
        pairKey: ByteArray,
        accountId: String?,
        serverOrigin: String? = null,
        linkId: String? = null,
    ) {
        savePairKey(phoneNode, pairKey)
        write(
            WatchLinkRecord(
                LinkMode.CLOUD,
                phoneNode,
                linkedAt = 0,
                accountId = accountId,
                installing = true,
                serverOrigin = serverOrigin,
                linkId = linkId,
            ),
        )
    }

    /** Keeps the pair key from an account link before its install finishes. */
    suspend fun savePairKey(
        phoneNode: String,
        pairKey: ByteArray,
    ) = pairKeys.put(phoneNode, pairKey)

    suspend fun saveCloudLink(
        phoneNode: String,
        accountId: String,
        serverOrigin: String? = null,
        linkId: String? = null,
        sessionId: String? = null,
    ) {
        write(
            WatchLinkRecord(
                mode = LinkMode.CLOUD,
                phoneNode = phoneNode,
                linkedAt = clock.now().toEpochMilliseconds(),
                accountId = accountId,
                serverOrigin = serverOrigin,
                linkId = linkId,
                sessionId = sessionId,
            ),
        )
    }

    /** Records why an account link failed; the watch then asks its phone to link it again. */
    suspend fun recordProblem(
        phoneNode: String?,
        problem: LinkProblem,
    ) {
        val previous = current()
        val node = phoneNode ?: previous?.phoneNode.orEmpty()
        val record =
            previous?.copy(phoneNode = node, problem = problem.name, installing = false)
                ?: WatchLinkRecord(mode = LinkMode.CLOUD, phoneNode = node, linkedAt = 0, problem = problem.name)
        write(record)
    }

    /**
     * Records that this watch's own sign-in ran out, so its phone links it again. False when the watch has
     * no working account link to renew.
     */
    suspend fun markSessionExpired(): Boolean {
        val record = current() ?: return false
        if (record.mode != LinkMode.CLOUD || record.stale || record.installing || record.problem != null) return false
        write(record.copy(problem = LinkProblem.SESSION_EXPIRED.name))
        return true
    }

    /** Marks the link with [phoneNode] stale; false when the watch is not linked with that phone. */
    suspend fun markStale(phoneNode: String): Boolean {
        val record = current() ?: return false
        if (record.phoneNode != phoneNode || record.stale) return false
        write(record.copy(stale = true, installing = false))
        return true
    }

    private suspend fun write(record: WatchLinkRecord) {
        storage.putString(KEY, json.encodeToString(WatchLinkRecord.serializer(), record))
        if (!record.stale && record.problem == null) onLinked()
    }

    private fun decode(text: String?): WatchLinkRecord? {
        if (text == null) return null
        return try {
            json.decodeFromString(WatchLinkRecord.serializer(), text)
        } catch (e: SerializationException) {
            Napier.e("This watch's link record could not be read; it reports no link so the phone links it again", e)
            null
        } catch (e: IllegalArgumentException) {
            Napier.e("This watch's link record could not be read; it reports no link so the phone links it again", e)
            null
        }
    }

    private companion object {
        const val KEY = "watch.link.record.v1"
    }
}
