package app.logdate.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.account.navigation.CloudAccountSetupRoute
import app.logdate.feature.core.account.navigation.cloudAccountSetupEntry
import app.logdate.feature.onboarding.navigation.WelcomeBack

internal fun EntryProviderScope<NavKey>.accountSetupEntries(
    backStack: NavBackStack<NavKey>,
    isOnboarded: Boolean,
    onSignedIn: () -> Unit,
    metadata: Map<String, Any> = emptyMap(),
) {
    cloudAccountSetupEntry(
        onAccountCreated = { backStack.removeLastOrNull() },
        onSkipped = { backStack.removeLastOrNull() },
        onBack = { backStack.removeLastOrNull() },
        onSignedIn = onSignedIn,
        metadata = metadata,
        onContinueWithBluesky = { backStack.add(BlueskySignInRoute()) },
        onBlueskyAccountCreated = { completeBlueskyAccountSetup(backStack, isOnboarded) },
    )
}

internal fun completeBlueskyAccountSetup(
    backStack: MutableList<NavKey>,
    isOnboarded: Boolean,
) {
    while (backStack.lastOrNull() is BlueskySignInRoute || backStack.lastOrNull() is CloudAccountSetupRoute) {
        backStack.removeLastOrNull()
    }
    if (!isOnboarded) backStack.add(WelcomeBack)
}
