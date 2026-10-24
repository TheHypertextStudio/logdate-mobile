@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.workspace

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.logdate.ui.common.IconTooltip
import app.logdate.ui.common.PlatformBackHandler
import app.logdate.ui.foldable.rememberFoldableLayoutInfo
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.platform.PlatformIcons
import app.logdate.ui.theme.Spacing
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.workspace_sheet_browse
import logdate.client.ui.generated.resources.workspace_sheet_collapse
import logdate.client.ui.generated.resources.workspace_sheet_expand
import logdate.client.ui.generated.resources.workspace_sheet_full_view
import logdate.client.ui.generated.resources.workspace_sheet_show_map
import org.jetbrains.compose.resources.stringResource

val LocalSupportingOverlap = staticCompositionLocalOf { 0.dp }

/** Keeps the visual focus mounted while the same supporting panel changes extent or placement. */
@Composable
// Foundation 1.11's untargeted fling is not reported by isAnimationRunning. Keep explicit takeover
// until the default covers it; WorkspaceSheetGestureTest verifies a content touch interrupts a fling.
@Suppress("DEPRECATION")
fun WorkspaceSupportingSheet(
    summary: String,
    modifier: Modifier = Modifier,
    summaryDetail: String? = null,
    focusAvailable: Boolean = true,
    initialExtent: SupportingExtent = SupportingExtent.Peek,
    supportingContextKey: String? = null,
    supportingContainment: PanelContainment = PanelContainment.Working,
    header: (@Composable () -> Unit)? = null,
    headerActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    focus: @Composable () -> Unit,
    supporting: @Composable (SupportingExtent) -> Unit,
) {
    var extent by rememberSaveable { mutableStateOf(if (supportingContextKey != null) SupportingExtent.Browsing else initialExtent) }
    var previousExtent by rememberSaveable { mutableStateOf(initialExtent) }
    var previousContext by rememberSaveable { mutableStateOf(supportingContextKey) }
    LaunchedEffect(supportingContextKey) {
        if (supportingContextKey != null && previousContext == null) {
            previousExtent = extent
            extent = SupportingExtent.Browsing
        } else if (supportingContextKey == null && previousContext != null) {
            extent = previousExtent
        }
        previousContext = supportingContextKey
    }
    val parentPlacement = LocalPanelLayoutInfo.current?.placement
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val foldable = rememberFoldableLayoutInfo()
    val (origin, originModifier) = rememberWindowOrigin()
    BoxWithConstraints(modifier.fillMaxSize().then(originModifier), contentAlignment = AbsoluteAlignment.TopLeft) {
        val composition =
            resolveSupportingWorkspaceComposition(
                maxWidth,
                maxHeight,
                foldable = foldable,
                origin = origin,
                layoutDirection = direction,
            )
        val mapBounds = composition.focus
        val anchors = supportingSheetAnchors(mapBounds.height, 160.dp * density.fontScale.coerceAtMost(2f))
        val side = composition.browse.takeIf { focusAvailable }
        val overlay = focusAvailable && side == null
        val dragAnchors = anchors.dragAnchors(with(density) { mapBounds.height.toPx() }, density.density)
        val presentationExtent =
            if (!focusAvailable) {
                SupportingExtent.Focused
            } else if (side ==
                null
            ) {
                dragAnchors.availableExtent(extent)
            } else {
                extent
            }
        val allowsFocus = !dragAnchors.positionOf(SupportingExtent.Focused).isNaN()
        val focused = presentationExtent == SupportingExtent.Focused
        val motion = rememberSupportingSheetMotion(extent, dragAnchors, onSettled = { extent = it })
        val normalSheetHeight = composition.browse?.height ?: with(density) { (-motion.state.requireOffset()).toDp() }
        val sheetHeight = if (!focusAvailable) maxHeight else normalSheetHeight
        val mapPlacement =
            if (mapBounds.x > 0.dp ||
                parentPlacement == PanelPlacement.Floating
            ) {
                PanelPlacement.Floating
            } else {
                PanelPlacement.EdgeAttached
            }
        val overlap =
            if (composition.browse ==
                null
            ) {
                normalSheetHeight.coerceAtMost((mapBounds.height - 180.dp).coerceAtLeast(0.dp))
            } else {
                0.dp
            }
        Box(
            Modifier
                .absoluteOffset { IntOffset(mapBounds.x.roundToPx(), mapBounds.y.roundToPx()) }
                .size(mapBounds.width, mapBounds.height)
                .then(if (focusAvailable) Modifier else Modifier.unavailableFocus()),
        ) {
            CompositionLocalProvider(LocalSupportingOverlap provides overlap) {
                WorkspacePanel(
                    Modifier.fillMaxSize(),
                    placement = mapPlacement,
                ) { focus() }
            }
        }
        val sheetX = if (!focusAvailable) 0.dp else side?.x ?: mapBounds.x
        val sheetY = if (!focusAvailable) 0.dp else side?.y ?: (mapBounds.y + mapBounds.height - sheetHeight)
        val sheetWidth = if (!focusAvailable) maxWidth else side?.width ?: mapBounds.width
        PlatformBackHandler(enabled = overlay && extent != SupportingExtent.Peek && supportingContextKey == null) {
            extent =
                SupportingExtent.Peek
        }
        Box(Modifier.absoluteOffset { IntOffset(sheetX.roundToPx(), sheetY.roundToPx()) }.size(sheetWidth, sheetHeight)) {
            WorkspacePanel(
                Modifier.fillMaxSize(),
                role = PanelRole.Browse,
                containment = if (side == null) PanelContainment.Working else supportingContainment,
                placement = if (side == null && mapPlacement != PanelPlacement.Floating) PanelPlacement.Lower else PanelPlacement.Floating,
            ) {
                Column(
                    Modifier.fillMaxSize().then(
                        if (overlay) {
                            Modifier.nestedScroll(motion.nestedScroll).anchoredDraggable(
                                state = motion.state,
                                orientation = Orientation.Vertical,
                                flingBehavior = motion.flingBehavior,
                                startDragImmediately = motion.isSettling || motion.state.isAnimationRunning,
                            )
                        } else {
                            Modifier
                        },
                    ),
                ) {
                    if (side == null || header != null) {
                        FlowRow(
                            Modifier
                                .fillMaxWidth()
                                .semantics {
                                    if (overlay) {
                                        stateDescription = presentationExtent.name
                                        if (allowsFocus) {
                                            expand {
                                                extent = if (anchors.canOverlay) SupportingExtent.Expanded else SupportingExtent.Focused
                                                true
                                            }
                                        }
                                        collapse {
                                            extent = SupportingExtent.Peek
                                            true
                                        }
                                    }
                                }.padding(horizontal = Spacing.lg),
                        ) {
                            if (header != null) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { header() }
                                    if (overlay) {
                                        val expandLabel =
                                            when (presentationExtent) {
                                                SupportingExtent.Peek -> stringResource(Res.string.workspace_sheet_browse)
                                                SupportingExtent.Browsing -> stringResource(Res.string.workspace_sheet_expand)
                                                else -> stringResource(Res.string.workspace_sheet_collapse)
                                            }
                                        IconTooltip(label = expandLabel) {
                                            IconButton(enabled = allowsFocus || presentationExtent != SupportingExtent.Peek, onClick = {
                                                extent =
                                                    when (presentationExtent) {
                                                        SupportingExtent.Peek -> {
                                                            if (anchors.canOverlay) SupportingExtent.Browsing else SupportingExtent.Focused
                                                        }

                                                        SupportingExtent.Browsing -> {
                                                            SupportingExtent.Expanded
                                                        }

                                                        else -> {
                                                            SupportingExtent.Peek
                                                        }
                                                    }
                                            }) {
                                                Icon(
                                                    PlatformIcons.expandMore(),
                                                    expandLabel,
                                                    Modifier.then(
                                                        if ((
                                                                presentationExtent == SupportingExtent.Peek ||
                                                                    presentationExtent == SupportingExtent.Browsing
                                                            )
                                                        ) {
                                                            Modifier.graphicsLayer {
                                                                rotationZ =
                                                                    180f
                                                            }
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                                )
                                            }
                                        }
                                        val fullViewLabel =
                                            if (focused) {
                                                stringResource(Res.string.workspace_sheet_show_map)
                                            } else {
                                                stringResource(Res.string.workspace_sheet_full_view)
                                            }
                                        IconTooltip(label = fullViewLabel) {
                                            IconButton(enabled = allowsFocus, onClick = {
                                                extent =
                                                    if (focused) {
                                                        if (anchors.canOverlay) SupportingExtent.Browsing else SupportingExtent.Peek
                                                    } else {
                                                        SupportingExtent.Focused
                                                    }
                                            }) { Icon(PlatformIcons.openInNew(), fullViewLabel) }
                                        }
                                    }
                                    headerActions()
                                }
                            } else if (focusAvailable) {
                                TextButton(enabled = allowsFocus || presentationExtent != SupportingExtent.Peek, onClick = {
                                    extent =
                                        if (presentationExtent ==
                                            SupportingExtent.Peek
                                        ) {
                                            if (anchors.canOverlay) SupportingExtent.Browsing else SupportingExtent.Focused
                                        } else {
                                            SupportingExtent.Peek
                                        }
                                }) {
                                    Text(
                                        if (presentationExtent == SupportingExtent.Peek) {
                                            stringResource(Res.string.workspace_sheet_browse)
                                        } else {
                                            stringResource(Res.string.workspace_sheet_collapse)
                                        },
                                    )
                                }
                                if (presentationExtent ==
                                    SupportingExtent.Browsing
                                ) {
                                    TextButton(onClick = { extent = SupportingExtent.Expanded }) {
                                        Text(stringResource(Res.string.workspace_sheet_expand))
                                    }
                                }
                                TextButton(enabled = allowsFocus, onClick = {
                                    extent =
                                        if (focused) {
                                            if (anchors.canOverlay) SupportingExtent.Browsing else SupportingExtent.Peek
                                        } else {
                                            SupportingExtent.Focused
                                        }
                                }) {
                                    Text(
                                        if (focused) {
                                            stringResource(Res.string.workspace_sheet_show_map)
                                        } else {
                                            stringResource(Res.string.workspace_sheet_full_view)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (overlay && extent == SupportingExtent.Peek && sheetHeight <= anchors.peek) {
                        if (anchors.canOverlay) PanelHeader(summary, subtitle = summaryDetail)
                    } else {
                        Box(Modifier.weight(1f)) {
                            supporting(
                                if (!focusAvailable) {
                                    SupportingExtent.Focused
                                } else if (side != null) {
                                    SupportingExtent.Expanded
                                } else if (extent ==
                                    SupportingExtent.Peek
                                ) {
                                    SupportingExtent.Browsing
                                } else {
                                    presentationExtent
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Suppress this mounted focus host, rather than placing an intercepting layer over the collection. */
private fun Modifier.unavailableFocus(): Modifier =
    graphicsLayer { alpha = 0f }.clearAndSetSemantics {}.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
