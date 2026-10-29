@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavKey
import app.logdate.feature.core.account.navigation.BlueskySignInRoute
import app.logdate.feature.core.navigation.BaseRoute
import app.logdate.feature.core.settings.navigation.WatchSettingsRoute
import app.logdate.feature.editor.navigation.EntryEditorRoute
import app.logdate.feature.editor.navigation.RecordingEditorDestination
import app.logdate.feature.onboarding.navigation.OnboardingStart
import io.github.aakira.napier.Napier

/**
 * Applies [resolveNavBootstrapAction] and opens the launch destination in the same pass.
 *
 * Resetting to Home and opening the pending destination happen together, so a cold start that
 * targets another screen never draws Home on its own first. A destination that arrives while
 * the app is locked or not yet onboarded stays pending until both gates clear.
 */
@Composable
internal fun NavBootstrapEffect(
    backStack: MutableList<NavKey>,
    pendingNavKey: NavKey?,
    placesEnabled: Boolean?,
    isOnboarded: Boolean,
    requiresUnlock: Boolean,
    onPendingNavKeyConsumed: () -> Unit,
) {
    LaunchedEffect(pendingNavKey, placesEnabled, isOnboarded, requiresUnlock) {
        val pendingTarget = pendingNavKey?.let { placesNavigationTarget(it, placesEnabled) }
        val action =
            resolveNavBootstrapAction(
                backStack = backStack,
                isOnboarded = isOnboarded,
                requiresUnlock = requiresUnlock,
                pendingNavKey = pendingTarget,
            )
        var openedPendingTarget = false
        if (action is NavBootstrapAction.ResetTo) {
            val top = backStack.lastOrNull()
            if (action.key == OnboardingStart && top != null && top != BaseRoute) {
                Napier.w("Not onboarded; replacing restored back stack (top=$top) with onboarding")
            }
            backStack.clear()
            backStack.add(action.key)
            action.pendingKey?.let { target ->
                openPendingTarget(backStack, target)
                openedPendingTarget = true
            }
        }
        if (pendingTarget == null || !canOpenPendingDestination(pendingTarget, isOnboarded, requiresUnlock)) return@LaunchedEffect
        if (!openedPendingTarget) openPendingTarget(backStack, pendingTarget)
        onPendingNavKeyConsumed()
    }
}

private fun openPendingTarget(
    backStack: MutableList<NavKey>,
    target: NavKey,
) {
    if (target is WatchSettingsRoute && backStack.lastOrNull() == WatchSettingsRoute) return
    when {
        target is EntryEditorRoute && target.resumeRecording -> RecordingEditorDestination.open(backStack, target)
        target is BlueskySignInRoute -> applyPendingDestination(backStack, target)
        else -> addPlacesSafeTarget(backStack, target)
    }
}
