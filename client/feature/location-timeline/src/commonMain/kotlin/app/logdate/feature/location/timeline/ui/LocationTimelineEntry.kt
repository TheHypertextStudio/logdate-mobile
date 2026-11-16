@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.location.timeline.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryScreen
import app.logdate.shared.model.location.VisitMemoryContext
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Composable
fun LocationTimelineScreen(
    modifier: Modifier = Modifier,
    onOpenNote: (Uuid) -> Unit = {},
    onAddMemory: (VisitMemoryContext) -> Unit = {},
    embeddedInHome: Boolean = false,
) {
    val flags = koinInject<FeatureFlagStore>()
    LocationHistoryBetaContent(flags) {
        HumanLocationHistoryScreen(onOpenNote, onAddMemory, modifier, showTitle = !embeddedInHome)
    }
}

@Composable
internal fun LocationHistoryBetaContent(
    flags: FeatureFlagStore,
    historyContent: @Composable () -> Unit,
) {
    val enabled: Boolean? by remember(flags) { flags.observe(FeatureFlag.PLACES_BETA) }.collectAsStateWithLifecycle(initialValue = null)
    if (enabled == true) historyContent()
}
