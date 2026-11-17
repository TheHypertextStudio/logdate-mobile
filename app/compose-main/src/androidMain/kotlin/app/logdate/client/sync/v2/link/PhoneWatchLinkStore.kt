package app.logdate.client.sync.v2.link

import app.logdate.client.datastore.KeyValueStorage
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.peer.link.PairKeyVault
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Why the phone's last attempt to link a watch failed. [needsAttention] marks the ones only the person can fix. */
@Serializable
enum class PhoneLinkFailure(
    val needsAttention: Boolean,
) {
    PHONE_NODE_UNKNOWN(false),
    WATCH_UNREACHABLE(false),
    WATCH_CANNOT_LINK(false),
    OFFER_EXPIRED(false),
    ANSWER_UNREADABLE(false),
    CONFIRMATION_MISMATCH(false),
    WATCH_BOUND_TO_ANOTHER_ACCOUNT(true),
    BUNDLE_UNAVAILABLE(false),
    SIGNED_OUT(false),
    LINK_CODE_UNAVAILABLE(false),
    KEYS_UNAVAILABLE(false),
    DEVICE_LIMIT_REACHED(true),
    WATCH_KEY_CONFLICT(true),
    WATCH_INSTALL_FAILED(false),
}

/**
 * What the phone knows about linking one watch.
 *
 * @property seenWatchDocAt The `writtenAt` of the watch's state document the phone last acted on.
 */
@Serializable
data class PhoneWatchLink(
    val nodeId: String,
    val displayName: String,
    val mode: LinkMode,
    val accountId: String? = null,
    val serverOrigin: String? = null,
    val linkedAt: Long? = null,
    val confirmedAt: Long? = null,
    val failure: PhoneLinkFailure? = null,
    val attempts: Int = 0,
    val lastFailure: PhoneLinkFailure? = null,
    val attentionShown: Set<PhoneLinkFailure> = emptySet(),
    val lastAttemptAt: Long = 0,
    val nextAttemptAt: Long = 0,
    val seenWatchDocAt: Long = 0,
    val linkId: String? = null,
    val sessionId: String? = null,
)

/** One record per watch, as one JSON value, and each watch's pair key in secure storage. */
class PhoneWatchLinkStore(
    private val storage: KeyValueStorage,
    private val pairKeys: PairKeyVault,
    private val choices: KeyValueStorage = storage,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), PhoneWatchLink.serializer())
    private val mutex = Mutex()
    private val generations = mutableMapOf<String, Long>()

    suspend fun generation(nodeId: String): Long = mutex.withLock { generations[nodeId] ?: 0L }

    suspend fun isAttemptCurrent(
        nodeId: String,
        generation: Long,
    ): Boolean = mutex.withLock { (generations[nodeId] ?: 0L) == generation && !removalSuppressed(nodeId) }

    fun observe(): Flow<Map<String, PhoneWatchLink>> = storage.observeString(KEY).map(::decode).distinctUntilChanged()

    suspend fun get(nodeId: String): PhoneWatchLink? = decode(storage.getString(KEY))[nodeId]

    /** A copied success record cannot stand in for the installation's excluded pair key. */
    suspend fun getForReconciliation(nodeId: String): PhoneWatchLink? =
        mutex.withLock {
            val records = decode(storage.getStringOrThrow(KEY))
            val record = records[nodeId] ?: return@withLock null
            if (record.failure == null && record.linkedAt != null && pairKeys.get(nodeId)?.size != 32) {
                storage.putStringOrThrow(KEY, json.encodeToString(serializer, records - nodeId))
                null
            } else {
                record
            }
        }

    suspend fun recordSuccess(
        target: LinkTarget,
        plan: LinkPlan,
        pairKey: ByteArray,
        at: Long,
        seenWatchDocAt: Long,
        linkId: String? = null,
        generation: Long? = null,
    ): PhoneWatchLink? =
        mutex.withLock {
            if (removalSuppressed(target.nodeId) ||
                (generation != null && (generations[target.nodeId] ?: 0L) != generation)
            ) {
                return@withLock null
            }
            val previous = decode(storage.getStringOrThrow(KEY))[target.nodeId]
            val samePlan = previous.matches(plan)
            val success =
                PhoneWatchLink(
                    nodeId = target.nodeId,
                    displayName = target.displayName,
                    mode = plan.mode,
                    accountId = plan.accountId,
                    serverOrigin = plan.serverOrigin,
                    linkedAt = at,
                    confirmedAt =
                        previous?.confirmedAt?.takeIf {
                            previous.mode == plan.mode &&
                                previous.accountId == plan.accountId &&
                                previous.serverOrigin == plan.serverOrigin &&
                                previous.linkId == linkId
                        },
                    attempts = if (samePlan) previous?.attempts ?: 0 else 0,
                    lastFailure = previous?.lastFailure?.takeIf { samePlan },
                    attentionShown = if (samePlan) previous?.attentionShown.orEmpty() else emptySet(),
                    lastAttemptAt = at,
                    seenWatchDocAt = seenWatchDocAt,
                    linkId = linkId,
                )
            updateUnderLock(target.nodeId) {
                success.copy(
                    linkedAt = null,
                    confirmedAt = null,
                    failure = PhoneLinkFailure.KEYS_UNAVAILABLE,
                    attempts = maxOf(success.attempts, 1),
                    nextAttemptAt = at + FIRST_WAIT_MS,
                    linkId = null,
                    sessionId = null,
                )
            }
            pairKeys.put(target.nodeId, pairKey)
            updateUnderLock(target.nodeId) { success }
        }

    suspend fun recordFailure(
        target: LinkTarget,
        plan: LinkPlan,
        failure: PhoneLinkFailure,
        at: Long,
        seenWatchDocAt: Long,
        generation: Long? = null,
    ): PhoneWatchLink? =
        mutex.withLock {
            if (removalSuppressed(target.nodeId) ||
                (generation != null && (generations[target.nodeId] ?: 0L) != generation)
            ) {
                return@withLock null
            }
            updateUnderLock(target.nodeId) { previous ->
                val samePlan = previous.matches(plan)
                val attempts =
                    if (samePlan &&
                        (previous?.failure ?: previous?.lastFailure) == failure
                    ) {
                        checkNotNull(previous).attempts + 1
                    } else {
                        1
                    }
                PhoneWatchLink(
                    nodeId = target.nodeId,
                    displayName = target.displayName,
                    mode = plan.mode,
                    accountId = plan.accountId,
                    serverOrigin = plan.serverOrigin,
                    linkedAt = previous?.linkedAt?.takeIf { samePlan },
                    confirmedAt = previous?.confirmedAt?.takeIf { samePlan },
                    failure = failure,
                    lastFailure = failure,
                    attentionShown = if (samePlan) previous?.attentionShown.orEmpty() else emptySet(),
                    attempts = attempts,
                    lastAttemptAt = at,
                    nextAttemptAt = at + backoffMs(attempts),
                    seenWatchDocAt = maxOf(seenWatchDocAt, previous?.seenWatchDocAt ?: 0),
                    linkId = previous?.linkId?.takeIf { samePlan },
                )
            }
        }

    suspend fun markConfirmed(
        nodeId: String,
        at: Long,
        seenWatchDocAt: Long,
        expectedLinkId: String?,
        sessionId: String? = null,
    ): Boolean =
        mutex.withLock {
            val previous = decode(storage.getStringOrThrow(KEY))[nodeId] ?: return@withLock false
            if (removalSuppressed(nodeId) || previous.linkId != expectedLinkId || pairKeys.get(nodeId)?.size != 32) return@withLock false
            updateUnderLock(nodeId) {
                previous.copy(
                    confirmedAt = at,
                    sessionId = sessionId ?: previous.sessionId,
                    seenWatchDocAt = seenWatchDocAt,
                    attempts = 0,
                    lastFailure = null,
                    attentionShown = emptySet(),
                )
            }
            previous.confirmedAt == null
        }

    suspend fun recordSession(
        nodeId: String,
        linkId: String,
        sessionId: String,
    ) {
        mutex.withLock {
            val previous = decode(storage.getStringOrThrow(KEY))[nodeId] ?: return@withLock
            if (!removalSuppressed(nodeId) && previous.linkId == linkId) {
                updateUnderLock(nodeId) { previous.copy(sessionId = sessionId) }
            }
        }
    }

    suspend fun removalSuppressed(nodeId: String): Boolean = choices.getBooleanOrThrow("watch.removed.$nodeId")

    suspend fun allowRelink(nodeId: String) =
        mutex.withLock {
            generations[nodeId] = (generations[nodeId] ?: 0L) + 1
            choices.remove("watch.removed.$nodeId")
        }

    /**
     * Ends the wait after a failed attempt, so the next reconciliation links [nodeId] again at once. The
     * person asked to set the watch up, and a retry they ask for must not sit behind a wait of up to six hours.
     */
    suspend fun retryNow(nodeId: String) =
        mutex.withLock {
            val record = decode(storage.getStringOrThrow(KEY))[nodeId] ?: return@withLock
            if (record.failure == null || record.nextAttemptAt == 0L) return@withLock
            updateUnderLock(nodeId) { record.copy(nextAttemptAt = 0L) }
        }

    suspend fun forget(nodeId: String) =
        mutex.withLock {
            generations[nodeId] = (generations[nodeId] ?: 0L) + 1
            choices.putBoolean("watch.removed.$nodeId", true)
            check(choices.getBooleanOrThrow("watch.removed.$nodeId"))
            val records = decode(storage.getStringOrThrow(KEY)) - nodeId
            storage.putStringOrThrow(KEY, json.encodeToString(serializer, records))
            check(decode(storage.getString(KEY))[nodeId] == null)
            pairKeys.remove(nodeId)
        }

    suspend fun claimAttention(
        nodeId: String,
        failure: PhoneLinkFailure,
    ): Boolean {
        var claimed = false
        mutex.withLock {
            val record = decode(storage.getStringOrThrow(KEY))[nodeId] ?: return@withLock
            if (removalSuppressed(nodeId) || record.failure != failure) return@withLock
            claimed = failure !in record.attentionShown
            updateUnderLock(nodeId) { record.copy(attentionShown = record.attentionShown + failure) }
        }
        return claimed
    }

    private fun PhoneWatchLink?.matches(plan: LinkPlan): Boolean =
        this != null && mode == plan.mode && accountId == plan.accountId && serverOrigin == plan.serverOrigin

    private suspend fun updateUnderLock(
        nodeId: String,
        change: (PhoneWatchLink?) -> PhoneWatchLink,
    ): PhoneWatchLink {
        val all = decode(storage.getStringOrThrow(KEY))
        val changed = change(all[nodeId])
        storage.putStringOrThrow(KEY, json.encodeToString(serializer, all + (nodeId to changed)))
        return changed
    }

    private fun decode(text: String?): Map<String, PhoneWatchLink> {
        if (text == null) return emptyMap()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            Napier.e("The phone's watch link records could not be read; each watch is linked again", e)
            emptyMap()
        }
    }

    private fun backoffMs(attempts: Int): Long = minOf(FIRST_WAIT_MS shl (attempts - 1).coerceIn(0, MAX_DOUBLINGS), MAX_WAIT_MS)

    private companion object {
        const val KEY = "phone.watch_links.v1"
        const val FIRST_WAIT_MS = 60_000L
        const val MAX_DOUBLINGS = 9
        const val MAX_WAIT_MS = 6L * 60 * 60 * 1000
    }
}
