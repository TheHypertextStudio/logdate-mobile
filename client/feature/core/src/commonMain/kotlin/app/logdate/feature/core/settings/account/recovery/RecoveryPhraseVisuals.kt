@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.recovery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.recovery_phrase_backup_visual
import logdate.client.feature.core.generated.resources.recovery_phrase_device_visual
import logdate.client.feature.core.generated.resources.recovery_phrase_diagram_description
import org.jetbrains.compose.resources.stringResource

/** Concealment is visual only: this illustration never receives secret text. */
@Composable
internal fun RecoveryPhrasePrivacyVisual(
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    val cardShape = MaterialTheme.shapes.extraLarge
    val scaledCardShape =
        if (scale == 1f) {
            cardShape
        } else {
            cardShape.copy(
                topStart = ScaledPrivacyCorner(cardShape.topStart, scale),
                topEnd = ScaledPrivacyCorner(cardShape.topEnd, scale),
                bottomStart = ScaledPrivacyCorner(cardShape.bottomStart, scale),
                bottomEnd = ScaledPrivacyCorner(cardShape.bottomEnd, scale),
            )
        }
    Box(modifier.size(176.dp * scale, 136.dp * scale)) {
        Surface(
            modifier = Modifier.align(Alignment.TopStart).size(152.dp * scale, 112.dp * scale).graphicsLayer { rotationZ = -5f },
            shape = scaledCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(24.dp * scale),
                verticalArrangement = Arrangement.spacedBy(12.dp * scale),
            ) {
                listOf(0.85f, 1f, 0.65f).forEach { fraction ->
                    Box(
                        Modifier.fillMaxWidth(fraction).height(8.dp * scale).background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            RoundedCornerShape(4.dp * scale),
                        ),
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).size(56.dp * scale),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.VisibilityOff, contentDescription = null, modifier = Modifier.size(28.dp * scale))
            }
        }
    }
}

private class ScaledPrivacyCorner(
    private val source: CornerSize,
    private val scale: Float,
) : CornerSize {
    override fun toPx(
        shapeSize: Size,
        density: Density,
    ): Float = source.toPx(Size(shapeSize.width / scale, shapeSize.height / scale), density) * scale
}

@Composable
internal fun RecoveryRestoreDiagram(modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.recovery_phrase_diagram_description)
    Row(
        modifier =
            modifier.fillMaxWidth().clearAndSetSemantics {
                role = Role.Image
                contentDescription = description
            },
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RecoveryDiagramNode { Icon(Icons.Outlined.Cloud, contentDescription = null, modifier = Modifier.size(36.dp)) }
            Text(
                stringResource(Res.string.recovery_phrase_backup_visual),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.padding(top = 24.dp).size(24.dp))
        Column(
            Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RecoveryDiagramNode { Icon(Icons.Outlined.Smartphone, contentDescription = null, modifier = Modifier.size(36.dp)) }
            Text(
                stringResource(Res.string.recovery_phrase_device_visual),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun RecoveryDiagramNode(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(72.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}
