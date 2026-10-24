package app.logdate.wear.sync.v2.link

import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.LinkAccept
import app.logdate.client.sync.datalayer.v2.LinkConfirm
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.LinkOffer
import app.logdate.client.sync.datalayer.v2.LinkRefusal
import app.logdate.client.sync.datalayer.v2.SealedLinkBundle
import app.logdate.client.sync.datalayer.v2.WearJson
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.datalayer.v2.WearProtocol
import app.logdate.client.sync.peer.link.InstallOutcome
import app.logdate.client.sync.peer.link.LinkExchangeFailure
import app.logdate.client.sync.peer.link.LinkInstaller
import app.logdate.client.sync.peer.link.LinkProblem
import app.logdate.client.sync.peer.link.LinkStep
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.peer.link.WatchLinkExchange
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The watch's side of linking. The phone always opens; this answers its offer and its bundle, records
 * the link, and marks it stale when the watch moves to another phone.
 *
 * One exchange runs at a time and a new offer replaces one that never reached its bundle. Every
 * refusal is logged with the reason the phone receives.
 */
class WatchLinkCoordinator(
    private val exchange: WatchLinkExchange,
    private val store: WatchLinkStore,
    private val v2Enabled: suspend () -> Boolean,
    private val localNodeId: suspend () -> String?,
    private val installer: LinkInstaller? = null,
    private val installScope: CoroutineScope? = null,
    private val beforeInstall: suspend () -> Unit = {},
    /** Runs once an account link is installed: the watch reads its account from the server. */
    private val afterInstall: suspend () -> Unit = {},
) {
    /** Finishes an account link a stopped process left after the code was spent. */
    suspend fun resumeInstall() {
        mutex.withLock {
            val phoneNode = store.current()?.phoneNode
            val outcome =
                try {
                    installer?.resume()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Napier.e("Resuming account setup failed", e)
                    InstallOutcome.Failed(LinkProblem.INTERRUPTED)
                }
            if (outcome != null) {
                record(phoneNode, outcome)
            } else if (store.current()?.installing == true) {
                record(phoneNode, InstallOutcome.Failed(LinkProblem.INTERRUPTED))
            }
        }
    }

    private val mutex = Mutex()
    private var pending: Pending? = null
    private var installation: Job? = null

    private class Pending(
        val phoneNode: String,
        val accepted: WatchLinkExchange.Accepted,
    )

    /** The answer to a request at [path], or null when [path] is not a link request. */
    suspend fun answer(
        sourceNodeId: String,
        path: String,
        payload: ByteArray,
    ): ByteArray? =
        when (path) {
            WearPaths.LINK_OFFER -> mutex.withLock { answerOffer(sourceNodeId, payload) }
            WearPaths.LINK_BUNDLE -> mutex.withLock { answerBundle(sourceNodeId, payload) }
            else -> null
        }

    suspend fun onNodeMigrated(oldNodeId: String) {
        mutex.withLock {
            if (pending?.phoneNode == oldNodeId) pending = null
            if (store.markStale(oldNodeId)) {
                Napier.w("This watch moved to another phone; it waits for that phone to link it again")
            }
        }
    }

    private suspend fun answerOffer(
        sourceNodeId: String,
        payload: ByteArray,
    ): ByteArray {
        if (!v2Enabled()) return refuse(LinkRefusal.UNSUPPORTED, "the redesigned watch app is off")
        if (installation?.isActive == true || store.current()?.installing == true) {
            return refuse(LinkRefusal.UNSUPPORTED, "account setup is still finishing")
        }
        val offer =
            when (val decoded = WearJson.decode(payload, WearProtocol.LINK_SCHEMA, LinkOffer.serializer())) {
                is Decoded.Supported -> decoded.value
                is Decoded.Unsupported -> return refuse(LinkRefusal.UNSUPPORTED, "the offer uses schema ${decoded.schema}")
                is Decoded.Malformed -> return refuse(LinkRefusal.UNSUPPORTED, decoded.reason)
            }
        refusePairDowngrade(offer.mode)?.let { return it }
        if (!acceptsMode(offer.mode)) return refuse(LinkRefusal.UNSUPPORTED, "this watch build cannot link to an account")
        val watchNode = localNodeId() ?: return refuse(LinkRefusal.UNSUPPORTED, "this watch's node is unknown")
        return when (val step = exchange.accept(offer, watchNode = watchNode, phoneNode = sourceNodeId)) {
            is LinkStep.Ok -> {
                pending = Pending(sourceNodeId, step.value)
                LinkWire.write(LinkAccept.serializer(), step.value.reply)
            }

            is LinkStep.Failed -> {
                refuse(step.failure.toRefusal(), "the offer failed its ${step.failure} check")
            }
        }
    }

    private suspend fun answerBundle(
        sourceNodeId: String,
        payload: ByteArray,
    ): ByteArray {
        if (!v2Enabled()) {
            pending = null
            return refuse(LinkRefusal.UNSUPPORTED, "the redesigned watch app is off")
        }
        val current =
            pending?.takeIf { it.phoneNode == sourceNodeId }
                ?: return refuse(LinkRefusal.EXPIRED_OFFER, "no offer from this phone is open")
        pending = null
        val sealed =
            when (val decoded = WearJson.decode(payload, WearProtocol.LINK_SCHEMA, SealedLinkBundle.serializer())) {
                is Decoded.Supported -> decoded.value
                is Decoded.Unsupported -> return refuse(LinkRefusal.UNSUPPORTED, "the bundle uses schema ${decoded.schema}")
                is Decoded.Malformed -> return refuse(LinkRefusal.UNSUPPORTED, decoded.reason)
            }
        val opened =
            when (val step = exchange.open(current.accepted, sealed)) {
                is LinkStep.Ok -> step.value
                is LinkStep.Failed -> return refuse(step.failure.toRefusal(), "the bundle failed its ${step.failure} check")
            }
        refusePairDowngrade(opened.bundle.mode)?.let { return it }
        return when (opened.bundle.mode) {
            LinkMode.PAIR -> {
                store.savePairing(sourceNodeId, opened.pairKey, opened.linkId)
                Napier.i("This watch is paired with its phone")
                LinkWire.write(LinkConfirm.serializer(), opened.confirm)
            }

            LinkMode.CLOUD -> {
                linkAccount(sourceNodeId, opened)
            }
        }
    }

    private suspend fun linkAccount(
        phoneNode: String,
        opened: WatchLinkExchange.Opened,
    ): ByteArray {
        val installer = installer
        val scope = installScope
        if (installer == null || scope == null) return refuse(LinkRefusal.UNSUPPORTED, "this watch build cannot link to an account")
        installer.bindingProblem(opened.bundle)?.let { problem ->
            return refuse(problem.toRefusal(), "the account bundle cannot be installed: $problem")
        }
        if (!v2Enabled() || !scope.isActive) return refuse(LinkRefusal.UNSUPPORTED, "account setup is no longer active")
        store.beginCloudInstall(phoneNode, opened.pairKey, opened.bundle.accountId, opened.bundle.serverOrigin, opened.linkId)
        installation = scope.launch { install(installer, phoneNode, opened) }
        Napier.i("Confirmed the phone's account link; installing it now")
        return LinkWire.write(LinkConfirm.serializer(), opened.confirm)
    }

    private suspend fun install(
        installer: LinkInstaller,
        phoneNode: String,
        opened: WatchLinkExchange.Opened,
    ) {
        val outcome =
            try {
                beforeInstall()
                installer.install(opened.bundle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Napier.e("Installing the phone's account link failed part way; the phone links this watch again", e)
                InstallOutcome.Failed(LinkProblem.INTERRUPTED)
            }
        mutex.withLock { record(phoneNode, outcome) }
        if (outcome is InstallOutcome.Installed) afterInstall()
    }

    private suspend fun record(
        phoneNode: String?,
        outcome: InstallOutcome,
    ) {
        val current = store.current()
        if (current?.stale == true || (phoneNode != null && current != null && current.phoneNode != phoneNode)) return
        when (outcome) {
            is InstallOutcome.Installed -> {
                store.saveCloudLink(phoneNode.orEmpty(), outcome.accountId, outcome.origin, current?.linkId, outcome.sessionId)
                Napier.i("This watch now syncs with its phone's account")
            }

            is InstallOutcome.Failed -> {
                store.recordProblem(phoneNode, outcome.problem)
                Napier.w(
                    "Linking this watch to its phone's account failed with ${outcome.problem}; the phone sees it in this watch's state",
                )
            }
        }
    }

    private fun LinkProblem.toRefusal(): LinkRefusal =
        when (this) {
            LinkProblem.BOUND_TO_ANOTHER_ACCOUNT, LinkProblem.KEY_CONFLICT -> LinkRefusal.BOUND_TO_ANOTHER_ACCOUNT
            LinkProblem.LINK_CODE_EXPIRED -> LinkRefusal.EXPIRED_OFFER
            else -> LinkRefusal.UNSUPPORTED
        }

    private suspend fun refusePairDowngrade(mode: LinkMode): ByteArray? {
        if (mode != LinkMode.PAIR) return null
        val current = store.current() ?: return null
        if (current.mode != LinkMode.CLOUD && current.accountId == null) return null
        return refuse(LinkRefusal.BOUND_TO_ANOTHER_ACCOUNT, "erase the existing account setup before pairing without an account")
    }

    private fun acceptsMode(mode: LinkMode): Boolean = mode == LinkMode.PAIR || (installer != null && installScope != null)

    private fun refuse(
        reason: LinkRefusal,
        why: String,
    ): ByteArray {
        Napier.w("Refused the phone's link request with $reason: $why")
        return LinkWire.refusal(reason)
    }

    private fun LinkExchangeFailure.toRefusal(): LinkRefusal =
        when (this) {
            LinkExchangeFailure.EXPIRED_OFFER -> LinkRefusal.EXPIRED_OFFER
            LinkExchangeFailure.REPLAYED_NONCE -> LinkRefusal.REPLAYED_NONCE
            else -> LinkRefusal.UNSUPPORTED
        }

    companion object {
        fun handles(path: String): Boolean = path == WearPaths.LINK_OFFER || path == WearPaths.LINK_BUNDLE
    }
}
