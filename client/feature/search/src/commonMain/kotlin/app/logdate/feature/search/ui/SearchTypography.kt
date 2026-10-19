@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.search.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/**
 * Gives search the type scale it is designed with. The app's body text is 16sp; search sets
 * supporting lines in 14sp on a 20sp line, so a row's text and its supporting line read apart.
 */
@Composable
internal fun SearchTypography(content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        shapes = MaterialTheme.shapes,
        typography = typography.copy(bodyMedium = typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp)),
        content = content,
    )
}
