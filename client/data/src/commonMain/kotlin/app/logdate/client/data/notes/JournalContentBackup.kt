package app.logdate.client.data.notes

import app.logdate.client.repository.journals.JournalNote
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Serializable snapshot of exported journal notes and their journal memberships.
 *
 * @property notes All exported notes within the requested time window.
 * @property journalToNotesMap Mapping from each journal ID to the IDs of notes belonging to it.
 * @property generated Timestamp when the export payload was generated.
 * @property version Schema version of the export payload.
 */
@Serializable
data class JournalContentBackup(
    val notes: List<JournalNote>,
    @Serializable(with = UuidToUuidListMapSerializer::class)
    val journalToNotesMap: Map<Uuid, List<Uuid>> = emptyMap(),
    val generated: Instant = Clock.System.now(),
    val version: String = "1.0",
)
