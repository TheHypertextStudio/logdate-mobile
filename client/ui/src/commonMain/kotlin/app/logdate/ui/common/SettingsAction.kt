@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.ui.platform.rememberSystemReduceMotion
import app.logdate.ui.theme.Spacing

/** Presentation only: the caller owns work, results, and localized feedback. */
enum class SettingsActionStatus {
    IDLE,
    BUSY,
    SUCCEEDED,
    FAILED,
}

@Composable
fun SettingsAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    status: SettingsActionStatus = SettingsActionStatus.IDLE,
    busyLabel: String = label,
    successLabel: String = label,
    error: String? = null,
    icon: ImageVector? = null,
) {
    val reduceMotion by rememberSystemReduceMotion()
    val duration = if (reduceMotion) 0 else 160
    Column(
        modifier = modifier.animateContentSize(tween(duration)),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Button(onClick = onClick, enabled = status != SettingsActionStatus.BUSY, modifier = Modifier.fillMaxWidth()) {
            if (status == SettingsActionStatus.BUSY) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (status == SettingsActionStatus.BUSY || icon != null) Spacer(Modifier.width(Spacing.sm))
            AnimatedContent(
                targetState =
                    when (status) {
                        SettingsActionStatus.BUSY -> busyLabel
                        SettingsActionStatus.SUCCEEDED -> successLabel
                        else -> label
                    },
                transitionSpec = {
                    (fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)))
                        .using(SizeTransform { _, _ -> tween(duration) })
                },
                label = "Settings action feedback",
            ) { text ->
                Text(text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        if (status == SettingsActionStatus.FAILED && error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
