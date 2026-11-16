package app.logdate.client.repository.assistants

import kotlin.uuid.Uuid

/** What a piece of LogDate content is, which decides the permission an assistant needs to see it. */
enum class AssistantSourceKind {
    TEXT,
    TRANSCRIPT,

    /** A journal's title and description. */
    JOURNAL,

    /** A saved place. */
    PLACE,

    /** A person who comes up in entries. */
    PERSON,
    ORIGINAL_MEDIA,
    UNSUPPORTED,
}

data class AssistantSourceMetadata(
    val id: Uuid,
    val journalIds: Set<Uuid>,
    val kind: AssistantSourceKind,
    val shared: Boolean,
) {
    override fun toString(): String = "AssistantSourceMetadata([redacted])"
}
