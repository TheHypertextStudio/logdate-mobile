package app.logdate.client.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.logdate.client.text.UiText
import app.logdate.feature.core.main.HomeRouteDestination
import app.logdate.feature.location.timeline.ui.history.HistoryMemoryKind
import app.logdate.feature.location.timeline.ui.history.HistoryMemoryUi
import app.logdate.feature.location.timeline.ui.history.HistoryPlaceUi
import app.logdate.feature.location.timeline.ui.history.HistoryTab
import app.logdate.feature.location.timeline.ui.history.HumanHistoryMap
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryState
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.ui.theme.LogDateTheme
import app.logdate.ui.workspace.SupportingExtent
import app.logdate.ui.workspace.WorkspaceDestination
import app.logdate.ui.workspace.WorkspaceScaffold
import kotlin.time.Instant
import kotlin.uuid.Uuid

@androidx.compose.runtime.Composable
internal fun PlacesSheetFixture(
    nativeMap: Boolean = false,
    onOpenPlace: (String) -> Unit = {},
) {
    var mapReady by remember { mutableStateOf(!nativeMap) }
    var query by remember { mutableStateOf("") }
    val instant = Instant.parse("2026-06-18T10:00:00Z")
    val place = SemanticPlace("cafe", "Riverside Café", 51.507, -0.12)
    val visits = listOf(PlaceVisit("visit", instant, instant, listOf("fix"), place.latitude, place.longitude, true, place))
    val places =
        listOf(
            HistoryPlaceUi(
                "cafe",
                UiText.Raw("Riverside Café"),
                UiText.Raw("London · 3 visits"),
                listOf(
                    HistoryMemoryUi(
                        Uuid.parse("00000000-0000-4000-8000-00000000c0ff"),
                        UiText.Raw("Coffee before the train, and time to finish our conversation."),
                        HistoryMemoryKind.Text,
                    ),
                ),
            ),
            HistoryPlaceUi(
                "river",
                UiText.Raw("River walk"),
                UiText.Raw("London · 2 visits"),
                listOf(
                    HistoryMemoryUi(
                        Uuid.parse("00000000-0000-4000-8000-00000000a1c0"),
                        UiText.Raw("The long walk home along the river."),
                        HistoryMemoryKind.Text,
                    ),
                ),
            ),
        ) + (1..12).map { HistoryPlaceUi("place-$it", UiText.Raw("Synthetic place $it"), UiText.Raw("Example visit")) }
    LogDateTheme(dynamicColor = false, darkTheme = false) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                WorkspaceScaffold(
                    HomeRouteDestination.entries.map {
                        WorkspaceDestination(
                            it.name,
                            if (it ==
                                HomeRouteDestination.LocationHistory
                            ) {
                                "Places"
                            } else {
                                it.name
                            },
                            it.selectedIcon,
                            it.unselectedIcon,
                        )
                    },
                    HomeRouteDestination.LocationHistory.name,
                    {},
                    onSearch = {},
                ) {
                    HumanLocationHistoryContent(
                        HumanLocationHistoryState(
                            UiText.Raw("18 Jun 2026"),
                            tab = HistoryTab.Places,
                            places = places,
                            placesQuery = query,
                            placesFilterLabel = UiText.Raw("30 days"),
                        ),
                        HumanLocationHistoryActions(onPlacesQuery = { query = it }, onOpenPlace = onOpenPlace),
                        initialSupportingExtent = SupportingExtent.Browsing,
                        mapAvailable = mapReady,
                        mapContent = {
                            if (nativeMap) {
                                HumanHistoryMap(visits, null, {}, it, {}, "synthetic-fixture", showStatus = false, onReadyChanged = {
                                    mapReady =
                                        it
                                })
                            } else {
                                Box(it) { Text("Synthetic map viewport") }
                            }
                        },
                    )
                }
            }
        }
    }
}
