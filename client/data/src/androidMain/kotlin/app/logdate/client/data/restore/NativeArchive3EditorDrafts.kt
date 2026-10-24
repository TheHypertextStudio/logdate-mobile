package app.logdate.client.data.restore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import app.logdate.client.data.journals.ArchiveDraftStorageState
import app.logdate.shared.model.EditorDraft
import kotlinx.serialization.json.Json

/** Selects local editor drafts without crossing a different account's custody boundary. */
internal object NativeArchive3EditorDrafts {
    fun read(
        preferences: Preferences,
        ownerId: String,
        ownerBound: Boolean,
        serverOrigin: String,
    ): List<EditorDraft> {
        val selectedScope = "editor_drafts_scope_${ownerId.length}:$ownerId:${serverOrigin.length}:$serverOrigin"
        val selectedOwner = "${ownerId.length}:$ownerId:${serverOrigin.length}:$serverOrigin"
        val legacyOwner = preferences[stringPreferencesKey("editor_drafts_legacy_owner")]
        val selected = mutableListOf<EditorDraft>()
        preferences.asMap().forEach { (key, value) ->
            val name = key.name
            when {
                name == "entry_drafts" -> {
                    check(value is String)
                    selected += Json.decodeFromString<List<EditorDraft>>(value)
                }

                name == "editor_drafts_legacy_owner" || name.endsWith("_legacy_copied") -> {
                    Unit
                }

                name == "editor_drafts" || name == "editor_drafts_anonymous" || name.startsWith("editor_drafts_scope_") -> {
                    check(value is String)
                    val state = ArchiveDraftStorageState.decode(Json, value)
                    check(state.custodyCopies.isEmpty() && state.pendingMutations.isEmpty() && state.localDeletionIds.isEmpty()) {
                        "Editor draft custody is not yet portable"
                    }
                    if (name == "editor_drafts" && legacyOwner != null && legacyOwner != selectedOwner) {
                        check(state.drafts.isEmpty()) { "A different owner has legacy editor drafts" }
                    } else if ((name == selectedScope && ownerBound) || name in setOf("editor_drafts", "editor_drafts_anonymous")) {
                        selected += state.drafts
                    } else {
                        check(state.drafts.isEmpty()) { "A different owner has editor drafts" }
                    }
                }

                name.startsWith("editor_drafts") -> {
                    check(false) { "Unrecognized editor draft custody" }
                }
            }
        }
        val byId = selected.groupBy { it.id }
        check(byId.values.all { versions -> versions.distinct().size == 1 }) { "Conflicting editor drafts share one identity" }
        return byId.values.map { it.first() }
    }
}
