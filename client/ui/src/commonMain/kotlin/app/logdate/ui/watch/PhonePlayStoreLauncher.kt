package app.logdate.ui.watch

import androidx.compose.runtime.Composable

/** Opens LogDate's Play Store listing on this phone so the user can update the mobile app. */
@Composable
expect fun rememberPhonePlayStoreLauncher(): (() -> Boolean)?
