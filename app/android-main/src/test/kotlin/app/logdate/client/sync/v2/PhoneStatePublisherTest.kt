package app.logdate.client.sync.v2

import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.domain.streak.CampfireState
import app.logdate.client.domain.streak.FirePhase
import app.logdate.client.sync.datalayer.v2.CloudPresence
import app.logdate.client.sync.datalayer.v2.Decoded
import app.logdate.client.sync.datalayer.v2.WearFeatures
import app.logdate.client.sync.peer.state.ActivitySnapshot
import app.logdate.client.sync.peer.state.CampfireSnapshot
import app.logdate.client.sync.peer.state.LocalCapabilities
import app.logdate.client.sync.peer.state.PeerDocument
import app.logdate.client.sync.peer.state.PeerRole
import app.logdate.client.sync.peer.state.PeerStateStore
import app.logdate.client.sync.peer.state.PhoneState
import app.logdate.client.sync.peer.state.Stamped
import app.logdate.client.sync.peer.state.StateDocument
import app.logdate.client.sync.peer.state.StateDocumentChannel
import app.logdate.client.sync.peer.state.WatchState
import app.logdate.di.phoneWatchFeatures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests [PhoneStatePublisher]: the phone always publishes its state, and advertises version 2 only
 * while the Labs switch is on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneStatePublisherTest {
    @Test
    fun `production phone advertises marked moment ingestion with operation receipts`() =
        runTest {
            val publisher = PhoneStatePublisher(enabled, channel, capabilities, peerStates, features = phoneWatchFeatures)
            publisher.refresh()
            val features =
                channel.published
                    .last()
                    .phone!!
                    .features
            kotlin.test.assertTrue(WearFeatures.MOMENTS in features)
            kotlin.test.assertTrue(WearFeatures.OPERATION_RECEIPTS in features)
            kotlin.test.assertTrue(WearFeatures.PHONE_SETTINGS_LINK in features)
            kotlin.test.assertTrue(WearFeatures.REMINDERS in features)
        }

    @Test
    fun `presence refresh publishes current complete inputs rather than placeholder defaults`() =
        runTest {
            val current = PhoneStateParts(cloud = CloudPresence.SIGNED_IN, dayStartHour = 5)
            val shared = mapOf("window.audioDownloads" to Stamped(JsonPrimitive("on_demand"), 100))
            val publisher =
                PhoneStatePublisher(
                    enabled,
                    channel,
                    capabilities,
                    peerStates,
                    parts = MutableStateFlow(current),
                    sharedPreferences = MutableStateFlow(shared),
                    lastReminderAt = MutableStateFlow(900L),
                )
            publisher.refresh()
            assertEquals(
                CloudPresence.SIGNED_IN,
                channel.published
                    .last()
                    .phone
                    ?.cloud,
            )
            assertEquals(
                5,
                channel.published
                    .last()
                    .phone
                    ?.dayStartHour,
            )
            assertEquals(shared, channel.published.last().shared)
            assertEquals(900L, channel.published.last().lastReminderAt)
        }

    @Test
    fun `learned waking hours are published as phone owned state`() =
        runTest {
            val hours =
                app.logdate.client.sync.peer.state
                    .ReminderHours(32_400, 7_200)
            val publisher = PhoneStatePublisher(enabled, channel, capabilities, peerStates)
            publisher.publish(
                true,
                shared = mapOf("reminders.enabled" to Stamped(JsonPrimitive(true), 900)),
                current = PhoneStateParts(reminderHours = hours),
            )
            assertEquals(
                hours,
                channel.published
                    .last()
                    .phone
                    ?.reminderHours,
            )
        }

    @Test fun `stale learned hours cannot be published after rollout or consent is off`() =
        runTest {
            val hours =
                app.logdate.client.sync.peer.state
                    .ReminderHours(32_400, 7_200)
            val publisher = PhoneStatePublisher(enabled, channel, capabilities, peerStates)
            for ((rollout, consent) in listOf(false to true, true to false)) {
                publisher.publish(
                    rollout,
                    shared = mapOf("reminders.enabled" to Stamped(JsonPrimitive(consent), 900)),
                    current = PhoneStateParts(reminderHours = hours),
                )
                assertEquals(
                    null,
                    channel.published
                        .last()
                        .phone
                        ?.reminderHours,
                )
            }
        }

    @Test
    fun `unlinked watch preferences cannot shadow the verified watch`() =
        runTest {
            val publisher =
                PhoneStatePublisher(
                    enabled,
                    channel,
                    capabilities,
                    peerStates,
                    adoptWatchPreferences = { adopted += it },
                    acceptsWatchPreferences = { node, _ -> node == "verified" },
                )
            publisher.start(backgroundScope)
            runCurrent()
            val good = mapOf("reminders.enabled" to Stamped(JsonPrimitive(true), 900))
            val bad = mapOf("reminders.enabled" to Stamped(JsonPrimitive(false), 950))
            peerStates.accept(
                "verified",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 900, shared = good)),
                PeerRole.WATCH,
            )
            peerStates.accept(
                "unlinked",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 950, shared = bad)),
                PeerRole.WATCH,
            )
            runCurrent()
            assertEquals(listOf(good), adopted)
        }

    @Test
    fun `withdrawn watch cannot change shared reminder choice`() =
        runTest {
            var current = true
            val publisher =
                PhoneStatePublisher(
                    enabled,
                    channel,
                    capabilities,
                    peerStates,
                    adoptWatchPreferences = { adopted += it },
                    acceptsWatchPreferences = { _, _ -> current },
                )
            publisher.start(backgroundScope)
            runCurrent()
            current = false
            val shared = mapOf("reminders.enabled" to Stamped(JsonPrimitive(true), 950))
            peerStates.accept(
                "watch-node",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 950, shared = shared)),
                PeerRole.WATCH,
            )
            runCurrent()
            assertEquals(emptyList(), adopted)
        }

    @Test
    fun `the phone lists the features it supports`() =
        runTest {
            val withFeatures =
                PhoneStatePublisher(
                    v2Enabled = enabled,
                    channel = channel,
                    capabilities = capabilities,
                    peerStates = peerStates,
                    clock = FixedClock(1_000),
                    timeZone = { TimeZone.of("Europe/Paris") },
                    features = setOf("link.cloud.v1"),
                )

            withFeatures.start(backgroundScope)
            runCurrent()

            assertEquals(
                setOf("link.cloud.v1"),
                channel.published
                    .last()
                    .phone
                    ?.features,
            )
        }

    @Test
    fun `signing in publishes the account presence without advertising again`() =
        runTest {
            val parts = MutableStateFlow(PhoneStateParts(cloud = CloudPresence.NO_ACCOUNT))
            val withParts =
                PhoneStatePublisher(
                    v2Enabled = enabled,
                    channel = channel,
                    capabilities = capabilities,
                    peerStates = peerStates,
                    clock = FixedClock(1_000),
                    timeZone = { TimeZone.of("Europe/Paris") },
                    parts = parts,
                )
            withParts.start(backgroundScope)
            runCurrent()

            parts.value = PhoneStateParts(cloud = CloudPresence.SIGNED_IN)
            runCurrent()

            assertEquals(
                CloudPresence.SIGNED_IN,
                channel.published
                    .last()
                    .phone
                    ?.cloud,
            )
            assertEquals(listOf("remove $PHONE_V2"), capabilities.calls)
        }

    @Test
    fun `posted history republishes without advertising again`() =
        runTest {
            val history = MutableStateFlow<Long?>(null)
            PhoneStatePublisher(enabled, channel, capabilities, peerStates, lastReminderAt = history).start(backgroundScope)
            runCurrent()
            history.value = 900
            runCurrent()
            assertEquals(900L, channel.published.last().lastReminderAt)
            assertEquals(listOf("remove $PHONE_V2"), capabilities.calls)
        }

    @Test
    fun `new watch post is adopted even when its preferences are unchanged`() =
        runTest {
            val posts = mutableListOf<Pair<String, Long>>()
            PhoneStatePublisher(
                enabled,
                channel,
                capabilities,
                peerStates,
                adoptWatchReminderAt = { node, at -> posts += node to at },
            ).start(backgroundScope)
            runCurrent()
            for (at in listOf(900L, 950L)) {
                peerStates.accept(
                    "watch",
                    Decoded.Supported(
                        StateDocument(
                            role = PeerRole.WATCH,
                            writtenAt = at,
                            lastReminderAt = at,
                        ),
                    ),
                    PeerRole.WATCH,
                )
                runCurrent()
            }
            assertEquals(listOf("watch" to 900L, "watch" to 950L), posts)
        }

    @Test
    fun `foreign and withdrawn watches cannot alter posted history`() =
        runTest {
            var current = true
            val posts = mutableListOf<Pair<String, Long>>()
            PhoneStatePublisher(
                enabled,
                channel,
                capabilities,
                peerStates,
                adoptWatchReminderAt = { node, at -> posts += node to at },
                acceptsWatchPreferences = { node, _ -> current && node == "verified" },
            ).start(backgroundScope)
            runCurrent()
            peerStates.accept(
                "foreign",
                Decoded.Supported(
                    StateDocument(
                        role = PeerRole.WATCH,
                        writtenAt = 900,
                        lastReminderAt = 900,
                    ),
                ),
                PeerRole.WATCH,
            )
            runCurrent()
            current = false
            peerStates.accept(
                "verified",
                Decoded.Supported(
                    StateDocument(
                        role = PeerRole.WATCH,
                        writtenAt = 950,
                        lastReminderAt = 950,
                    ),
                ),
                PeerRole.WATCH,
            )
            runCurrent()
            assertEquals(emptyList(), posts)
        }

    @Test
    fun `failed capability change retries on the next history change`() =
        runTest {
            val history = MutableStateFlow<Long?>(null)
            capabilities.succeeds = false
            PhoneStatePublisher(enabled, channel, capabilities, peerStates, lastReminderAt = history).start(backgroundScope)
            runCurrent()
            capabilities.succeeds = true
            history.value = 900
            runCurrent()
            assertEquals(listOf("remove $PHONE_V2", "remove $PHONE_V2"), capabilities.calls)
        }

    private val enabled = MutableStateFlow(false)
    private val preferences = MutableStateFlow<Map<String, Stamped>>(emptyMap())
    private val adopted = mutableListOf<Map<String, Stamped>>()
    private val channel = FakeChannel()
    private val capabilities = FakeCapabilities()
    private val peerStates = PeerStateStore()
    private val publisher =
        PhoneStatePublisher(
            v2Enabled = enabled,
            channel = channel,
            capabilities = capabilities,
            peerStates = peerStates,
            clock = FixedClock(1_000),
            timeZone = { TimeZone.of("Europe/Paris") },
            sharedPreferences = preferences,
            adoptWatchPreferences = { adopted += it },
        )

    @Test
    fun `activity awareness and the phone's activity go to the watch only while it is on`() =
        runTest {
            val walking = ActivitySnapshot(kind = "MOVING", startedAt = 500)

            publisher.publish(v2Enabled = true, current = PhoneStateParts(activityAwareness = false, activity = walking))
            publisher.publish(v2Enabled = true, current = PhoneStateParts(activityAwareness = true, activity = walking))

            val (off, on) = channel.published.map { it.phone }
            assertEquals(mapOf(FLAG_KEY to true), off?.flags)
            assertEquals(null, off?.activity)
            assertEquals(mapOf(FLAG_KEY to true, FeatureFlag.ACTIVITY_AWARENESS.key to true), on?.flags)
            assertEquals(walking, on?.activity)
        }

    @Test
    fun `the phone publishes its state with the switch off`() =
        runTest {
            publisher.start(backgroundScope)
            runCurrent()

            assertEquals(
                listOf(
                    StateDocument(
                        role = PeerRole.PHONE,
                        writtenAt = 1_000,
                        phone = PhoneState(flags = mapOf(FLAG_KEY to false), zone = "Europe/Paris", features = setOf("prefs.v1")),
                    ),
                ),
                channel.published,
            )
            assertEquals(listOf("remove $PHONE_V2"), capabilities.calls)
        }

    @Test
    fun `turning the switch on publishes it and advertises version 2`() =
        runTest {
            publisher.start(backgroundScope)
            runCurrent()

            enabled.value = true
            runCurrent()

            assertEquals(
                true,
                channel.published
                    .last()
                    .phone
                    ?.flags
                    ?.get(FLAG_KEY),
            )
            assertEquals(listOf("remove $PHONE_V2", "add $PHONE_V2"), capabilities.calls)
        }

    @Test
    fun `starting reads the state the watches already published`() =
        runTest {
            val watch = StateDocument(role = PeerRole.WATCH, writtenAt = 1, watch = WatchState(features = setOf("receipts.v1")))
            channel.peers = listOf(PeerDocument("watch-node", Decoded.Supported(watch)))

            publisher.start(backgroundScope)
            runCurrent()

            assertEquals(setOf("receipts.v1"), peerStates.features(PeerRole.WATCH).first())
        }

    @Test
    fun `a failed publish still updates the capability`() =
        runTest {
            channel.succeeds = false

            publisher.start(backgroundScope)
            runCurrent()

            assertEquals(listOf("remove $PHONE_V2"), capabilities.calls)
        }

    @Test
    fun `a preference change republishes the document without touching the capability`() =
        runTest {
            publisher.start(backgroundScope)
            runCurrent()

            preferences.value = mapOf("window.days" to Stamped(JsonPrimitive(30), 900))
            runCurrent()

            assertEquals(2, channel.published.size)
            assertEquals(mapOf("window.days" to Stamped(JsonPrimitive(30), 900)), channel.published.last().shared)
            assertEquals(listOf("remove $PHONE_V2"), capabilities.calls)
        }

    @Test
    fun `preferences a watch wrote are handed over to be adopted`() =
        runTest {
            publisher.start(backgroundScope)
            runCurrent()
            val shared = mapOf("capture.places" to Stamped(JsonPrimitive(true), 950))

            peerStates.accept(
                "watch-node",
                Decoded.Supported(StateDocument(role = PeerRole.WATCH, writtenAt = 950, watch = WatchState(), shared = shared)),
                PeerRole.WATCH,
            )
            runCurrent()

            assertEquals(listOf(shared), adopted)
        }

    private class FakeChannel : StateDocumentChannel {
        val published = mutableListOf<StateDocument>()
        var peers = emptyList<PeerDocument>()
        var succeeds = true

        override suspend fun publish(document: StateDocument): Boolean {
            published += document
            return succeeds
        }

        override suspend fun peerDocuments(): List<PeerDocument> = peers
    }

    private class FakeCapabilities : LocalCapabilities {
        val calls = mutableListOf<String>()
        var succeeds = true

        override suspend fun add(capability: String): Boolean {
            calls += "add $capability"
            return succeeds
        }

        override suspend fun remove(capability: String): Boolean {
            calls += "remove $capability"
            return succeeds
        }
    }

    private class FixedClock(
        private val millis: Long,
    ) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    private companion object {
        const val FLAG_KEY = "watch_experience_v2_enabled"
        const val PHONE_V2 = "logdate_phone_sync_v2"
    }

    @Test
    fun `the phone's campfire crosses as its phase and run and whether it is lit today`() {
        val state =
            CampfireState(
                phase = FirePhase.BURNING,
                loggedToday = true,
                runDays = 6,
                size = null,
                longestRunDays = 9,
                totalDaysJournaled = 40,
                isRekindled = false,
            )

        assertEquals(
            CampfireSnapshot(phase = "BURNING", runDays = 6, litToday = true, computedAt = 5),
            state.toSnapshot(Instant.fromEpochMilliseconds(5)),
        )
    }
}
