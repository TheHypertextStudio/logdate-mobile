@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui.history

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.logdate.ui.workspace.SupportingExtent

@Composable
fun HumanLocationHistoryContent(
    state: HumanLocationHistoryState,
    actions: HumanLocationHistoryActions,
    modifier: Modifier = Modifier,
    mapContent: @Composable (Modifier) -> Unit = {},
    toolbarActions: @Composable RowScope.() -> Unit = {},
    supportingDetail: (@Composable () -> Unit)? = null,
    initialSupportingExtent: SupportingExtent = SupportingExtent.Peek,
    mapAvailable: Boolean = true,
) {
    HumanHistoryWorkspace(state, actions, modifier, mapContent, toolbarActions, supportingDetail, initialSupportingExtent, mapAvailable)
}
