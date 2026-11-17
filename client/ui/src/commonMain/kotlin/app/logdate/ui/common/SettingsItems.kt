@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import app.logdate.ui.theme.Spacing

/**
 * A titled group of settings rows. Emit rows directly in [content]: the container spaces
 * direct layout children. A Column around several rows removes their automatic separation.
 * See docs/reference/standards/settings-ui.md for component choices and examples.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier
                    .padding(vertical = Spacing.sm)
                    .disabledAlpha(LocalSettingsEnabled.current),
        )

        MaterialContainer {
            content()
        }
    }
}

/**
 * A simple settings item with a slot for title and description and optional actions.
 *
 * @param title The title of the settings item
 * @param description The description text for the settings item
 * @param overline Optional overline text for additional context or grouping
 * @param onClick Callback when the item is clicked
 * @param enabled Whether the item is interactive; when false it grays out and ignores taps
 */
@Composable
fun SimpleSettingsItem(
    title: String,
    description: String?,
    overline: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = LocalSettingsEnabled.current,
    leadingContent: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    SettingsRow(
        enabled = enabled,
        leadingContent = leadingContent,
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        overlineContent = overline?.let { { Text(it) } },
        trailingContent = action,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (onClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    },
                ),
    )
}

/**
 * A [SimpleSettingsItem] that has a toggleable switch.
 *
 * Tapping anywhere on the row toggles the switch. When [enabled] is false the row grays out and
 * stops responding — use this (not hiding) for a setting that depends on a feature's
 * [MasterFeatureToggle] being on. Defaults to the surrounding [LocalSettingsEnabled], so a
 * [SettingsFeatureGroup] disables it automatically.
 */
@Composable
fun ToggleSettingsItem(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    overline: String? = null,
    enabled: Boolean = LocalSettingsEnabled.current,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    SettingsRow(
        headlineContent = { Text(title) },
        supportingContent = description?.let { { Text(it) } },
        leadingContent = leadingContent,
        overlineContent = overline?.let { { Text(it) } },
        enabled = enabled,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/**
 * A settings item with both a toggleable switch and a separate navigation action.
 *
 * The left portion navigates to a detail screen when tapped. A vertical divider separates
 * the label area from the independently tappable switch on the right, following the Android
 * system settings pattern.
 */
@Composable
fun LinkedToggleSettingsItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onNavigate: () -> Unit,
    overline: String? = null,
    enabled: Boolean = LocalSettingsEnabled.current,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(MaterialTheme.colorScheme.surface)
                .disabledAlpha(enabled),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsRow(
            headlineContent = { Text(title) },
            supportingContent = { Text(description) },
            overlineContent = overline?.let { { Text(it) } },
            modifier = Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClick = onNavigate),
            enabled = true,
        )
        VerticalDivider(modifier = Modifier.fillMaxHeight().padding(vertical = Spacing.sm))
        Box(
            modifier =
                Modifier
                    .semantics { contentDescription = title }
                    .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                    .padding(horizontal = Spacing.lg)
                    .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

/**
 * The master on/off switch for an entire feature, styled as a prominent
 * `secondaryContainer` pill with `extraLarge` shape (the Pixel Settings detail-screen pattern).
 *
 * Use this **exactly once** per settings detail screen, as the switch that turns the whole feature
 * (and the group of settings below it) on or off — e.g. "Use recommendations". It is **not** a
 * generic switch: for an individual setting within a feature, use [ToggleSettingsItem] instead.
 *
 * When this master is off, keep the dependent settings **visible but disabled** rather than hiding
 * them — render them and pass `enabled = checked` so they gray out (see [ToggleSettingsItem],
 * [SettingsNavigationItem]). Reserve hiding for content that is genuinely unavailable, such as a
 * setting blocked behind a missing OS permission.
 *
 * @param label The label for the toggle
 * @param checked Whether the feature is currently enabled
 * @param onCheckedChange Callback when the toggle is changed
 * @param enabled Whether the master toggle itself is interactive
 * @param modifier Modifier to be applied to the outer surface
 */
@Composable
fun MasterFeatureToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .disabledAlpha(enabled)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        MasterToggleLayout(
            label = label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

private enum class MasterToggleSlot { Label, Control }

/**
 * Places [label] beside [control] while the label's widest word fits there, and otherwise stacks
 * [control] below a full-width label. A word wider than the full width drops its tracking, then
 * shrinks only as far as needed, so a word is never broken mid-word under any font's metrics.
 */
@Composable
private fun MasterToggleLayout(
    label: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    val textMeasurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val words = remember(label) { label.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    SubcomposeLayout(modifier) { constraints ->
        val controlPlaceable = subcompose(MasterToggleSlot.Control, control).single().measure(Constraints())
        val inlineGap = Spacing.lg.roundToPx()
        val width =
            if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                textMeasurer.measure(label, style, softWrap = false).size.width + inlineGap + controlPlaceable.width
            }

        fun widestWord(wordStyle: TextStyle): Int =
            words.maxOfOrNull { textMeasurer.measure(it, wordStyle, softWrap = false, maxLines = 1).size.width } ?: 0

        val inlineLabelWidth = width - controlPlaceable.width - inlineGap
        val stacked = widestWord(style) > inlineLabelWidth
        val labelWidth = if (stacked) width else inlineLabelWidth
        val labelStyle = style.fittingWord(labelWidth, ::widestWord)
        val labelPlaceable =
            subcompose(MasterToggleSlot.Label) { Text(label, style = labelStyle) }
                .single()
                .measure(Constraints(maxWidth = labelWidth.coerceAtLeast(0)))
        val gap = Spacing.sm.roundToPx()
        val height =
            if (stacked) {
                labelPlaceable.height + gap + controlPlaceable.height
            } else {
                maxOf(labelPlaceable.height, controlPlaceable.height)
            }
        layout(width, height) {
            if (stacked) {
                labelPlaceable.placeRelative(0, 0)
                controlPlaceable.placeRelative(width - controlPlaceable.width, labelPlaceable.height + gap)
            } else {
                val center = Alignment.CenterVertically
                labelPlaceable.placeRelative(0, center.align(labelPlaceable.height, height))
                controlPlaceable.placeRelative(width - controlPlaceable.width, center.align(controlPlaceable.height, height))
            }
        }
    }
}

private fun TextStyle.fittingWord(
    width: Int,
    widestWord: (TextStyle) -> Int,
): TextStyle {
    if (widestWord(this) <= width) return this
    val untracked = copy(letterSpacing = 0.sp)
    var fitted = untracked
    var measured = widestWord(fitted)
    while (measured > width && width > 0 && fitted.fontSize.isSpecified) {
        val scale = width.toFloat() / measured * 0.98f
        fitted =
            fitted.copy(
                fontSize = fitted.fontSize * scale,
                lineHeight = if (fitted.lineHeight.isSpecified) fitted.lineHeight * scale else fitted.lineHeight,
            )
        measured = widestWord(fitted)
    }
    return fitted
}

/**
 * A two-line settings row that navigates to a sub-screen on tap. Has a leading icon
 * slot, headline, supporting description, and a trailing chevron so the affordance
 * reads "tap to drill in" — the standard pattern for a settings hub. An optional
 * [overline] labels the row the way neighboring rows in the same list are labeled.
 */
@Composable
fun SettingsNavigationItem(
    title: String,
    description: String,
    icon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
    enabled: Boolean = LocalSettingsEnabled.current,
    onClickLabel: String? = null,
    destructive: Boolean = false,
    overline: String? = null,
) {
    SettingsRow(
        enabled = enabled,
        headlineContent = {
            Text(
                text = title,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { Text(description) },
        overlineContent = overline?.let { { Text(it) } },
        leadingContent = icon,
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
            )
        },
        modifier =
            Modifier
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick),
    )
}
