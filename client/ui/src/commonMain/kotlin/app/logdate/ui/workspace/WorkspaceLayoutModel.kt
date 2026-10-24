package app.logdate.ui.workspace

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.foldable.FoldableHingeOrientation
import app.logdate.ui.foldable.FoldableLayoutInfo
import app.logdate.ui.foldable.relativeTo

enum class PanelRole { Browse, Focus, Inspector }

enum class PanelPlacement { Floating, EdgeAttached, Upper, Lower }

data class PanelConstraints(
    val minimumWidth: Dp,
    val preferredWidth: Dp,
    val maximumWidth: Dp? = null,
) {
    companion object {
        val Browse = PanelConstraints(280.dp, 320.dp, 360.dp)
        val Reading = PanelConstraints(320.dp, 720.dp)
        val ReadingCollection = PanelConstraints(320.dp, 560.dp, 560.dp)
        val Visual = PanelConstraints(360.dp, 720.dp)
        val Inspector = PanelConstraints(280.dp, 320.dp, 360.dp)
    }
}

data class PanelLayoutInfo(
    val role: PanelRole,
    val placement: PanelPlacement,
    val width: Dp,
    val height: Dp,
)

data class PanelBounds(
    val x: Dp,
    val y: Dp,
    val width: Dp,
    val height: Dp,
)

data class WorkspaceComposition(
    val focus: PanelBounds,
    val browse: PanelBounds? = null,
    val inspector: PanelBounds? = null,
)

fun resolveWorkspaceComposition(
    width: Dp,
    height: Dp,
    focus: PanelConstraints = PanelConstraints.Reading,
    browse: PanelConstraints? = null,
    inspector: PanelConstraints? = null,
    foldable: FoldableLayoutInfo = FoldableLayoutInfo(),
    origin: DpOffset = DpOffset.Zero,
    browseOnStart: Boolean = false,
    layoutDirection: LayoutDirection = LayoutDirection.Ltr,
): WorkspaceComposition {
    val hinge = foldable.relativeTo(origin).hinge?.takeIf { it.isSeparating }
    val regions =
        when (hinge?.orientation) {
            FoldableHingeOrientation.Vertical -> {
                listOf(
                    PanelBounds(0.dp, 0.dp, hinge.bounds.left.coerceIn(0.dp, width), height),
                    PanelBounds(hinge.bounds.right.coerceIn(0.dp, width), 0.dp, (width - hinge.bounds.right).coerceAtLeast(0.dp), height),
                )
            }

            FoldableHingeOrientation.Horizontal -> {
                listOf(
                    PanelBounds(0.dp, 0.dp, width, hinge.bounds.top.coerceIn(0.dp, height)),
                    PanelBounds(
                        0.dp,
                        hinge.bounds.bottom.coerceIn(0.dp, height),
                        width,
                        (height - hinge.bounds.bottom).coerceAtLeast(0.dp),
                    ),
                )
            }

            else -> {
                emptyList()
            }
        }.map { it.inset(16.dp) }
    if (regions.size == 2) {
        return resolveHingeComposition(
            regions,
            focus,
            browse,
            (browseOnStart != (layoutDirection == LayoutDirection.Rtl)) && hinge?.orientation == FoldableHingeOrientation.Vertical,
            hinge?.orientation == FoldableHingeOrientation.Vertical,
        )
    }
    val composition = resolveLinearComposition(width, height, focus, browse, inspector, browseOnStart)
    if (layoutDirection == LayoutDirection.Ltr) return composition

    fun PanelBounds.mirror() = copy(x = width - x - this.width)
    return WorkspaceComposition(composition.focus.mirror(), composition.browse?.mirror(), composition.inspector?.mirror())
}

private fun resolveHingeComposition(
    regions: List<PanelBounds>,
    focus: PanelConstraints,
    browse: PanelConstraints?,
    browseFirst: Boolean,
    vertical: Boolean,
): WorkspaceComposition {
    val preferredFocus = if (browseFirst) regions[1] else regions[0]
    val preferredSupport = if (browseFirst) regions[0] else regions[1]
    val reverseFits =
        browse != null &&
            preferredFocus.width < focus.minimumWidth &&
            preferredSupport.width >= focus.minimumWidth &&
            preferredFocus.width >= browse.minimumWidth
    val focusRegion = if (reverseFits) preferredSupport else preferredFocus
    val supportingRegion = if (reverseFits) preferredFocus else preferredSupport
    if (browse != null &&
        focusRegion.width >= focus.minimumWidth &&
        supportingRegion.width >= browse.minimumWidth &&
        regions.all { it.height >= 180.dp }
    ) {
        // Each half of a vertical hinge is filled, so neither the navigation nor the fold has a gap beside it.
        return WorkspaceComposition(if (vertical) focusRegion else focusRegion.limitWidth(focus.maximumWidth), supportingRegion)
    }
    return WorkspaceComposition(regions.maxBy { it.width.value * it.height.value }.limitWidth(focus.maximumWidth))
}

private fun resolveLinearComposition(
    width: Dp,
    height: Dp,
    focus: PanelConstraints,
    browse: PanelConstraints?,
    inspector: PanelConstraints?,
    browseOnStart: Boolean,
): WorkspaceComposition {
    val gutter = 16.dp
    val available = width - gutter * 2
    val fitBrowse = height >= 212.dp && browse != null && available >= focus.minimumWidth + browse.minimumWidth + gutter
    val fitInspector =
        height >= 212.dp &&
            inspector != null &&
            available >= focus.minimumWidth + inspector.minimumWidth + gutter +
            (if (fitBrowse) browse.minimumWidth + gutter else 0.dp)
    if (!fitBrowse && !fitInspector) {
        val bounds = PanelBounds(0.dp, 0.dp, width, height)
        return WorkspaceComposition((if (width >= 600.dp) bounds.inset(gutter) else bounds).limitWidth(focus.maximumWidth))
    }
    val supporting = listOfNotNull(browse.takeIf { fitBrowse }, inspector.takeIf { fitInspector })
    var remaining = available - focus.minimumWidth - supporting.fold(0.dp) { total, constraint -> total + constraint.minimumWidth + gutter }

    fun supportingWidth(constraint: PanelConstraints): Dp {
        val target = constraint.preferredWidth.coerceAtMost(constraint.maximumWidth ?: constraint.preferredWidth)
        val extra = (target - constraint.minimumWidth).coerceAtLeast(0.dp).coerceAtMost(remaining)
        remaining -= extra
        return constraint.minimumWidth + extra
    }
    val browseWidth = browse?.takeIf { fitBrowse }?.let(::supportingWidth)
    val inspectorWidth = inspector?.takeIf { fitInspector }?.let(::supportingWidth)
    val focusWidth = available - (browseWidth?.plus(gutter) ?: 0.dp) - (inspectorWidth?.plus(gutter) ?: 0.dp)
    val contentHeight = (height - gutter * 2).coerceAtLeast(0.dp)
    var x = gutter

    fun nextBounds(panelWidth: Dp): PanelBounds = PanelBounds(x, gutter, panelWidth, contentHeight).also { x += panelWidth + gutter }
    val leadingBrowse = browseWidth?.takeIf { browseOnStart }?.let(::nextBounds)
    val focusBounds = nextBounds(focusWidth.coerceAtMost(focus.maximumWidth ?: focusWidth))
    val browseBounds = leadingBrowse ?: browseWidth?.let(::nextBounds)
    val inspectorBounds = inspectorWidth?.let(::nextBounds)
    return WorkspaceComposition(focusBounds, browseBounds, inspectorBounds)
}

private fun PanelBounds.inset(amount: Dp) =
    PanelBounds(
        x + amount,
        y + amount,
        (width - amount * 2).coerceAtLeast(0.dp),
        (height - amount * 2).coerceAtLeast(0.dp),
    )

private fun PanelBounds.limitWidth(maximum: Dp?) = if (maximum == null) this else copy(width = width.coerceAtMost(maximum))
