@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.editor.ui.handwriting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.logdate.feature.postcards.model.InkTool
import app.logdate.ui.common.DropdownMenu
import logdate.client.feature.editor.generated.resources.Res
import logdate.client.feature.editor.generated.resources.ink_berry
import logdate.client.feature.editor.generated.resources.ink_black
import logdate.client.feature.editor.generated.resources.ink_blue
import logdate.client.feature.editor.generated.resources.ink_context
import logdate.client.feature.editor.generated.resources.ink_erase
import logdate.client.feature.editor.generated.resources.ink_highlight
import logdate.client.feature.editor.generated.resources.ink_lines
import logdate.client.feature.editor.generated.resources.ink_medium
import logdate.client.feature.editor.generated.resources.ink_options
import logdate.client.feature.editor.generated.resources.ink_pen
import logdate.client.feature.editor.generated.resources.ink_redo
import logdate.client.feature.editor.generated.resources.ink_style
import logdate.client.feature.editor.generated.resources.ink_thick
import logdate.client.feature.editor.generated.resources.ink_thin
import logdate.client.feature.editor.generated.resources.ink_touch
import logdate.client.feature.editor.generated.resources.ink_undo
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun HandwritingTools(
    tool: InkTool,
    color: String,
    width: Float,
    allowTouch: Boolean,
    ruled: Boolean,
    showContext: Boolean,
    contextAvailable: Boolean,
    onTool: (InkTool) -> Unit,
    onColor: (String) -> Unit,
    onWidth: (Float) -> Unit,
    onTouch: () -> Unit,
    onRules: () -> Unit,
    onContext: () -> Unit,
) {
    BoxWithConstraints {
        val showColors = maxWidth >= 600.dp
        val showToolLabel = maxWidth >= 600.dp && LocalDensity.current.fontScale <= 1.3f
        val drawingActions: @Composable () -> Unit = {
            HorizontalFloatingToolbar(
                expanded = true,
                modifier = Modifier.height(56.dp),
                colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                contentPadding = PaddingValues(4.dp),
            ) {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        InkTool.PEN to Res.string.ink_pen,
                        InkTool.HIGHLIGHTER to Res.string.ink_highlight,
                        InkTool.ERASER to Res.string.ink_erase,
                    ).forEach { (choice, label) ->
                        DrawingToolButton(choice, stringResource(label), tool == choice, showToolLabel, { onTool(choice) })
                    }
                }
                if (showColors) InkColorChoices(color, onColor)
                InkStylePicker(color, width, onColor, onWidth)
                DrawingOptions(allowTouch, ruled, showContext, contextAvailable, onTouch, onRules, onContext)
            }
        }
        drawingActions()
    }
}

@Composable
internal fun HandwritingHistoryTools(
    history: HandwritingHistory,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    HorizontalFloatingToolbar(expanded = true, modifier = Modifier.height(48.dp), contentPadding = PaddingValues(0.dp)) {
        IconButton(onUndo, modifier = Modifier.size(48.dp), enabled = history.past.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(Res.string.ink_undo), Modifier.size(20.dp))
        }
        IconButton(onRedo, modifier = Modifier.size(48.dp), enabled = history.future.isNotEmpty()) {
            Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(Res.string.ink_redo), Modifier.size(20.dp))
        }
    }
}

@Composable
private fun DrawingToolButton(
    tool: InkTool,
    label: String,
    active: Boolean,
    showLabel: Boolean,
    onClick: () -> Unit,
) {
    val foreground = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
    ToggleButton(
        checked = active,
        onCheckedChange = { onClick() },
        shapes =
            ToggleButtonShapes(
                shape = ToggleButtonDefaults.shape,
                pressedShape = ToggleButtonDefaults.pressedShape,
                checkedShape = CircleShape,
            ),
        colors =
            ToggleButtonDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        contentPadding = PaddingValues(0.dp),
        modifier =
            Modifier
                .height(48.dp)
                .semantics {
                    contentDescription = label
                    selected = active
                    role = Role.RadioButton
                }.padding(vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (tool) {
                InkTool.PEN -> Icon(Icons.Outlined.Draw, null, Modifier.size(20.dp))
                InkTool.HIGHLIGHTER -> Icon(Icons.Outlined.BorderColor, null, Modifier.size(20.dp))
                InkTool.ERASER -> EraserGlyph(foreground)
            }
            AnimatedVisibility(active && showLabel) { Text(label, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun EraserGlyph(tint: Color) {
    Canvas(Modifier.size(20.dp)) {
        val unit = size.width / 24f
        val path =
            Path().apply {
                moveTo(3 * unit, 14 * unit)
                lineTo(13 * unit, 4 * unit)
                lineTo(21 * unit, 12 * unit)
                lineTo(13 * unit, 20 * unit)
                lineTo(9 * unit, 20 * unit)
                close()
            }
        drawPath(path, tint, style = Stroke(2 * unit, cap = StrokeCap.Round))
        drawLine(tint, Offset(8 * unit, 9 * unit), Offset(16 * unit, 17 * unit), 2 * unit)
    }
}

@Composable
private fun InkColorChoices(
    color: String,
    onColor: (String) -> Unit,
) {
    Row(Modifier.selectableGroup()) {
        val colors = listOf("#263044" to Res.string.ink_black, "#253F99" to Res.string.ink_blue, "#893D5E" to Res.string.ink_berry)
        colors.forEach { (choice, label) ->
            val description = stringResource(label)
            val ring = MaterialTheme.colorScheme.onPrimaryContainer
            IconToggleButton(color == choice, { onColor(choice) }, Modifier.size(48.dp).semantics { contentDescription = description }) {
                Canvas(Modifier.size(32.dp)) {
                    drawCircle(inkColor(choice), radius = 11.dp.toPx())
                    if (color == choice) drawCircle(ring, radius = 15.dp.toPx(), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun InkStylePicker(
    color: String,
    width: Float,
    onColor: (String) -> Unit,
    onWidth: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = listOf("#263044" to Res.string.ink_black, "#253F99" to Res.string.ink_blue, "#893D5E" to Res.string.ink_berry)
    val widths = listOf(2f to Res.string.ink_thin, 3.5f to Res.string.ink_medium, 6f to Res.string.ink_thick)
    val description = stringResource(Res.string.ink_style)
    Box {
        IconButton({ open = true }, Modifier.size(48.dp).semantics { contentDescription = description }) {
            InkSwatch(inkColor(color), width)
        }
        DropdownMenu(open, { open = false }) {
            colors.forEach { (choice, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { InkSwatch(inkColor(choice), width) },
                    trailingIcon = { if (color == choice) Text("✓") },
                    onClick = {
                        onColor(choice)
                        open = false
                    },
                )
            }
            widths.forEach { (choice, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = {
                        Canvas(Modifier.width(32.dp).height(24.dp)) {
                            drawLine(
                                inkColor(color),
                                Offset(4.dp.toPx(), center.y),
                                Offset(size.width - 4.dp.toPx(), center.y),
                                choice.dp.toPx(),
                                StrokeCap.Round,
                            )
                        }
                    },
                    trailingIcon = { if (width == choice) Text("✓") },
                    onClick = {
                        onWidth(choice)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun InkSwatch(
    tint: Color,
    width: Float,
) {
    val contrast = Color.White
    Canvas(Modifier.size(28.dp)) {
        drawCircle(tint)
        drawLine(
            contrast,
            Offset(size.width * .28f, size.height * .72f),
            Offset(size.width * .72f, size.height * .28f),
            width.dp.toPx(),
            StrokeCap.Round,
        )
    }
}

private fun inkColor(hex: String) = Color(hex.removePrefix("#").toLong(16) or 0xFF000000)

@Composable
private fun DrawingOptions(
    allowTouch: Boolean,
    ruled: Boolean,
    showContext: Boolean,
    contextAvailable: Boolean,
    onTouch: () -> Unit,
    onRules: () -> Unit,
    onContext: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.size(48.dp)) { Icon(Icons.Outlined.Tune, stringResource(Res.string.ink_options)) }
        DropdownMenu(open, { open = false }) {
            listOf(
                Triple(Res.string.ink_touch, allowTouch, onTouch),
                Triple(Res.string.ink_lines, ruled, onRules),
                Triple(Res.string.ink_context, showContext, onContext),
            ).filter { (label, _, _) -> contextAvailable || label != Res.string.ink_context }.forEach { (label, checked, action) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    trailingIcon = { if (checked) Text("✓") },
                    onClick = {
                        action()
                        open = false
                    },
                )
            }
        }
    }
}
