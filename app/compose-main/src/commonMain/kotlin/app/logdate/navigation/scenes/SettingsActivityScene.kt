@file:Suppress("ktlint:standard:function-naming")

package app.logdate.navigation.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import app.logdate.feature.core.settings.navigation.SettingsActivityCategories
import app.logdate.ui.foldable.FoldableSplitLayout
import app.logdate.ui.foldable.rememberWindowOrigin
import app.logdate.ui.theme.Spacing

class SettingsActivityScene<T : NavKey>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    override val entries: List<NavEntry<T>>,
    private val detail: NavEntry<T>,
    private val selectedSection: String,
    private val categories: SettingsActivityCategories,
    private val supportsDualPane: Boolean,
    private val foldableSplitLayout: FoldableSplitLayout,
) : Scene<T> {
    override val content: @Composable () -> Unit = {
        SettingsActivityLayout(
            supportsDualPane = supportsDualPane,
            foldableSplitLayout = foldableSplitLayout,
            categories = { categories.content(selectedSection) },
            detail = { detail.Content() },
        )
    }
}

@Composable
internal fun SettingsActivityLayout(
    supportsDualPane: Boolean,
    foldableSplitLayout: FoldableSplitLayout,
    categories: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val minimumCategories = 280.dp * fontScale
    val minimumDetail = 360.dp * fontScale
    val (origin, originModifier) = rememberWindowOrigin()
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(Modifier.fillMaxSize().then(originModifier), contentAlignment = AbsoluteAlignment.TopLeft) {
        val containerWidth = maxWidth
        val enoughHeight = maxHeight >= 480.dp * fontScale
        when (val split = foldableSplitLayout) {
            is FoldableSplitLayout.Vertical -> {
                val leftWidth = (split.hingeBounds.left - origin.x).coerceIn(0.dp, maxWidth)
                val hingeEnd = (split.hingeBounds.right - origin.x).coerceIn(0.dp, maxWidth)
                val rightWidth = maxWidth - hingeEnd
                val categoriesOnLeft = direction == LayoutDirection.Ltr
                val categoryWidth = if (categoriesOnLeft) leftWidth else rightWidth
                val detailWidth = if (categoriesOnLeft) rightWidth else leftWidth
                if (supportsDualPane &&
                    enoughHeight &&
                    categoryWidth >= minimumCategories + Spacing.lg * 2 &&
                    detailWidth >= minimumDetail + Spacing.lg * 2
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(Modifier.fillMaxSize().testTag("settings_activity_two_pane")) {
                            Surface(Modifier.width(leftWidth).fillMaxHeight().padding(Spacing.lg)) {
                                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                    if (categoriesOnLeft) categories() else detail()
                                }
                            }
                            Spacer(Modifier.width(hingeEnd - leftWidth))
                            Surface(Modifier.width(rightWidth).fillMaxHeight().padding(Spacing.lg)) {
                                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                    if (categoriesOnLeft) detail() else categories()
                                }
                            }
                        }
                    }
                } else {
                    val useLeft = leftWidth >= rightWidth
                    Box(
                        Modifier
                            .absoluteOffset(x = if (useLeft) 0.dp else hingeEnd)
                            .width(if (useLeft) leftWidth else rightWidth)
                            .fillMaxHeight(),
                    ) { detail() }
                }
            }

            is FoldableSplitLayout.Horizontal -> {
                val topHeight = (split.hingeBounds.top - origin.y).coerceIn(0.dp, maxHeight)
                val hingeEnd = (split.hingeBounds.bottom - origin.y).coerceIn(0.dp, maxHeight)
                val bottomHeight = maxHeight - hingeEnd
                val useTop = topHeight >= bottomHeight
                Box(
                    Modifier
                        .absoluteOffset(y = if (useTop) 0.dp else hingeEnd)
                        .height(if (useTop) topHeight else bottomHeight)
                        .width(maxWidth),
                ) { detail() }
            }

            FoldableSplitLayout.None -> {
                if (supportsDualPane && enoughHeight && maxWidth >= minimumCategories + minimumDetail + Spacing.lg * 3) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(Spacing.lg)
                                .testTag("settings_activity_two_pane"),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                    ) {
                        Surface(
                            Modifier.width((containerWidth * 0.28f).coerceIn(minimumCategories, 360.dp * fontScale)).fillMaxHeight(),
                            shape = MaterialTheme.shapes.extraLarge,
                        ) { categories() }
                        Surface(Modifier.weight(1f).fillMaxHeight(), shape = MaterialTheme.shapes.extraLarge) { detail() }
                    }
                } else {
                    detail()
                }
            }
        }
    }
}
