package app.logdate.feature.core.assistants

import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import kotlin.uuid.Uuid

/** Display snapshot from the LogDate-owned connection request; the assistant's own tool arguments never fill it. */
data class AssistantConnectRequest(
    val assistantName: String,
    val deviceName: String,
    val requestedCapabilities: Set<AssistantCapability>,
) {
    override fun toString(): String = "AssistantConnectRequest([redacted])"
}

/** A journal the person can share; this list is shown only on the person's own device. */
data class AssistantJournalOption(
    val id: Uuid,
    val title: String,
) {
    override fun toString(): String = "AssistantJournalOption([redacted])"
}

/** Exactly what the person allowed, ready for the grant issuer to validate against live authority. */
data class AssistantAccessGrant(
    val capabilities: Set<AssistantCapability>,
    val contentScope: AssistantContentScope,
) {
    override fun toString(): String = "AssistantAccessGrant([redacted])"
}

/** What the person has turned on so far. Nothing is on until they choose it. */
data class AssistantAccessSelection(
    val capabilities: Set<AssistantCapability> = emptySet(),
    val journalIds: Set<Uuid> = emptySet(),
    val includeUnfiled: Boolean = false,
) {
    /** The grant to issue, or null when nothing is on or a chosen journal no longer exists. */
    fun grant(
        requested: Set<AssistantCapability>,
        availableJournalIds: Set<Uuid>,
    ): AssistantAccessGrant? {
        if (capabilities.isEmpty() || !requested.containsAll(capabilities)) return null
        val readsEntries = AssistantCapability.READ_MEMORIES in capabilities
        if (readsEntries && !availableJournalIds.containsAll(journalIds)) return null
        val scope =
            if (readsEntries) {
                AssistantContentScope(journalIds.toSet(), includeUnfiled = includeUnfiled)
            } else {
                AssistantContentScope(emptySet())
            }
        return AssistantAccessGrant(capabilities.toSet(), scope)
    }

    /** Turning entries on shares every journal and entries outside journals until the person narrows it. */
    fun withCapability(
        capability: AssistantCapability,
        enabled: Boolean,
        availableJournalIds: Set<Uuid>,
    ): AssistantAccessSelection =
        when {
            capability == AssistantCapability.READ_MEMORIES && enabled ->
                copy(capabilities = capabilities + capability, journalIds = availableJournalIds.toSet(), includeUnfiled = true)
            capability == AssistantCapability.READ_MEMORIES ->
                copy(capabilities = capabilities - capability, journalIds = emptySet(), includeUnfiled = false)
            enabled -> copy(capabilities = capabilities + capability)
            else -> copy(capabilities = capabilities - capability)
        }

    /** Narrowing entries to nothing turns entries off instead of leaving an empty permission on. */
    fun withEntries(
        journalIds: Set<Uuid>,
        includeUnfiled: Boolean,
    ): AssistantAccessSelection =
        if (journalIds.isEmpty() && !includeUnfiled) {
            copy(capabilities = capabilities - AssistantCapability.READ_MEMORIES, journalIds = emptySet(), includeUnfiled = false)
        } else {
            copy(journalIds = journalIds.toSet(), includeUnfiled = includeUnfiled)
        }

    override fun toString(): String = "AssistantAccessSelection([redacted])"
}

/** One assistant as Settings shows it; names and labels are already resolved for display. */
data class ConnectedAssistantSummary(
    val id: String,
    val name: String,
    val capabilities: Set<AssistantCapability>,
    val connectedLabel: String,
    val lastUsedLabel: String?,
) {
    override fun toString(): String = "ConnectedAssistantSummary([redacted])"
}

/** One request an assistant made, described for the person who owns the data. */
data class AssistantActivity(
    val capability: AssistantCapability,
    val description: String,
    val timeLabel: String,
) {
    override fun toString(): String = "AssistantActivity([redacted])"
}
