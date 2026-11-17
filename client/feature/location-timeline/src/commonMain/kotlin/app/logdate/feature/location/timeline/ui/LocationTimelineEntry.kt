@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryScreen
import app.logdate.shared.model.location.VisitMemoryContext
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun LocationTimelineScreen(
    modifier: Modifier = Modifier,
    onOpenNote: (Uuid) -> Unit = {},
    onAddMemory: (VisitMemoryContext) -> Unit = {},
    viewModel: LocationTimelineViewModel? = null,
) {
    val flags = koinInject<FeatureFlagStore>()
    val enabled by flags.observe(FeatureFlag.HUMAN_LOCATION_HISTORY).collectAsStateWithLifecycle(false)
    if (enabled) {
        HumanLocationHistoryScreen(onOpenNote, onAddMemory, modifier)
    } else {
        LegacyLocationTimelineScreen(modifier, onOpenNote, viewModel ?: koinViewModel())
    }
}
