@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.account

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.logdate.ui.firstlaunch.PaperPiece

/**
 * The same day on a phone and on a tablet, with LogDate Cloud between them.
 *
 * It is drawn from the paper pieces of the opening screen, so LogDate Cloud looks like part of
 * LogDate and what it does is shown, not described: what you add on one device is on the other.
 * The picture is decoration; the step's words carry the meaning.
 */
@Composable
fun LogDateCloudHero(
    modifier: Modifier = Modifier,
    look: Offset = Offset.Zero,
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxWidth().height(HERO_HEIGHT.dp).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        // Drawn at one size and scaled to the room it is given, so it shrinks in a short step.
        val scale = minOf(maxWidth.value / HERO_WIDTH, maxHeight.value / HERO_HEIGHT, 1.15f)
        Box(
            Modifier.requiredSize(HERO_WIDTH.dp, HERO_HEIGHT.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(colors.tertiaryContainer.copy(alpha = 0.5f), Color.Transparent)),
                    radius = size.width * 0.5f,
                    center = Offset(center.x - look.x * 4.dp.toPx(), center.y - look.y * 4.dp.toPx()),
                )
            }
            PaperPiece(
                x = 44f,
                y = -6f,
                width = 176.dp,
                height = 128.dp,
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerLowest,
                rotation = 4f,
                lift = 0.5f,
                look = look * 5f,
            ) { DayOnADevice(wide = true) }
            PaperPiece(
                x = -66f,
                y = 10f,
                width = 100.dp,
                height = 170.dp,
                shape = RoundedCornerShape(24.dp),
                color = colors.surfaceContainerLowest,
                rotation = -6f,
                lift = 0.9f,
                look = look * 9f,
            ) { DayOnADevice(wide = false) }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = (2f + look.x * 12f).dp, y = (58f + look.y * 12f).dp)
                        .size(52.dp)
                        .dropShadow(
                            CircleShape,
                            Shadow(radius = 14.dp, color = colors.primary, offset = DpOffset(0.dp, 6.dp), alpha = 0.28f),
                        ).background(colors.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Cloud, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(28.dp))
            }
        }
    }
}

private const val HERO_WIDTH = 300f
private const val HERO_HEIGHT = 208f

/** One day as it appears in LogDate: its date, a photo, a few words and a voice note. */
@Composable
private fun DayOnADevice(wide: Boolean) {
    val colors = MaterialTheme.colorScheme
    val line = colors.outlineVariant
    if (wide) {
        Row(Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(34.dp, 38.dp).background(colors.tertiaryContainer, RoundedCornerShape(9.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.fillMaxWidth().height(52.dp).background(colors.primaryContainer, RoundedCornerShape(8.dp)))
                Box(Modifier.fillMaxWidth(0.9f).height(6.dp).background(line, CircleShape))
                Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(line, CircleShape))
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(26.dp, 30.dp).background(colors.tertiaryContainer, RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth().height(50.dp).background(colors.primaryContainer, RoundedCornerShape(8.dp)))
            Box(Modifier.fillMaxWidth(0.9f).height(6.dp).background(line, CircleShape))
            Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(line, CircleShape))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(12.dp).background(colors.primary, CircleShape))
                Box(Modifier.width(44.dp).height(6.dp).background(colors.primary.copy(alpha = 0.45f), CircleShape))
            }
        }
    }
}
