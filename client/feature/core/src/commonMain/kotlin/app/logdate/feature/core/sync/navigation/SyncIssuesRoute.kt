package app.logdate.feature.core.sync.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.sync.DeletionRecoveryScreen
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable

/** Review edits retained when ordinary sync encountered a deletion. */
@Serializable
data object SyncIssuesRoute : NavKey

fun EntryProviderScope<NavKey>.syncIssuesEntry(onBack: () -> Unit) {
    taggedEntry<SyncIssuesRoute> {
        DeletionRecoveryScreen(onBack = onBack)
    }
}
