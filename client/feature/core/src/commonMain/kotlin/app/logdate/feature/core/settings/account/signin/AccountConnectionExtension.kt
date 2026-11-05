package app.logdate.feature.core.settings.account.signin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

typealias AccountConnectionSection = @Composable () -> Unit
typealias AccountConnectionProvider = @Composable () -> AccountConnectionSection?

/** Only the Android host supplies this optional Account extension; other hosts keep their existing composition. */
val LocalAccountConnectionProvider = staticCompositionLocalOf<AccountConnectionProvider?> { null }
