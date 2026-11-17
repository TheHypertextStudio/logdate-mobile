package app.logdate.feature.core.profile.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.profile.ui.ProfileScreen
import app.logdate.ui.navigation.taggedEntry
import kotlinx.serialization.Serializable

@Serializable
data object ProfileRoute : NavKey

/** Registers the profile entry. */
fun EntryProviderScope<NavKey>.profileEntry(
    onBack: () -> Unit,
    onNavigateToBirthday: () -> Unit,
) {
    taggedEntry<ProfileRoute> {
        ProfileScreen(
            onBack = onBack,
            onNavigateToBirthday = onNavigateToBirthday,
        )
    }
}
