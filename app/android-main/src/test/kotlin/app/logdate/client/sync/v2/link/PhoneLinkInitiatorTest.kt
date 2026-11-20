package app.logdate.client.sync.v2.link

import app.logdate.client.device.crypto.link.BouncyCastleLinkHandshake
import app.logdate.client.sync.datalayer.v2.LinkBundle
import app.logdate.client.sync.datalayer.v2.LinkMode
import app.logdate.client.sync.datalayer.v2.LinkRefusal
import app.logdate.client.sync.datalayer.v2.WearPaths
import app.logdate.client.sync.peer.link.LinkWire
import app.logdate.client.sync.peer.link.PhoneLinkExchange
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.StateDocument
import app.logdate.client.sync.peer.state.WatchState
import app.logdate.client.sync.v2.MemoryPairKeyVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.logdate.client.sync.v2.link.PHONE_LINK_TEST_ACCOUNT as ACCOUNT
import app.logdate.client.sync.v2.link.PHONE_LINK_TEST_PHONE as PHONE
import app.logdate.client.sync.v2.link.PHONE_LINK_TEST_WATCH as WATCH

/**
 * Tests [PhoneLinkInitiator] against the watch's half of the real exchange: a watch is linked once,
 * every failure is recorded with its reason and waits, and only a working link is confirmed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneLinkInitiatorTest {
    private val cloud = TestCloudLinkBundleSource(ACCOUNT)
    private val unsteady = UnsteadyCloudLinkBundleSource(ACCOUNT)

    @Test
    fun `a restored linked record without its local pair key starts a fresh exchange`() =
        runTest {
            val initiator = initiator()
            initiator.reconcile(emptyMap())
            val oldLink = assertNotNull(store.get(WATCH)?.linkId)
            pairKeys.remove(WATCH)

            initiator.reconcile(mapOf(WATCH to watchDocument(PeerLinkState.PAIRED, null, clock.millis + 1)))

            assertEquals(4, watch.requests.size)
            assertTrue(oldLink != store.get(WATCH)?.linkId)
            assertContentEquals(assertNotNull(watch.pairKey), pairKeys.keys[WATCH])
        }

    @Test
    fun `a failed repair waits before retrying the missing pair key`() =
        runTest {
            val initiator = initiator()
            initiator.reconcile(emptyMap())
            pairKeys.remove(WATCH)
            watch.reachable = false

            initiator.reconcile(emptyMap())
            initiator.reconcile(emptyMap())

            assertEquals(3, watch.requests.size)
            assertEquals(PhoneLinkFailure.WATCH_UNREACHABLE, store.get(WATCH)?.failure)
            clock.millis += 60_000
            initiator.reconcile(emptyMap())
            assertEquals(4, watch.requests.size)
        }

    @Test
    fun `forgetting a watch during the exchange prevents local trust resurrection`() =
        runTest {
            watch.afterOffer = { store.forget(WATCH) }

            initiator().reconcile(emptyMap())

            assertTrue(store.removalSuppressed(WATCH))
            assertNull(store.get(WATCH))
            assertNull(pairKeys.keys[WATCH])
            assertTrue(notifier.events.isEmpty())
        }

    @Test
    fun `reallowing a forgotten watch cannot accept the old in flight exchange`() =
        runTest {
            watch.afterOffer = {
                store.forget(WATCH)
                store.allowRelink(WATCH)
            }
            initiator().reconcile(emptyMap())
            assertNull(store.get(WATCH))
            assertNull(pairKeys.keys[WATCH])
            assertTrue(notifier.events.isEmpty())
        }

    @Test
    fun `temporary storage failure retries a quiet watch without another document event`() =
        runTest {
            preferences.readFailure = IOException("temporarily unavailable")
            val job = initiator().start(backgroundScope)
            runCurrent()
            assertTrue(job.isActive)
            assertTrue(watch.requests.isEmpty())
            preferences.readFailure = null
            clock.millis += 60_000
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, watch.requests.size)
            assertNotNull(pairKeys.get(WATCH))
        }

    @Test
    fun `a completed storage observation restarts and still schedules watch retries`() =
        runTest {
            preferences.breakObservationOnce = true
            watch.reachable = false
            val job = initiator().start(backgroundScope)
            runCurrent()
            assertTrue(job.isActive)
            assertEquals(1, watch.requests.size)
            clock.millis += 60_000
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, watch.requests.size)
        }

    @Test
    fun `the phone stores the same link identity as the watch`() =
        runTest {
            initiator().reconcile(emptyMap())
            val linkId = assertNotNull(store.get(WATCH)?.linkId)
            assertEquals(32, LinkWire.decodeKey(linkId)?.size)
            assertEquals(watch.linkId, linkId)
        }

    @Test
    fun `an account link is confirmed only once the watch reports it`() =
        runTest {
            val initiator = initiator(bundles = cloud)
            initiator.reconcile(emptyMap())
            assertTrue(notifier.events.isEmpty())

            initiator.reconcile(mapOf(WATCH to watchDocument(PeerLinkState.LINKED, problem = null, at = clock.millis + 1)))

            assertEquals(listOf("linked Pixel Watch 3 CLOUD"), notifier.events)
        }

    @Test
    fun `a watch that could not install the link is recorded and shown once`() =
        runTest {
            val initiator = initiator(bundles = cloud)
            initiator.reconcile(emptyMap())
            val failed =
                mapOf(
                    WATCH to
                        watchDocument(PeerLinkState.NEEDS_RELINK, problem = "DEVICE_LIMIT_REACHED", at = clock.millis + 1),
                )

            initiator.reconcile(failed)
            initiator.reconcile(failed)

            assertEquals(PhoneLinkFailure.DEVICE_LIMIT_REACHED, store.get(WATCH)?.failure)
            assertEquals(listOf("attention Pixel Watch 3 DEVICE_LIMIT_REACHED"), notifier.events)
            assertEquals(2, watch.requests.size)
        }

    @Test
    fun `repeated account install failures grow the wait without repeating attention`() =
        runTest {
            val initiator = initiator(bundles = cloud)
            initiator.reconcile(emptyMap())
            initiator.reconcile(
                mapOf(
                    WATCH to watchDocument(PeerLinkState.NEEDS_RELINK, "DEVICE_LIMIT_REACHED", clock.millis + 1),
                ),
            )
            val first = assertNotNull(store.get(WATCH))
            clock.millis = first.nextAttemptAt
            initiator.reconcile(emptyMap())
            initiator.reconcile(
                mapOf(
                    WATCH to watchDocument(PeerLinkState.NEEDS_RELINK, "DEVICE_LIMIT_REACHED", clock.millis + 1),
                ),
            )
            val second = assertNotNull(store.get(WATCH))

            assertEquals(2, second.attempts)
            assertEquals(clock.millis + 120_000, second.nextAttemptAt)
            assertEquals(listOf("attention Pixel Watch 3 DEVICE_LIMIT_REACHED"), notifier.events)
        }

    @Test
    fun `a working account link clears the install retry history`() =
        runTest {
            val initiator = initiator(bundles = cloud)
            initiator.reconcile(emptyMap())
            initiator.reconcile(
                mapOf(
                    WATCH to watchDocument(PeerLinkState.NEEDS_RELINK, "DEVICE_LIMIT_REACHED", clock.millis + 1),
                ),
            )
            clock.millis = assertNotNull(store.get(WATCH)).nextAttemptAt
            initiator.reconcile(emptyMap())
            initiator.reconcile(mapOf(WATCH to watchDocument(PeerLinkState.LINKED, null, clock.millis + 1)))

            assertEquals(0, store.get(WATCH)?.attempts)
            assertEquals(listOf("attention Pixel Watch 3 DEVICE_LIMIT_REACHED", "linked Pixel Watch 3 CLOUD"), notifier.events)
        }

    private fun watchDocument(
        link: PeerLinkState,
        problem: String?,
        at: Long,
    ) = StateDocument(role = PeerRole.WATCH, writtenAt = at, watch = WatchState(link = link, linkProblem = problem, linkId = watch.linkId))

    private val clock = MutableClock(1_000_000)
    private val handshake = BouncyCastleLinkHandshake()
    private val watch = FakeWatch(handshake, clock)
    private val pairKeys = MemoryPairKeyVault()
    private val preferences = RecoverableLinkPreferences()
    private val store = PhoneWatchLinkStore(preferences, pairKeys)
    private val notifier = RecordingNotifier()
    private val enabledFlow = MutableStateFlow(true)
    private val capableFlow = MutableStateFlow(true)
    private var bundleSource: LinkBundleSource = PairOnlyBundleSource

    private fun initiator(
        enabled: Boolean = true,
        bundles: LinkBundleSource = bundleSource,
    ) = PhoneLinkInitiator(
        v2Enabled = enabledFlow.also { it.value = enabled },
        watchCapable = capableFlow,
        watchDocuments = MutableStateFlow(emptyMap()),
        targets = { listOf(LinkTarget(WATCH, "Pixel Watch 3")) },
        requester = watch,
        exchange = PhoneLinkExchange(handshake, clock),
        store = store,
        notifier = notifier,
        localNodeId = { PHONE },
        bundles = bundles,
        clock = clock,
    )

    @Test
    fun `a watch that was never linked is paired once and the phone shows one confirmation`() =
        runTest {
            val initiator = initiator()

            initiator.reconcile(emptyMap())
            initiator.reconcile(emptyMap())

            assertEquals(listOf(WearPaths.LINK_OFFER, WearPaths.LINK_BUNDLE), watch.requests)
            assertEquals(listOf("linked Pixel Watch 3 PAIR"), notifier.events)
            val record = assertNotNull(store.get(WATCH))
            assertNull(record.failure)
            assertNotNull(record.confirmedAt)
            assertContentEquals(assertNotNull(watch.pairKey), pairKeys.keys[WATCH])
        }

    @Test
    fun `a refused offer is recorded with its reason and tried again only after the wait`() =
        runTest {
            watch.refuseOffersWith = LinkRefusal.UNSUPPORTED
            val initiator = initiator()

            initiator.reconcile(emptyMap())
            initiator.reconcile(emptyMap())

            assertEquals(PhoneLinkFailure.WATCH_CANNOT_LINK, store.get(WATCH)?.failure)
            assertEquals(1, watch.requests.size)
            clock.millis += 60_000
            initiator.reconcile(emptyMap())
            assertEquals(2, watch.requests.size)
        }

    @Test
    fun `asking to set up the watch again retries at once instead of waiting`() =
        runTest {
            watch.reachable = false
            val initiator = initiator()
            initiator.reconcile(emptyMap())
            assertEquals(PhoneLinkFailure.WATCH_UNREACHABLE, store.get(WATCH)?.failure)
            watch.reachable = true

            initiator.reconcile(emptyMap())
            assertEquals(1, watch.requests.size)
            store.retryNow(WATCH)
            initiator.reconcile(emptyMap())

            assertEquals(3, watch.requests.size)
            assertNull(store.get(WATCH)?.failure)
            assertEquals(listOf("linked Pixel Watch 3 PAIR"), notifier.events)
        }

    @Test
    fun `retrying a working link changes nothing`() =
        runTest {
            val initiator = initiator()
            initiator.reconcile(emptyMap())
            val linked = store.get(WATCH)

            store.retryNow(WATCH)
            initiator.reconcile(emptyMap())

            assertEquals(linked, store.get(WATCH))
            assertEquals(2, watch.requests.size)
        }

    @Test
    fun `a watch set up for another account is recorded and shown once`() =
        runTest {
            watch.refuseOffersWith = LinkRefusal.BOUND_TO_ANOTHER_ACCOUNT
            val initiator = initiator()

            initiator.reconcile(emptyMap())
            clock.millis += 60_000
            initiator.reconcile(emptyMap())

            assertEquals(PhoneLinkFailure.WATCH_BOUND_TO_ANOTHER_ACCOUNT, store.get(WATCH)?.failure)
            assertEquals(listOf("attention Pixel Watch 3 WATCH_BOUND_TO_ANOTHER_ACCOUNT"), notifier.events)
        }

    @Test
    fun `an unreachable watch is recorded as unreachable`() =
        runTest {
            watch.reachable = false

            initiator().reconcile(emptyMap())

            assertEquals(PhoneLinkFailure.WATCH_UNREACHABLE, store.get(WATCH)?.failure)
            assertTrue(notifier.events.isEmpty())
        }

    @Test
    fun `a confirmation that does not verify is not counted as linked`() =
        runTest {
            watch.corruptConfirmation = true

            initiator().reconcile(emptyMap())

            assertEquals(PhoneLinkFailure.CONFIRMATION_MISMATCH, store.get(WATCH)?.failure)
            assertNull(pairKeys.keys[WATCH])
            assertTrue(notifier.events.isEmpty())
        }

    @Test
    fun `a watch that reports losing its link is paired again`() =
        runTest {
            val initiator = initiator()
            initiator.reconcile(emptyMap())
            clock.millis += PhoneLinkDecision.RELINK_GRACE_MS

            initiator.reconcile(mapOf(WATCH to unlinkedDocument(at = clock.millis)))

            assertEquals(4, watch.requests.size)
        }

    @Test
    fun `a report written right after the link does not start another exchange`() =
        runTest {
            val initiator = initiator()
            initiator.reconcile(emptyMap())

            initiator.reconcile(mapOf(WATCH to unlinkedDocument(at = clock.millis + 1)))

            assertEquals(2, watch.requests.size)
        }

    @Test
    fun `with the switch off the phone sends nothing`() =
        runTest {
            initiator(enabled = false).start(backgroundScope)
            runCurrent()

            assertTrue(watch.requests.isEmpty())
        }

    @Test
    fun `a quiet watch is retried when its wait expires`() =
        runTest {
            watch.reachable = false
            initiator().start(backgroundScope)
            runCurrent()
            assertEquals(1, watch.requests.size)
            clock.millis += 60_000
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, watch.requests.size)
            assertEquals(2, store.get(WATCH)?.attempts)
        }

    @Test
    fun `turning the switch off interrupts an offer already awaiting an answer`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var interrupted = false
            watch.afterOffer = {
                try {
                    release.await()
                } finally {
                    interrupted = true
                }
            }
            initiator().start(backgroundScope)
            runCurrent()
            assertEquals(1, watch.requests.size)
            enabledFlow.value = false
            runCurrent()
            assertTrue(interrupted)
            assertNull(store.get(WATCH))
            assertNull(pairKeys.keys[WATCH])
        }

    @Test
    fun `losing watch capability interrupts an unfinished exchange`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            var interrupted = false
            watch.afterOffer = {
                try {
                    release.await()
                } finally {
                    interrupted = true
                }
            }
            initiator().start(backgroundScope)
            runCurrent()
            capableFlow.value = false
            runCurrent()
            assertTrue(interrupted)
            assertEquals(1, watch.requests.size)
            assertNull(store.get(WATCH))
        }

    @Test
    fun `an explicit reconciliation respects the disabled switch`() =
        runTest {
            initiator(enabled = false).reconcile(emptyMap())
            assertTrue(watch.requests.isEmpty())
        }

    @Test
    fun `an overdue unreachable peer does not block another watch retry`() =
        runTest {
            store.recordFailure(
                LinkTarget("offline", "Offline"),
                LinkPlan(LinkMode.PAIR),
                PhoneLinkFailure.WATCH_UNREACHABLE,
                clock.millis - 60_000,
                0,
            )
            watch.reachable = false
            initiator().start(backgroundScope)
            runCurrent()
            assertEquals(1, watch.requests.size)
            clock.millis += 60_000
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(2, watch.requests.size)
        }

    @Test
    fun `cloud confirmation compares watch documents on the watch clock`() =
        runTest {
            bundleSource =
                object : LinkBundleSource {
                    override fun changes(): Flow<Unit> = flowOf(Unit)

                    override suspend fun plan(watchFeatures: Set<String>) = LinkPlan(LinkMode.CLOUD, "account")

                    override suspend fun bundle(plan: LinkPlan) =
                        LinkBundleResult.Ready(LinkBundle(mode = LinkMode.CLOUD, accountId = "account"))
                }
            val initiator = initiator()
            initiator.reconcile(mapOf(WATCH to StateDocument(role = PeerRole.WATCH, writtenAt = 500, watch = WatchState())))
            assertTrue(notifier.events.isEmpty())
            initiator.reconcile(
                mapOf(
                    WATCH to
                        StateDocument(
                            role = PeerRole.WATCH,
                            writtenAt = 501,
                            watch = WatchState(link = PeerLinkState.LINKED, linkId = watch.linkId),
                        ),
                ),
            )
            assertEquals(listOf("linked Pixel Watch 3 CLOUD"), notifier.events)
            assertEquals(501L, store.get(WATCH)?.seenWatchDocAt)
        }

    @Test
    fun `a bundle plan change interrupts an old exchange`() =
        runTest {
            val changes = MutableStateFlow(0)
            bundleSource =
                object : LinkBundleSource {
                    override fun changes(): Flow<Unit> = changes.map { }

                    override suspend fun plan(watchFeatures: Set<String>) = LinkPlan(LinkMode.PAIR)

                    override suspend fun bundle(plan: LinkPlan) = LinkBundleResult.Ready(LinkBundle(mode = LinkMode.PAIR))
                }
            val release = CompletableDeferred<Unit>()
            var interrupted = false
            watch.afterOffer = {
                try {
                    release.await()
                } finally {
                    interrupted = true
                }
            }
            initiator().start(backgroundScope)
            runCurrent()
            changes.value++
            runCurrent()
            assertTrue(interrupted)
            assertNull(store.get(WATCH))
            assertNull(pairKeys.keys[WATCH])
        }

    @Test
    fun `a watch is paired with this phone when the account's server cannot be reached`() =
        runTest {
            initiator(bundles = unsteady).reconcile(emptyMap())

            assertEquals(listOf(WearPaths.LINK_OFFER, WearPaths.LINK_OFFER, WearPaths.LINK_BUNDLE), watch.requests)
            val record = assertNotNull(store.get(WATCH))
            assertTrue(record.isWorkingPair)
            assertEquals(AccountLinkWait(ACCOUNT, null, attempts = 1, nextAttemptAt = clock.millis + 60_000), record.accountWait)
            assertContentEquals(assertNotNull(watch.pairKey), pairKeys.keys[WATCH])
            assertEquals(listOf("linked Pixel Watch 3 PAIR"), notifier.events)
        }

    @Test
    fun `a paired watch keeps its pairing while the account is tried again after longer waits`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            val paired = assertNotNull(store.get(WATCH))

            initiator.reconcile(emptyMap())
            assertEquals(3, watch.requests.size)
            clock.millis += 60_000
            initiator.reconcile(emptyMap())

            assertEquals(4, watch.requests.size)
            val record = assertNotNull(store.get(WATCH))
            assertTrue(record.isWorkingPair)
            assertEquals(paired.linkId, record.linkId)
            assertEquals(AccountLinkWait(ACCOUNT, null, attempts = 2, nextAttemptAt = clock.millis + 120_000), record.accountWait)
            assertEquals(listOf("linked Pixel Watch 3 PAIR"), notifier.events)
        }

    @Test
    fun `a paired watch is linked to the account once the server answers`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            unsteady.serverAnswers = true
            clock.millis += 60_000

            initiator.reconcile(emptyMap())
            initiator.reconcile(mapOf(WATCH to watchDocument(PeerLinkState.LINKED, problem = null, at = clock.millis + 1)))

            val record = assertNotNull(store.get(WATCH))
            assertEquals(LinkMode.CLOUD, record.mode)
            assertNull(record.failure)
            assertNull(record.accountWait)
            assertEquals(listOf("linked Pixel Watch 3 PAIR", "linked Pixel Watch 3 CLOUD"), notifier.events)
        }

    @Test
    fun `a watch paired before signing in stays paired while the account's server cannot be reached`() =
        runTest {
            unsteady.signedIn = false
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            val paired = assertNotNull(store.get(WATCH))
            unsteady.signedIn = true

            initiator.reconcile(emptyMap())

            assertEquals(3, watch.requests.size)
            val record = assertNotNull(store.get(WATCH))
            assertTrue(record.isWorkingPair)
            assertEquals(paired.linkId, record.linkId)
            assertNotNull(pairKeys.keys[WATCH])
        }

    @Test
    fun `a paired watch that lost its link is paired again while the account waits`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            val first = assertNotNull(store.get(WATCH)?.linkId)
            clock.millis += PhoneLinkDecision.RELINK_GRACE_MS

            initiator.reconcile(mapOf(WATCH to unlinkedDocument(at = clock.millis)))

            assertEquals(6, watch.requests.size)
            val record = assertNotNull(store.get(WATCH))
            assertTrue(record.isWorkingPair)
            assertTrue(first != record.linkId)
            assertEquals(watch.linkId, record.linkId)
        }

    @Test
    fun `a paired watch's account is tried again when its wait ends with no other change`() =
        runTest {
            initiator(bundles = unsteady).start(backgroundScope)
            runCurrent()
            assertEquals(3, watch.requests.size)

            clock.millis += 60_000
            advanceTimeBy(60_000)
            runCurrent()

            assertEquals(4, watch.requests.size)
            assertEquals(2, store.get(WATCH)?.accountWait?.attempts)
        }

    @Test
    fun `a server that refuses the link is recorded as a failure and the watch is not paired instead`() =
        runTest {
            unsteady.serverRefuses = true

            initiator(bundles = unsteady).reconcile(emptyMap())

            assertEquals(listOf(WearPaths.LINK_OFFER), watch.requests)
            assertEquals(PhoneLinkFailure.LINK_CODE_UNAVAILABLE, store.get(WATCH)?.failure)
            assertTrue(notifier.events.isEmpty())
        }

    @Test
    fun `asking to set up the watch again tries the account at once`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            unsteady.serverAnswers = true

            store.retryNow(WATCH)
            initiator.reconcile(emptyMap())

            assertEquals(LinkMode.CLOUD, store.get(WATCH)?.mode)
        }

    @Test
    fun `a watch out of reach when the account is tried again keeps its pairing`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            val paired = assertNotNull(store.get(WATCH))
            watch.reachable = false
            clock.millis += 60_000

            initiator.reconcile(emptyMap())

            val record = assertNotNull(store.get(WATCH))
            assertTrue(record.isWorkingPair)
            assertEquals(paired.linkId, record.linkId)
            assertEquals(2, record.accountWait?.attempts)
        }

    @Test
    fun `signing out ends the wait so signing in again tries the account at once`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            unsteady.signedIn = false
            initiator.reconcile(emptyMap())
            assertNull(store.get(WATCH)?.accountWait)
            unsteady.signedIn = true
            unsteady.serverAnswers = true

            initiator.reconcile(emptyMap())

            assertEquals(LinkMode.CLOUD, store.get(WATCH)?.mode)
        }

    @Test
    fun `pairing again while the account waits keeps counting the account's attempts`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            clock.millis += PhoneLinkDecision.RELINK_GRACE_MS

            initiator.reconcile(mapOf(WATCH to unlinkedDocument(at = clock.millis)))

            assertEquals(2, store.get(WATCH)?.accountWait?.attempts)
        }

    @Test
    fun `a put off account link does not delay pairing a watch that lost its link`() =
        runTest {
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            clock.millis += PhoneLinkDecision.RELINK_GRACE_MS
            initiator.reconcile(emptyMap())
            assertEquals(4, watch.requests.size)

            clock.millis += 1_000
            initiator.reconcile(mapOf(WATCH to unlinkedDocument(at = clock.millis)))

            assertEquals(6, watch.requests.size)
            val record = assertNotNull(store.get(WATCH))
            assertTrue(record.isWorkingPair)
            assertEquals(watch.linkId, record.linkId)
            assertEquals(2, record.accountWait?.attempts)
        }

    @Test
    fun `a watch whose account link cannot be renewed is not paired instead`() =
        runTest {
            unsteady.serverAnswers = true
            val initiator = initiator(bundles = unsteady)
            initiator.reconcile(emptyMap())
            unsteady.serverAnswers = false

            initiator.reconcile(mapOf(WATCH to watchDocument(PeerLinkState.NEEDS_RELINK, "SESSION_EXPIRED", clock.millis + 1)))

            assertEquals(3, watch.requests.size)
            val record = assertNotNull(store.get(WATCH))
            assertEquals(LinkMode.CLOUD, record.mode)
            assertEquals(PhoneLinkFailure.LINK_CODE_UNAVAILABLE, record.failure)
        }

    private fun unlinkedDocument(at: Long) =
        StateDocument(role = PeerRole.WATCH, writtenAt = at, watch = WatchState(link = PeerLinkState.UNLINKED))
}
