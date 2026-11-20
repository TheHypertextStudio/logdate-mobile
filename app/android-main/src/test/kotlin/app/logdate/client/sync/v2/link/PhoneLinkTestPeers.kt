package app.logdate.client.sync.v2.link

import app.logdate.client.device.crypto.link.LinkHandshake
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkAccept
import app.logdate.client.sync.datalayer.v2.LinkBundle
import app.logdate.client.sync.datalayer.v2.LinkConfirm
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.LinkNonceLedger
import app.logdate.client.sync.datalayer.v2.LinkOffer
import app.logdate.client.sync.datalayer.v2.LinkRefusal
import app.logdate.client.sync.datalayer.v2.SealedLinkBundle
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.peer.link.WatchLinkExchange
import app.logdate.client.sync.peer.link.valueOrNull
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant

/** The watch's side of the exchange, answering the way the watch app does. */
internal class FakeWatch(
    handshake: LinkHandshake,
    clock: Clock,
) : LinkRequester {
    private val exchange = WatchLinkExchange(handshake, LinkNonceLedger(clock), clock)
    private var accepted: WatchLinkExchange.Accepted? = null
    val requests = mutableListOf<String>()
    var reachable = true
    var refuseOffersWith: LinkRefusal? = null
    var corruptConfirmation = false
    var pairKey: ByteArray? = null
    var linkId: String? = null
    var afterOffer: suspend () -> Unit = {}

    override suspend fun request(
        nodeId: String,
        path: String,
        payload: ByteArray,
    ): Result<ByteArray> {
        requests += path
        if (!reachable) return Result.failure(IOException("The watch is out of reach"))
        return Result.success(
            when (path) {
                WearPaths.LINK_OFFER -> answerOffer(nodeId, payload).also { afterOffer() }
                WearPaths.LINK_BUNDLE -> answerBundle(payload)
                else -> error("Unexpected request $path")
            },
        )
    }

    private suspend fun answerOffer(
        nodeId: String,
        payload: ByteArray,
    ): ByteArray {
        refuseOffersWith?.let { return LinkWire.refusal(it) }
        val offer = (WearJson.decode(payload, WearProtocol.LINK_SCHEMA, LinkOffer.serializer()) as Decoded.Supported).value
        val step = checkNotNull(exchange.accept(offer, watchNode = nodeId, phoneNode = offer.phoneNode).valueOrNull())
        accepted = step
        return LinkWire.write(LinkAccept.serializer(), step.reply)
    }

    private fun answerBundle(payload: ByteArray): ByteArray {
        val sealed = (WearJson.decode(payload, WearProtocol.LINK_SCHEMA, SealedLinkBundle.serializer()) as Decoded.Supported).value
        val opened = checkNotNull(exchange.open(checkNotNull(accepted), sealed).valueOrNull())
        pairKey = opened.pairKey
        linkId = opened.linkId
        val confirm = if (corruptConfirmation) LinkConfirm(mac = LinkWire.encodeKey(ByteArray(32))) else opened.confirm
        return LinkWire.write(LinkConfirm.serializer(), confirm)
    }
}

internal class RecordingNotifier : WatchLinkNotifier {
    val events = mutableListOf<String>()

    override fun linked(
        target: LinkTarget,
        mode: LinkMode,
    ) {
        events += "linked ${target.displayName} $mode"
    }

    override fun needsAttention(
        target: LinkTarget,
        failure: PhoneLinkFailure,
    ) {
        events += "attention ${target.displayName} $failure"
    }
}

internal class MutableClock(
    var millis: Long,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

internal class TestCloudLinkBundleSource(
    private val account: String,
) : LinkBundleSource {
    override fun changes() = kotlinx.coroutines.flow.flowOf(Unit)

    override suspend fun plan(watchFeatures: Set<String>) = LinkPlan(LinkMode.CLOUD, account)

    override suspend fun bundle(plan: LinkPlan) = LinkBundleResult.Ready(LinkBundle(mode = LinkMode.CLOUD, accountId = account))
}

/** An account whose server can give no answer, as when it is down or out of reach, or refuse the link. */
internal class UnsteadyCloudLinkBundleSource(
    private val account: String,
) : LinkBundleSource {
    var signedIn = true
    var serverAnswers = false
    var serverRefuses = false

    override fun changes() = kotlinx.coroutines.flow.flowOf(Unit)

    override suspend fun plan(watchFeatures: Set<String>) = if (signedIn) LinkPlan(LinkMode.CLOUD, account) else LinkPlan(LinkMode.PAIR)

    override suspend fun bundle(plan: LinkPlan): LinkBundleResult =
        when {
            plan.mode == LinkMode.PAIR -> LinkBundleResult.Ready(LinkBundle(mode = LinkMode.PAIR))
            serverAnswers -> LinkBundleResult.Ready(LinkBundle(mode = LinkMode.CLOUD, accountId = account))
            else -> LinkBundleResult.Unavailable(PhoneLinkFailure.LINK_CODE_UNAVAILABLE, serverUnreachable = !serverRefuses)
        }
}

internal const val PHONE_LINK_TEST_ACCOUNT = "5f0c3a52-0000-4000-8000-000000000001"
internal const val PHONE_LINK_TEST_PHONE = "phone-node"
internal const val PHONE_LINK_TEST_WATCH = "watch-node"

internal class RecoverableLinkPreferences(
    private val storage: app.logdate.client.sync.v2.MemoryKeyValueStorage =
        app.logdate.client.sync.v2
            .MemoryKeyValueStorage(),
) : app.logdate.client.datastore.KeyValueStorage by storage {
    var readFailure: IOException? = null
    var breakObservationOnce = false

    override suspend fun getStringOrThrow(key: String): String? {
        readFailure?.let { throw it }
        return storage.getStringOrThrow(key)
    }

    override fun observeString(key: String): kotlinx.coroutines.flow.Flow<String?> =
        kotlinx.coroutines.flow.flow {
            if (breakObservationOnce) {
                breakObservationOnce = false
                emit(null)
            } else {
                storage.observeString(key).collect { emit(it) }
            }
        }
}
