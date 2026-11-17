@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.logdate.feature.core.settings.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notes
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.min
import androidx.compose.ui.zIndex
import app.logdate.shared.model.CloudObjectType
import app.logdate.ui.common.MaterialCard
import app.logdate.ui.motion.rememberDeviceParallax
import app.logdate.ui.platform.LocalLogDateHaptics
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.storage_usage_category_description
import logdate.client.feature.core.generated.resources.storage_usage_hide_details
import logdate.client.feature.core.generated.resources.storage_usage_show_details
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val STORAGE_USAGE_DETAILS_TAG = "storage_usage_details"
const val STORAGE_USAGE_TOGGLE_TAG = "storage_usage_toggle"
const val STORAGE_USAGE_SHAPES_TAG = "storage_usage_shapes"

/** The test tag of the bar's run for the [index]th largest kind of stored thing. */
fun storageSegmentTag(index: Int): String = "storage_usage_segment_$index"

/** The test tag of the list row for the [index]th largest kind of stored thing. */
fun storageCategoryTag(index: Int): String = "storage_usage_category_$index"

/** The test tag of the shape drawn for the [index]th largest kind of stored thing. */
fun storageShapeTag(index: Int): String = "storage_usage_shape_$index"

/** The test tag of the button that opens the [index]th largest kind of stored thing. */
fun storageActionTag(index: Int): String = "storage_usage_action_$index"

/** Somewhere in the app to look through one kind of stored thing: the button's words and what it opens. */
data class StorageKindAction(
    val label: String,
    val onClick: () -> Unit,
)

/**
 * How much of an account's storage is in use, and what fills it.
 *
 * Each kind of stored thing is drawn three ways that move together: a shape sized by its share, a
 * run of the bar, and a row in the list. Touching any of them singles that kind out everywhere,
 * says its share, and offers [actionFor] it; touching it again lets go. The arrow beside the title
 * opens and closes the list without choosing a kind.
 *
 * [status] is one line under the title, such as what the plan costs. [used] and [total] arrive
 * written for reading ("32 GB", "64% of 50 GB"); [usageDescription] is the one sentence a screen
 * reader hears for both. [fraction] is how full the storage is, from 0 to 1. Without [categories]
 * the bar is one tone and there is nothing to open.
 */
@Composable
fun StorageUsageCard(
    title: String,
    status: String?,
    used: String,
    total: String?,
    usageDescription: String,
    fraction: Float,
    categories: List<StorageCategory>,
    modifier: Modifier = Modifier,
    actionFor: @Composable (StorageCategory) -> StorageKindAction? = { null },
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val colors = MaterialTheme.colorScheme
    val haptics = LocalLogDateHaptics.current
    // LogDate Cloud's summary surface everywhere in Settings: secondary container with its paired ink.
    val ink = colors.onSecondaryContainer
    val shown = categories.filter { it.usedBytes > 0 }.sortedByDescending { it.usedBytes }
    // One family of tones, strongest for the largest kind. The card has one colour, and every kind
    // is that colour's ink in steps; the icon and the shape say which kind it is.
    val tones = shown.indices.map { ink.copy(alpha = TONES[it.coerceAtMost(TONES.lastIndex)]) }
    val marks = shown.map { kindMark(it.type) }
    val usedBytes = shown.sumOf { it.usedBytes }.coerceAtLeast(1)
    val filled = fraction.coerceIn(0f, 1f)
    // Each kind's share of all the storage there is, which is also how wide its run is.
    val shares = shown.map { filled * it.usedBytes / usedBytes }
    val percents = shares.map { percentText(it) }
    val actions = shown.map { actionFor(it) }
    val descriptions =
        shown.mapIndexed { index, category ->
            stringResource(
                Res.string.storage_usage_category_description,
                stringResource(category.name),
                category.formattedUsed,
                percents[index],
            )
        }
    val select: (Int) -> Unit = { index ->
        selected = if (selected == index) null else index
        expanded = true
        haptics.detentTick()
    }
    val palette =
        KindPalette(
            tones = tones,
            chosen = ink,
            resting = ink.copy(alpha = RESTING_INK),
            onStrong = colors.secondaryContainer,
            onQuiet = ink,
        )

    MaterialCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.secondaryContainer,
        contentColor = ink,
        contentPadding = Spacing.xl,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (status != null) {
                    Text(text = status, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = QUIET_INK))
                }
            }
            if (shown.isNotEmpty()) {
                val turn by animateFloatAsState(if (expanded) HALF_TURN else 0f, MaterialTheme.motionScheme.fastSpatialSpec())
                IconButton(
                    onClick = {
                        expanded = !expanded
                        if (!expanded) selected = null
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = ink.copy(alpha = BUTTON_INK), contentColor = ink),
                    modifier = Modifier.size(TOGGLE_SIZE).testTag(STORAGE_USAGE_TOGGLE_TAG),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription =
                            stringResource(if (expanded) Res.string.storage_usage_hide_details else Res.string.storage_usage_show_details),
                        modifier = Modifier.rotate(turn),
                    )
                }
            }
        }
        if (shown.isNotEmpty()) {
            val look by rememberDeviceParallax()
            KindShapes(
                shares = shares,
                marks = marks,
                palette = palette,
                selected = selected,
                onSelect = select,
                look = look,
                modifier = Modifier.padding(top = Spacing.lg).testTag(STORAGE_USAGE_SHAPES_TAG),
            )
        }
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg)
                    .clearAndSetSemantics { contentDescription = usageDescription },
        ) {
            Text(used, style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f).alignByBaseline())
            if (total != null) {
                Text(
                    total,
                    style = MaterialTheme.typography.labelLarge,
                    color = ink.copy(alpha = QUIET_INK),
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (total != null) {
            UsageBar(
                shares = if (shown.isEmpty()) listOf(filled) else shares,
                labels = percents,
                palette = if (shown.isEmpty()) palette.copy(tones = listOf(ink)) else palette,
                descriptions = descriptions,
                selected = selected,
                onSelect = select.takeIf { shown.isNotEmpty() },
                track = ink.copy(alpha = TRACK_INK),
            )
        }
        AnimatedVisibility(
            visible = expanded && shown.isNotEmpty(),
            enter =
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit =
                shrinkVertically(
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Column(modifier = Modifier.padding(top = Spacing.sm).testTag(STORAGE_USAGE_DETAILS_TAG)) {
                shown.forEachIndexed { index, category ->
                    CategoryRow(
                        name = stringResource(category.name),
                        size = category.formattedUsed,
                        percent = percents[index],
                        icon = marks[index].icon,
                        tone = palette.toneFor(index, selected),
                        iconTone = palette.iconToneFor(index, selected),
                        isSelected = selected == index,
                        isDimmed = selected != null && selected != index,
                        action = actions[index],
                        actionTag = storageActionTag(index),
                        onClick = { select(index) },
                        modifier = Modifier.testTag(storageCategoryTag(index)),
                    )
                }
            }
        }
    }
}

/** The tones the kinds are drawn in, and how choosing one kind changes them. */
private data class KindPalette(
    val tones: List<Color>,
    val chosen: Color,
    val resting: Color,
    val onStrong: Color,
    val onQuiet: Color,
) {
    /** Choosing a kind leaves it at full strength and sets every other kind back to one quiet tone. */
    fun toneFor(
        index: Int,
        selected: Int?,
    ): Color =
        when (selected) {
            null -> tones[index.coerceAtMost(tones.lastIndex)]
            index -> chosen
            else -> resting
        }

    /** The colour of an icon or figure drawn on top of [toneFor]: light on a strong tone, ink on a quiet one. */
    fun iconToneFor(
        index: Int,
        selected: Int?,
    ): Color =
        when {
            selected == index -> onStrong
            selected != null -> onQuiet
            index < FIRST_QUIET_TONE -> onStrong
            else -> onQuiet
        }
}

/** The icon and the outline that stand for one kind of stored thing. */
private class KindMark(
    val icon: ImageVector,
    val shape: Shape,
)

@Composable
private fun kindMark(type: CloudObjectType?): KindMark =
    when (type) {
        CloudObjectType.IMAGE_NOTES -> KindMark(Icons.Rounded.Image, MaterialShapes.Cookie9Sided.toShape())
        CloudObjectType.VIDEO_NOTES -> KindMark(Icons.Rounded.Videocam, MaterialShapes.Sunny.toShape())
        CloudObjectType.VOICE_NOTES -> KindMark(Icons.Rounded.Mic, MaterialShapes.Clover4Leaf.toShape())
        CloudObjectType.TEXT_NOTES -> KindMark(Icons.Rounded.Notes, MaterialShapes.Cookie4Sided.toShape())
        CloudObjectType.JOURNAL_DATA -> KindMark(Icons.Filled.Book, MaterialShapes.Flower.toShape())
        else -> KindMark(Icons.Outlined.Storage, CircleShape)
    }

/**
 * The kinds of stored things as a loose row of shapes, each as large as its share. They overlap,
 * lean a little, and shift with the device's tilt; the chosen one straightens up and grows.
 *
 * The shapes are a picture of the bar beneath them, in the same order. A screen reader is given
 * the bar and the list, so the shapes are hidden from it.
 */
@Composable
private fun KindShapes(
    shares: List<Float>,
    marks: List<KindMark>,
    palette: KindPalette,
    selected: Int?,
    onSelect: (Int) -> Unit,
    look: Offset,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth().clearAndSetSemantics { }) {
        val largest = shares.max().coerceAtLeast(MIN_REST)
        // Area follows share, so width follows its square root.
        val wanted = shares.map { lerp(SMALLEST_SHAPE, LARGEST_SHAPE, sqrt(it / largest)) }
        // The row takes most of the card's width: a wider card gets larger shapes, not more margin.
        val fit = (maxWidth * ROW_SHARE / wanted.rowWidth()).coerceAtMost(LARGEST_FIT)
        val sizes = wanted.map { it * fit }
        val height = SHAPES_HEIGHT * fit
        val rowStart = (maxWidth - sizes.rowWidth()) / 2
        Box(Modifier.fillMaxWidth().height(height))
        var x = rowStart
        sizes.forEachIndexed { index, size ->
            val isChosen = selected == index
            val grow by animateFloatAsState(
                when {
                    isChosen -> CHOSEN_GROWTH
                    selected != null -> RESTING_SHRINK
                    else -> 1f
                },
                MaterialTheme.motionScheme.defaultSpatialSpec(),
            )
            val lean by animateFloatAsState(if (isChosen) 0f else LEANS[index % LEANS.size], MaterialTheme.motionScheme.slowSpatialSpec())
            val tone by animateColorAsState(palette.toneFor(index, selected), MaterialTheme.motionScheme.fastEffectsSpec())
            val iconTone by animateColorAsState(palette.iconToneFor(index, selected), MaterialTheme.motionScheme.fastEffectsSpec())
            // Smaller shapes sit nearer, so they travel further when the device tilts.
            val depth = PARALLAX_STEP * (index + 1)
            // Alternate high and low so the row reads as a cluster, not a line.
            val y = (height - size) * if (index % 2 == 0) HIGH else LOW
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .offset(x = x + depth * look.x, y = y + depth * look.y)
                        .size(size)
                        .zIndex(if (isChosen) 1f else 0f)
                        .graphicsLayer {
                            scaleX = grow
                            scaleY = grow
                            rotationZ = lean
                        }.clip(marks[index].shape)
                        .background(tone)
                        .clickable { onSelect(index) }
                        .testTag(storageShapeTag(index)),
            ) {
                Icon(
                    imageVector = marks[index].icon,
                    contentDescription = null,
                    tint = iconTone,
                    // The icon stays upright while its shape leans.
                    modifier = Modifier.size(size * ICON_SHARE).graphicsLayer { rotationZ = -lean },
                )
            }
            if (index < sizes.lastIndex) x += size - min(size, sizes[index + 1]) * OVERLAP
        }
    }
}

/** How wide a row of shapes of these sizes is once each overlaps the next. */
private fun List<Dp>.rowWidth(): Dp {
    var width = 0.dp
    forEachIndexed { index, size ->
        width += size
        if (index < lastIndex) width -= min(size, this[index + 1]) * OVERLAP
    }
    return width
}

/**
 * A chunky track with one run per kind. The chosen run stands taller and rounder and says its
 * share, and the rest fall back to one quiet tone, so the bar itself answers a touch. Every run is
 * a full-height touch target however low it is drawn.
 */
@Composable
private fun UsageBar(
    shares: List<Float>,
    labels: List<String>,
    palette: KindPalette,
    descriptions: List<String>,
    selected: Int?,
    onSelect: ((Int) -> Unit)?,
    track: Color,
    modifier: Modifier = Modifier,
) {
    // A sliver of a run cannot be seen or touched, so every kind gets a minimum width.
    val widths = shares.map { if (it > 0f) it.coerceAtLeast(MIN_RUN) else 0f }
    val rest = (1f - widths.sum()).takeIf { it > MIN_REST } ?: 0f
    val lastRun = widths.indexOfLast { it > 0f }
    Row(
        modifier = modifier.fillMaxWidth().height(BAR_TOUCH_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(RUN_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widths.forEachIndexed { index, width ->
            if (width <= 0f) return@forEachIndexed
            val isChosen = selected == index
            val height by animateDpAsState(if (isChosen) BAR_CHOSEN_HEIGHT else BAR_HEIGHT, MaterialTheme.motionScheme.fastSpatialSpec())
            val start by cornerAsState(
                if (isChosen) {
                    CHOSEN_CORNER
                } else if (index == 0) {
                    END_CORNER
                } else {
                    INNER_CORNER
                },
            )
            val end by cornerAsState(
                if (isChosen) {
                    CHOSEN_CORNER
                } else if (index == lastRun && rest == 0f) {
                    END_CORNER
                } else {
                    INNER_CORNER
                },
            )
            val tone by animateColorAsState(palette.toneFor(index, selected), MaterialTheme.motionScheme.fastEffectsSpec())
            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(width)
                        .fillMaxHeight()
                        .then(
                            if (onSelect != null) {
                                Modifier
                                    .selectable(
                                        selected = isChosen,
                                        interactionSource = null,
                                        indication = null,
                                        onClick = { onSelect(index) },
                                    ).semantics { contentDescription = descriptions[index] }
                                    .testTag(storageSegmentTag(index))
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
            ) {
                // The chosen run says its share, where the run is wide enough to hold the figure.
                val fits = maxWidth >= LABEL_MIN_WIDTH
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(height)
                            .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                            .background(tone),
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isChosen && fits && index < labels.size,
                        enter =
                            fadeIn(
                                MaterialTheme.motionScheme.fastEffectsSpec(),
                            ) + scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                    ) {
                        Text(
                            text = labels.getOrElse(index) { "" },
                            style = MaterialTheme.typography.labelLarge,
                            color = palette.onStrong,
                            maxLines = 1,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
            }
        }
        if (rest > 0f) {
            val start = if (lastRun >= 0) INNER_CORNER else END_CORNER
            Box(
                Modifier
                    .weight(rest)
                    .height(BAR_HEIGHT)
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = END_CORNER, bottomEnd = END_CORNER))
                    .background(track),
            )
        }
    }
}

@Composable
private fun cornerAsState(target: Dp) = animateDpAsState(target, MaterialTheme.motionScheme.fastSpatialSpec())

/**
 * One kind of stored thing. Choosing it here chooses its shape and its run in the bar, and the
 * other way round. Once chosen, the row opens to offer [action], the place to look through it.
 */
@Composable
private fun CategoryRow(
    name: String,
    size: String,
    percent: String,
    icon: ImageVector,
    tone: Color,
    iconTone: Color,
    isSelected: Boolean,
    isDimmed: Boolean,
    action: StorageKindAction?,
    actionTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strength by animateFloatAsState(if (isDimmed) DIMMED else 1f, MaterialTheme.motionScheme.fastEffectsSpec())
    val markTone by animateColorAsState(tone, MaterialTheme.motionScheme.fastEffectsSpec())
    val markIconTone by animateColorAsState(iconTone, MaterialTheme.motionScheme.fastEffectsSpec())
    val weight = if (isSelected) FontWeight.SemiBold else null
    Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = strength }) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = ROW_HEIGHT)
                    .selectable(selected = isSelected, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(MARK_SIZE).background(markTone, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = markIconTone, modifier = Modifier.size(MARK_ICON_SIZE))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = weight,
                modifier = Modifier.padding(start = Spacing.md),
            )
            // The share is a quieter, smaller voice than the name and the size it sits between.
            Text(
                text = percent,
                style = MaterialTheme.typography.labelMedium,
                color = LocalContentColor.current.copy(alpha = QUIET_INK),
                modifier = Modifier.weight(1f).padding(start = Spacing.sm),
            )
            Text(size, style = MaterialTheme.typography.bodyLarge, fontWeight = weight)
        }
        AnimatedVisibility(
            visible = isSelected && action != null,
            enter =
                expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit =
                shrinkVertically(
                    MaterialTheme.motionScheme.defaultSpatialSpec(),
                ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            // Inverse of the card: its ink as the button, its colour as the words.
            Button(
                onClick = { action?.onClick?.invoke() },
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        contentColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                modifier = Modifier.padding(start = MARK_SIZE + Spacing.md, bottom = Spacing.sm).testTag(actionTag),
            ) {
                Text(action?.label.orEmpty())
            }
        }
    }
}

/** A share from 0 to 1 as a whole percentage, with anything under one percent written as such. */
private fun percentText(share: Float): String {
    val percent = (share * PERCENT).roundToInt()
    return if (percent < 1 && share > 0f) "<1%" else "$percent%"
}

private const val PERCENT = 100
private const val QUIET_INK = 0.78f
private const val RESTING_INK = 0.3f
private const val TRACK_INK = 0.1f
private const val BUTTON_INK = 0.08f
private const val DIMMED = 0.55f
private const val HALF_TURN = 180f
private const val MIN_RUN = 0.035f
private const val MIN_REST = 0.01f
private const val CHOSEN_GROWTH = 1.14f
private const val RESTING_SHRINK = 0.92f
private const val OVERLAP = 0.16f
private const val ROW_SHARE = 0.94f
private const val LARGEST_FIT = 1.5f
private const val ICON_SHARE = 0.4f
private const val HIGH = 0.15f
private const val LOW = 0.85f

/** The first tone light enough that ink reads on it better than the card's colour does. */
private const val FIRST_QUIET_TONE = 2
private val TONES = listOf(1f, 0.7f, 0.48f, 0.32f)
private val LEANS = listOf(-8f, 10f, -14f, 6f, -4f)
private val SHAPES_HEIGHT = 136.dp
private val LARGEST_SHAPE = 128.dp
private val SMALLEST_SHAPE = 56.dp
private val PARALLAX_STEP = 3.dp
private val LABEL_MIN_WIDTH = 40.dp
private val BAR_TOUCH_HEIGHT = 48.dp
private val BAR_HEIGHT = 32.dp
private val BAR_CHOSEN_HEIGHT = 44.dp
private val RUN_GAP = 3.dp
private val END_CORNER = 16.dp
private val INNER_CORNER = 4.dp
private val CHOSEN_CORNER = 12.dp
private val MARK_SIZE = 32.dp
private val MARK_ICON_SIZE = 18.dp
private val ROW_HEIGHT = 48.dp
private val TOGGLE_SIZE = 48.dp
