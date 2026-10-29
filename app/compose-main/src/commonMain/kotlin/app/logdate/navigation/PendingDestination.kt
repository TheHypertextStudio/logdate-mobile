package app.logdate.navigation

import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.account.navigation.BlueskySignInRoute

internal fun canOpenPendingDestination(
    target: NavKey,
    isOnboarded: Boolean,
    requiresUnlock: Boolean,
): Boolean {
    if (requiresUnlock) return false
    val isBrowserReturn = target is BlueskySignInRoute && target.attemptId?.matches(Regex("[A-Za-z0-9_-]{43}")) == true
    return isOnboarded || isBrowserReturn
}

internal fun applyPendingDestination(
    backStack: MutableList<NavKey>,
    target: NavKey,
) {
    if (backStack.lastOrNull() == target) return
    if (target is BlueskySignInRoute && backStack.lastOrNull() is BlueskySignInRoute) {
        backStack.removeLastOrNull()
    }
    backStack.add(target)
}
