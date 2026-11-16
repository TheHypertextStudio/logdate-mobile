@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.onboarding.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.logdate.ui.firstlaunch.PaperPiece
import app.logdate.ui.platform.rememberSystemReduceMotion
import coil3.compose.AsyncImage
import logdate.client.feature.onboarding.generated.resources.Res
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_description
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_month
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_photo
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_thought
import logdate.client.feature.onboarding.generated.resources.onboarding_scene_weekday
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A date, a photo, a thought and a voice note, laid out like paper on a table and seen from above.
 *
 * Everything is flat and lit from directly overhead: each piece only shifts, turns a few degrees
 * and casts a soft shadow straight down. Tilting the pieces in three dimensions skewed their text
 * and needed drawn-on thickness and gloss to explain the angle, which read as plastic.
 *
 * [parallax] is how the device is being turned, from -1 to 1 on each axis. Pieces that sit higher
 * above the table travel further with it and their shadows lean the other way, so turning the
 * device looks around the pile.
 */
@Composable
internal fun OnboardingObjects(
    overview: Boolean,
    animateScene: Boolean,
    selectedPhotoUri: String? = null,
    onPhotoReady: (String) -> Unit = {},
    onPhotoFailed: (String) -> Unit = {},
    parallax: Offset = Offset.Zero,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        OnboardingObjectScene(overview, animateScene, selectedPhotoUri, onPhotoReady, onPhotoFailed, parallax)
    }
}

@Composable
private fun OnboardingObjectScene(
    overview: Boolean,
    animateScene: Boolean,
    selectedPhotoUri: String?,
    onPhotoReady: (String) -> Unit,
    onPhotoFailed: (String) -> Unit,
    parallax: Offset,
) {
    val reducedMotion by rememberSystemReduceMotion()
    val moving = animateScene && !reducedMotion
    val progress by animateFloatAsState(
        targetValue = if (overview) 1f else 0f,
        animationSpec = if (moving) tween(1_100, easing = FastOutSlowInEasing) else snap(),
        label = "Objects arrange into timeline",
    )
    val arrival = remember { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(moving) {
        if (moving) arrival.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing)) else arrival.snapTo(1f)
    }
    val phase = if (moving) scenePhase() else 0f
    // How loosely the pieces lie: 1 while scattered, 0 once they are lined up on the timeline.
    val loose = 1f - progress
    // Lined up, the pieces still answer the device, but less: they are lying flatter.
    val look = parallax * (0.45f + 0.55f * loose)
    val colors = MaterialTheme.colorScheme
    val description = stringResource(Res.string.onboarding_scene_description)
    BoxWithConstraints(
        Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val scale = min(maxWidth.value / SCENE_WIDTH, maxHeight.value / SCENE_HEIGHT).coerceAtMost(1.45f)
        Box(
            Modifier.requiredSize(SCENE_WIDTH.dp, SCENE_HEIGHT.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = arrival.value
                translationY = (1f - arrival.value) * 32.dp.toPx()
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(listOf(colors.tertiaryContainer.copy(alpha = 0.45f), Color.Transparent)),
                    radius = size.width * 0.66f,
                    center = Offset(size.width * 0.48f - look.x * 6.dp.toPx(), size.height * 0.46f - look.y * 6.dp.toPx()),
                )
                val lineX = center.x + TIMELINE_X.dp.toPx()
                drawLine(
                    color = colors.outlineVariant.copy(alpha = progress),
                    start = Offset(lineX, center.y + (DATE_ROW - 34f).dp.toPx()),
                    end = Offset(lineX, center.y + VOICE_ROW.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                )
                listOf(PHOTO_ROW, THOUGHT_ROW, VOICE_ROW).forEach { row ->
                    drawCircle(colors.primary.copy(alpha = progress), 3.5.dp.toPx(), Offset(lineX, center.y + row.dp.toPx()))
                }
            }
            // Scattered: the date peeks out from behind the photo. Lined up: it heads the timeline.
            PaperPiece(
                x = mix(90f, TIMELINE_X, progress),
                y = mix(-112f, DATE_ROW - 74f, progress) + sin(phase + 0.4f) * 2.5f * loose,
                rotation = (7f + cos(phase + 0.4f) * 0.6f) * loose,
                scale = mix(1f, 0.7f, progress),
                lift = 0.55f * loose,
                look = look * 5f,
                width = 104.dp,
                height = 116.dp,
                shape = RoundedCornerShape(22.dp),
                color = colors.tertiaryContainer,
            ) { DateObjectContent() }

            PaperPiece(
                x = mix(-50f, ENTRY_LEFT + 176f * 0.78f / 2f, progress),
                y = mix(-44f, PHOTO_ROW, progress) + cos(phase + 1.4f) * 3f * loose,
                rotation = (-6f + sin(phase + 1.4f) * 0.5f) * loose,
                scale = mix(1f, 0.78f, progress),
                lift = 0.8f * loose,
                look = look * 9f,
                width = 176.dp,
                height = 198.dp,
                shape = RoundedCornerShape(8.dp),
                color = colors.surfaceContainerLowest,
            ) { PhotoObjectContent(selectedPhotoUri, onPhotoReady, onPhotoFailed) }

            PaperPiece(
                x = mix(62f, ENTRY_LEFT + 190f * 0.82f / 2f, progress),
                y = mix(66f, THOUGHT_ROW, progress) + sin(phase + 2.5f) * 2.5f * loose,
                rotation = (3f + cos(phase + 2.5f) * 0.5f) * loose,
                scale = mix(1f, 0.82f, progress),
                lift = loose,
                look = look * 13f,
                width = 190.dp,
                height = 84.dp,
                shape = RoundedCornerShape(18.dp),
                color = colors.surfaceContainerLowest,
            ) { ThoughtObjectContent() }

            PaperPiece(
                x = mix(-56f, ENTRY_LEFT + 168f * 0.82f / 2f, progress),
                y = mix(144f, VOICE_ROW, progress) + cos(phase + 3.4f) * 2.5f * loose,
                rotation = (-4f + sin(phase + 3.4f) * 0.6f) * loose,
                scale = mix(1f, 0.82f, progress),
                lift = 0.9f * loose,
                look = look * 11f,
                width = 168.dp,
                height = 46.dp,
                shape = CircleShape,
                color = colors.surfaceContainerLowest,
            ) { VoiceObjectContent() }
        }
    }
}

// The scene is drawn at this size and scaled to fit. Positions are measured from its centre.
private const val SCENE_WIDTH = 360f
private const val SCENE_HEIGHT = 410f
private const val TIMELINE_X = -112f
private const val ENTRY_LEFT = -84f
private const val DATE_ROW = -62f
private const val PHOTO_ROW = -40f
private const val THOUGHT_ROW = 84f
private const val VOICE_ROW = 152f

@Composable
private fun scenePhase(): Float {
    val transition = rememberInfiniteTransition(label = "Scene drift")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Restart),
        label = "Scene drift phase",
    )
    return phase
}

private fun mix(
    from: Float,
    to: Float,
    fraction: Float,
): Float = from + (to - from) * fraction

@Composable
private fun DateObjectContent() {
    val ink = MaterialTheme.colorScheme.onTertiaryContainer
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(Res.string.onboarding_scene_month),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = ink.copy(alpha = 0.72f),
        )
        Text(
            "14",
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = ink,
        )
        Text(
            stringResource(Res.string.onboarding_scene_weekday),
            style = MaterialTheme.typography.labelSmall,
            color = ink.copy(alpha = 0.72f),
        )
    }
}

/** A print: a square picture with a white margin, deeper at the bottom where the time is written. */
@Composable
private fun PhotoObjectContent(
    selectedPhotoUri: String?,
    onPhotoReady: (String) -> Unit,
    onPhotoFailed: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(start = 8.dp, top = 8.dp, end = 8.dp)) {
        Box(Modifier.size(PHOTO_SIDE).clip(RoundedCornerShape(3.dp))) {
            if (selectedPhotoUri == null) {
                // The picture is one sheet of four; the print shows its first frame.
                Image(
                    painterResource(Res.drawable.onboarding_scene_photo),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(PHOTO_SIDE * 2),
                )
            } else {
                AsyncImage(
                    model = selectedPhotoUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onSuccess = { onPhotoReady(selectedPhotoUri) },
                    onError = { onPhotoFailed(selectedPhotoUri) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(horizontal = 2.dp), contentAlignment = Alignment.CenterStart) {
            Text("16:42", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val PHOTO_SIDE = 160.dp

@Composable
private fun ThoughtObjectContent() {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Text("16:44", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(Res.string.onboarding_scene_thought),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun VoiceObjectContent() {
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary
    Row(
        Modifier.fillMaxSize().padding(start = 9.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(Modifier.size(28.dp)) {
            drawCircle(accent)
            val arrow = 5.dp.toPx()
            drawPath(
                Path().apply {
                    moveTo(center.x - arrow * 0.7f, center.y - arrow)
                    lineTo(center.x + arrow, center.y)
                    lineTo(center.x - arrow * 0.7f, center.y + arrow)
                    close()
                },
                onAccent,
            )
        }
        Canvas(Modifier.weight(1f).height(22.dp)) {
            val levels = listOf(0.3f, 0.55f, 0.85f, 0.45f, 1f, 0.7f, 0.4f, 0.82f, 0.6f, 0.92f, 0.5f, 0.3f, 0.45f, 0.25f)
            levels.forEachIndexed { index, level ->
                val x = index * size.width / (levels.size - 1)
                drawLine(
                    accent.copy(alpha = 0.7f),
                    Offset(x, center.y - level * 8.dp.toPx()),
                    Offset(x, center.y + level * 8.dp.toPx()),
                    2.dp.toPx(),
                    StrokeCap.Round,
                )
            }
        }
        Text("0:12", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
