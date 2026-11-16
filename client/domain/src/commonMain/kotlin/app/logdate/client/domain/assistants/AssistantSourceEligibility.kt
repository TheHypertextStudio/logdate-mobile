package app.logdate.client.domain.assistants

import app.logdate.client.repository.assistants.AssistantCapability
import app.logdate.client.repository.assistants.AssistantContentScope
import app.logdate.client.repository.assistants.AssistantGrant
import app.logdate.client.repository.assistants.AssistantSourceKind
import app.logdate.client.repository.assistants.AssistantSourceMetadata

class AssistantSourceEligibility {
    /** Apply this predicate before scoring, snippets, limits, counts or cursors are computed. */
    fun allows(
        grant: AssistantGrant,
        source: AssistantSourceMetadata,
    ): Boolean = allows(grant.capabilities, grant.contentScope, source)

    /**
     * The same rule for consent kept on this device without a connection grant, such as what the
     * person allows Android assistants to see.
     */
    fun allows(
        capabilities: Set<AssistantCapability>,
        contentScope: AssistantContentScope,
        source: AssistantSourceMetadata,
    ): Boolean {
        if (source.shared) return false
        return when (source.kind) {
            AssistantSourceKind.TEXT,
            AssistantSourceKind.TRANSCRIPT,
            AssistantSourceKind.JOURNAL,
            -> AssistantCapability.READ_MEMORIES in capabilities && contentScope.covers(source)

            AssistantSourceKind.PLACE -> AssistantCapability.READ_PLACES in capabilities

            AssistantSourceKind.PERSON -> AssistantCapability.READ_PEOPLE in capabilities

            AssistantSourceKind.ORIGINAL_MEDIA, AssistantSourceKind.UNSUPPORTED -> false
        }
    }

    private fun AssistantContentScope.covers(source: AssistantSourceMetadata): Boolean {
        if (source.id in excludedEntryIds) return false
        return if (source.journalIds.isEmpty()) includeUnfiled else source.journalIds.all { it in journalIds }
    }
}
