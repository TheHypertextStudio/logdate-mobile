package app.logdate.client.sync.v2.window

import app.logdate.client.domain.watch.WatchWindowPolicy
import app.logdate.client.domain.watch.WatchWindowPreferences
import app.logdate.client.domain.watch.WindowBudget
import app.logdate.client.domain.watch.WindowCandidate
import app.logdate.client.domain.watch.WindowExtraIds
import app.logdate.client.domain.watch.WindowPlan
import app.logdate.client.repository.journals.JournalNote
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The notes chosen for the watch's library window and the bodies that carry them. */
internal class SelectedWindow(
    val plan: WindowPlan,
    val notes: Map<Uuid, JournalNote>,
    val bodies: Map<Uuid, JsonElement>,
)

/** Every note that qualifies for the window, before any budget applies. */
private val qualifying = WatchWindowPolicy(budget = WindowBudget(maxItems = Int.MAX_VALUE, maxBytes = Long.MAX_VALUE))

/**
 * Chooses the window, encoding only the notes that qualify for it. The byte budget needs each body's
 * size, but a note outside every window reason can never be chosen, so it is never encoded.
 */
internal fun selectWindow(
    notes: List<JournalNote>,
    policy: WatchWindowPolicy,
    extras: WindowExtraIds,
    now: Instant,
    zone: TimeZone,
    dayStartHour: Int,
    preferences: WatchWindowPreferences,
    encode: (JournalNote) -> JsonElement,
): SelectedWindow {
    val unsized = notes.map { WindowCandidate(it.uid, it.creationTimestamp, sizeBytes = 0) }
    val qualifiedIds = qualifying.choose(unsized, extras, now, zone, dayStartHour, preferences).members.mapTo(HashSet()) { it.id }
    val qualified = notes.filter { it.uid in qualifiedIds }.associateBy { it.uid }
    val bodies = qualified.mapValues { (_, note) -> encode(note) }
    val candidates = qualified.values.map { WindowCandidate(it.uid, it.creationTimestamp, bodies.getValue(it.uid).sizeBytes()) }
    return SelectedWindow(policy.choose(candidates, extras, now, zone, dayStartHour, preferences), qualified, bodies)
}

internal fun JsonElement.sizeBytes(): Long = toString().encodeToByteArray().size.toLong()
