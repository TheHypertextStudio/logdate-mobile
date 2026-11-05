package app.logdate.client.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.logdate.feature.core.AppAuthState
import app.logdate.feature.core.AppUnlockState
import app.logdate.ui.adaptive.HingeAwareOverlay
import app.logdate.ui.common.LocalPrivateModalVisible
import app.logdate.ui.theme.Spacing
import logdate.app.composemain.generated.resources.Res
import logdate.app.composemain.generated.resources.ic_launcher_round_google_play
import logdate.app.composemain.generated.resources.lock_screen_retry_unlock
import logdate.app.composemain.generated.resources.lock_screen_retry_unlock_subtitle
import logdate.app.composemain.generated.resources.lock_screen_setup_security
import logdate.app.composemain.generated.resources.lock_screen_setup_security_subtitle
import logdate.app.composemain.generated.resources.lock_screen_unlock_subtitle
import logdate.app.composemain.generated.resources.lock_screen_use_passcode
import logdate.app.composemain.generated.resources.lock_screen_verification_failed
import logdate.app.composemain.generated.resources.lock_screen_verifying
import logdate.app.composemain.generated.resources.lock_screen_welcome_back
import logdate.app.composemain.generated.resources.lock_screen_welcome_back_no_name
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Keeps [content] composed while locked so an unfinished editor survives authentication.
 * Locked content is not placed and cannot receive input or expose descendant semantics.
 */
@Suppress("ktlint:standard:function-naming")
@Composable
fun LockableContent(
    isLocked: Boolean,
    displayName: String,
    onUsePasscode: () -> Unit,
    modifier: Modifier = Modifier,
    authState: AppAuthState = AppAuthState.REQUIRE_PROMPT,
    unlockState: AppUnlockState = AppUnlockState.Idle,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(isLocked) {
        if (isLocked) focusManager.clearFocus(force = true)
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .focusProperties { canFocus = !isLocked }
                    .onPreviewKeyEvent { isLocked }
                    .then(if (isLocked) Modifier.clearAndSetSemantics {} else Modifier)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            if (!isLocked) placeable.placeRelative(0, 0)
                        }
                    },
        ) {
            CompositionLocalProvider(LocalPrivateModalVisible provides !isLocked) {
                content()
            }
        }
        if (isLocked) {
            LockScreen(
                displayName = displayName,
                onUsePasscode = onUsePasscode,
                authState = authState,
                unlockState = unlockState,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming")
@Composable
private fun LockScreen(
    displayName: String,
    onUsePasscode: () -> Unit,
    authState: AppAuthState,
    unlockState: AppUnlockState,
    modifier: Modifier = Modifier,
) {
    val greeting =
        if (displayName.isNotBlank()) {
            stringResource(Res.string.lock_screen_welcome_back, displayName)
        } else {
            stringResource(Res.string.lock_screen_welcome_back_no_name)
        }

    val enrollmentRequired = authState == AppAuthState.REQUEST_ENROLLMENT
    val retryRequired = authState in setOf(AppAuthState.UNKNOWN, AppAuthState.UNSUPPORTED, AppAuthState.UPDATE_REQUIRED)
    val subtitle =
        when {
            enrollmentRequired -> Res.string.lock_screen_setup_security_subtitle
            retryRequired -> Res.string.lock_screen_retry_unlock_subtitle
            else -> Res.string.lock_screen_unlock_subtitle
        }
    val action =
        when {
            enrollmentRequired -> Res.string.lock_screen_setup_security
            retryRequired -> Res.string.lock_screen_retry_unlock
            else -> Res.string.lock_screen_use_passcode
        }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.primary),
    ) {
        // Keep the obscuring background full-screen, but pin the greeting and unlock action to a
        // single physical pane so they stay reachable and clear of a foldable hinge.
        HingeAwareOverlay {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                Column(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .widthIn(max = 560.dp)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = maxHeight)
                            .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(
                        painter = painterResource(Res.drawable.ic_launcher_round_google_play),
                        contentDescription = null,
                        modifier = Modifier.size(96.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        text = stringResource(subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.xl))
                    FilledTonalButton(
                        onClick = onUsePasscode,
                        enabled = unlockState != AppUnlockState.Authenticating,
                    ) {
                        if (unlockState == AppUnlockState.Authenticating) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(Spacing.sm))
                        }
                        Text(
                            stringResource(if (unlockState == AppUnlockState.Authenticating) Res.string.lock_screen_verifying else action),
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (unlockState == AppUnlockState.Failed) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(Res.string.lock_screen_verification_failed),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
    }
}
