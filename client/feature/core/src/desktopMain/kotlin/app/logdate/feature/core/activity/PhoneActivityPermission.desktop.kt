@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.activity

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberPhoneActivityPermission(onGranted: () -> Unit): PhoneActivityPermission =
    PhoneActivityPermission(granted = false, requesting = false, denied = false, request = {}, openSettings = {})
