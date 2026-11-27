@file:Suppress("ktlint:standard:function-naming", "ktlint:standard:package-name")

package app.logdate.screenshots.components.home_timeline

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.client.text.UiText
import app.logdate.feature.location.timeline.ui.history.HistoryItemKind
import app.logdate.feature.location.timeline.ui.history.HistoryItemUi
import app.logdate.feature.location.timeline.ui.history.HistoryMemoryKind
import app.logdate.feature.location.timeline.ui.history.HistoryMemoryUi
import app.logdate.feature.location.timeline.ui.history.HistoryPlaceUi
import app.logdate.feature.location.timeline.ui.history.HistoryTab
import app.logdate.feature.location.timeline.ui.history.HumanHistoryMap
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryActions
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryDetailContent
import app.logdate.feature.location.timeline.ui.history.HumanLocationHistoryState
import app.logdate.feature.location.timeline.ui.history.PlaceCandidateChipUi
import app.logdate.feature.location.timeline.ui.history.RecordedDayUi
import app.logdate.screenshots.common.ScreenshotPreviewMatrix
import app.logdate.screenshots.common.ScreenshotTestData.PHONE
import app.logdate.screenshots.common.ScreenshotTheme
import app.logdate.shared.model.location.JourneyLeg
import app.logdate.shared.model.location.LocationObservation
import app.logdate.shared.model.location.PlaceVisit
import app.logdate.shared.model.location.SemanticPlace
import app.logdate.shared.model.location.TravelMode
import app.logdate.ui.timeline.MomentAudioUiState
import com.android.tools.screenshot.PreviewTest
import kotlinx.datetime.LocalDate
import logdate.client.feature.location.timeline.generated.resources.Res
import logdate.client.feature.location.timeline.generated.resources.history_weekday_fri_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_mon_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_sat_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_sun_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_thu_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_tue_short
import logdate.client.feature.location.timeline.generated.resources.history_weekday_wed_short
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun memoryUuid(suffix: Int): Uuid = Uuid.parse("00000000-0000-0000-0000-0000000008%02d".format(suffix))

private val cafeMemory =
    HistoryMemoryUi(memoryUuid(1), UiText.Raw("A quiet table, a good book, and nowhere to rush."), HistoryMemoryKind.Text)
private val mothershipPlace = SemanticPlace("cafe", "Mothership Coffee", 36.1662, -115.1431, category = "COFFEE")
private val bakeryCandidate = SemanticPlace("bakery", "Sunrise Bakery", 36.1665, -115.1428, category = "FOOD")
private val sampleWeek =
    listOf(
        RecordedDayUi(
            LocalDate(2026, 9, 28),
            Res.string.history_weekday_mon_short,
            28,
            hasVisits = true,
            hasMemories = false,
            isToday = false,
        ),
        RecordedDayUi(
            LocalDate(2026, 9, 29),
            Res.string.history_weekday_tue_short,
            29,
            hasVisits = true,
            hasMemories = true,
            isToday = true,
        ),
        RecordedDayUi(
            LocalDate(2026, 9, 30),
            Res.string.history_weekday_wed_short,
            30,
            hasVisits = true,
            hasMemories = false,
            isToday = false,
        ),
        RecordedDayUi(
            LocalDate(2026, 10, 1),
            Res.string.history_weekday_thu_short,
            1,
            hasVisits = false,
            hasMemories = false,
            isToday = false,
        ),
        RecordedDayUi(
            LocalDate(2026, 10, 2),
            Res.string.history_weekday_fri_short,
            2,
            hasVisits = true,
            hasMemories = true,
            isToday = false,
        ),
        RecordedDayUi(
            LocalDate(2026, 10, 3),
            Res.string.history_weekday_sat_short,
            3,
            hasVisits = false,
            hasMemories = false,
            isToday = false,
        ),
        RecordedDayUi(
            LocalDate(2026, 10, 4),
            Res.string.history_weekday_sun_short,
            4,
            hasVisits = false,
            hasMemories = false,
            isToday = false,
        ),
    )

internal val historyDay =
    HumanLocationHistoryState(
        dateLabel = UiText.Raw("Tuesday, September 29"),
        daySummary = UiText.Raw("6 visits · 14.2 km · 4 memories"),
        selectedItemId = "cafe-morning",
        selectedDate = LocalDate(2026, 9, 29),
        timezoneBadgeLabel = UiText.Raw("Los Angeles · UTC-7"),
        weekDays = sampleWeek,
        items =
            listOf(
                HistoryItemUi(
                    "home-morning",
                    HistoryItemKind.Visit,
                    UiText.Raw("Home"),
                    UiText.Raw("Until 9:10 AM"),
                    UiText.Raw(""),
                    placeCategory = "HOME",
                ),
                HistoryItemUi(
                    "walk-cafe",
                    HistoryItemKind.Journey,
                    UiText.Raw("Walked"),
                    UiText.Raw("9:10–9:28 AM"),
                    UiText.Raw("1.2 km · 18 min"),
                    travelMode = TravelMode.WALKING,
                ),
                HistoryItemUi(
                    "cafe-morning",
                    HistoryItemKind.Visit,
                    UiText.Raw("Mothership Coffee"),
                    UiText.Raw("9:28–10:45 AM"),
                    UiText.Raw("Arts District · 1 hr 17 min"),
                    listOf(
                        cafeMemory,
                        HistoryMemoryUi(memoryUuid(2), UiText.Raw("Pour over"), HistoryMemoryKind.Image, thumbnailUri = "file:///p1.jpg"),
                        HistoryMemoryUi(memoryUuid(3), UiText.Raw("Latte art"), HistoryMemoryKind.Image, thumbnailUri = "file:///p2.jpg"),
                        HistoryMemoryUi(memoryUuid(4), UiText.Raw("Patio"), HistoryMemoryKind.Image, thumbnailUri = "file:///p3.jpg"),
                        HistoryMemoryUi(memoryUuid(5), UiText.Raw("Beans"), HistoryMemoryKind.Image, thumbnailUri = "file:///p4.jpg"),
                        HistoryMemoryUi(memoryUuid(6), UiText.Raw("Notebook"), HistoryMemoryKind.Image, thumbnailUri = "file:///p5.jpg"),
                        HistoryMemoryUi(memoryUuid(7), UiText.Raw("Street view"), HistoryMemoryKind.Image, thumbnailUri = "file:///p6.jpg"),
                    ),
                    placeId = "cafe",
                    placeCategory = "COFFEE",
                    placeConfirmed = false,
                    disambiguationCandidates =
                        listOf(
                            PlaceCandidateChipUi(mothershipPlace, UiText.Raw("Confirm Mothership Coffee"), isConfirmation = true),
                            PlaceCandidateChipUi(bakeryCandidate, UiText.Raw("Or Sunrise Bakery?")),
                        ),
                ),
                HistoryItemUi(
                    "walk-library",
                    HistoryItemKind.Journey,
                    UiText.Raw("Walked"),
                    UiText.Raw("10:45–11:12 AM"),
                    UiText.Raw("1.8 km · 27 min"),
                    travelMode = TravelMode.WALKING,
                ),
                HistoryItemUi(
                    "library",
                    HistoryItemKind.Visit,
                    UiText.Raw("Las Vegas Library"),
                    UiText.Raw("11:12 AM–1:20 PM"),
                    UiText.Raw("2 hr 8 min"),
                    listOf(
                        HistoryMemoryUi(memoryUuid(8), UiText.Raw("The book I came for"), HistoryMemoryKind.Image),
                        HistoryMemoryUi(
                            memoryUuid(10),
                            UiText.Raw("A thought to come back to"),
                            HistoryMemoryKind.Audio,
                            audio =
                                MomentAudioUiState(
                                    uri = "file:///screenshot-fixtures/library.m4a",
                                    durationMs = 42000,
                                    transcript = "I like how a familiar place can make room for a new idea.",
                                    noteId = memoryUuid(10),
                                ),
                        ),
                    ),
                ),
                HistoryItemUi(
                    "travelling",
                    HistoryItemKind.Journey,
                    UiText.Raw("Travelling"),
                    UiText.Raw("1:20–1:45 PM"),
                    UiText.Raw("~25 min"),
                    travelMode = TravelMode.UNKNOWN,
                    isConnector = true,
                ),
                HistoryItemUi(
                    "cafe-afternoon",
                    HistoryItemKind.Visit,
                    UiText.Raw("Mothership Coffee"),
                    UiText.Raw("1:45–2:10 PM"),
                    UiText.Raw("Back for another coffee · 25 min"),
                ),
                HistoryItemUi(
                    "bus",
                    HistoryItemKind.Journey,
                    UiText.Raw("Bus"),
                    UiText.Raw("2:10–2:42 PM"),
                    UiText.Raw("8.4 km · 32 min"),
                    travelMode = TravelMode.BUS,
                ),
                HistoryItemUi(
                    "brief",
                    HistoryItemKind.Visit,
                    UiText.Raw("Near Baker Park"),
                    UiText.Raw("2:42 PM"),
                    UiText.Raw("Briefly seen here; visit length unknown"),
                    isApproximate = true,
                ),
                HistoryItemUi(
                    "gap",
                    HistoryItemKind.Gap,
                    UiText.Raw("Signal interrupted"),
                    UiText.Raw("2:43–3:20 PM"),
                    UiText.Raw("Add a missing visit if you remember where you were."),
                ),
                HistoryItemUi(
                    "home-evening",
                    HistoryItemKind.Visit,
                    UiText.Raw("Home"),
                    UiText.Raw("From 3:20 PM"),
                    memories =
                        listOf(
                            HistoryMemoryUi(memoryUuid(11), UiText.Raw("The evening light"), HistoryMemoryKind.Video, UiText.Raw("0:18")),
                        ),
                ),
            ),
        places =
            listOf(
                HistoryPlaceUi(
                    "cafe",
                    UiText.Raw("Mothership Coffee"),
                    UiText.Raw("2 visits today · Arts District"),
                    listOf(cafeMemory),
                    category = "COFFEE",
                    visitCount = 2,
                ),
                HistoryPlaceUi(
                    "library",
                    UiText.Raw("Las Vegas Library"),
                    UiText.Raw("Visited today · 2 memories"),
                    category = "OUTDOORS",
                    visitCount = 1,
                ),
                HistoryPlaceUi("home", UiText.Raw("Home"), UiText.Raw("Last visited today"), category = "HOME", visitCount = 2),
            ),
    )

@PreviewTest
@ScreenshotPreviewMatrix
@Composable
fun HumanLocationHistoryDayScreenshot() = HistoryScene(historyDay)

@PreviewTest
@Preview(name = "History Dark Mode", device = PHONE, showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HumanLocationHistoryDarkScreenshot() = HistoryScene(historyDay, darkTheme = true)

@PreviewTest
@Preview(name = "History Standalone", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryStandaloneScreenshot() = HistoryScene(historyDay)

@PreviewTest
@Preview(name = "History Large Text", device = PHONE, fontScale = 1.8f, showBackground = true)
@Composable
fun HumanLocationHistoryLargeTextScreenshot() = HistoryScene(historyDay)

@PreviewTest
@Preview(name = "History Places", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryPlacesScreenshot() =
    HistoryScene(historyDay.copy(tab = HistoryTab.Places, placesFilterLabel = UiText.Raw("30 days")))

@PreviewTest
@Preview(name = "History Recovery", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryRecoveryScreenshot() =
    HistoryScene(
        historyDay.copy(recoveryMessage = UiText.Raw("Location is paused. Your saved days and memories are still here.")),
    )

@PreviewTest
@Preview(name = "History Visit Detail", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryDetailScreenshot() = HistoryDetailScene("cafe-morning")

@PreviewTest
@Preview(name = "History Audio", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryAudioScreenshot() = HistoryDetailScene("library")

@Composable
private fun HistoryScene(
    state: HumanLocationHistoryState,
    darkTheme: Boolean = false,
) {
    ScreenshotTheme(darkTheme = darkTheme) {
        HumanLocationHistoryContent(
            state,
            HumanLocationHistoryActions(
                onSelectDate = {},
                onJumpToToday = {},
                onConfirmPlace = { _, _ -> },
                onPlacesCategoryFilter = {},
                onPlacesSort = {},
            ),
            mapContent = { modifier -> HistoryMapFixture(modifier) },
            toolbarActions = { IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More location options") } },
        )
    }
}

@Composable
internal fun HistoryMapFixture(modifier: Modifier) {
    val time = Instant.parse("2026-09-29T10:00:00Z")
    val home = SemanticPlace("home", "Home", 36.1699, -115.1398, category = "HOME", userConfirmed = true)
    val cafe = SemanticPlace("cafe", "Mothership Coffee", 36.1662, -115.1431, category = "COFFEE", userConfirmed = true)
    val library = SemanticPlace("library", "Las Vegas Library", 36.1624, -115.1452, category = "OUTDOORS", userConfirmed = true)
    val park = SemanticPlace("park", "Near Baker Park", 36.1595, -115.1500)
    val visits =
        listOf(
            "home-morning" to home,
            "cafe-morning" to cafe,
            "library" to library,
            "cafe-afternoon" to cafe,
            "brief" to park,
            "home-evening" to home,
        ).map { (id, place) ->
            PlaceVisit(id, time, time, listOf(id), place.latitude, place.longitude, id != "brief", place)
        }

    fun leg(
        id: String,
        mode: TravelMode,
        from: SemanticPlace,
        to: SemanticPlace,
    ): JourneyLeg =
        JourneyLeg(
            id,
            time,
            time,
            listOf(from.id, to.id),
            mode,
            listOf(from, to).mapIndexed { index, place ->
                LocationObservation("$id-$index", "fixture", "fixture", time, place.latitude, place.longitude)
            },
        )
    HumanHistoryMap(
        visits +
            listOf(
                leg("walk-cafe", TravelMode.WALKING, home, cafe),
                leg("walk-library", TravelMode.WALKING, cafe, library),
                leg("bus", TravelMode.BUS, cafe, park),
            ),
        "cafe-morning",
        {},
        modifier,
        {},
    )
}

@PreviewTest
@Preview(name = "History Places Map", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryPlacesMapScreenshot() = HistoryScene(historyDay.copy(tab = HistoryTab.Places, placesMapVisible = true))

@PreviewTest
@Preview(name = "History Search Empty", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryNoSearchResultsScreenshot() = HistoryScene(historyDay.copy(tab = HistoryTab.Places, placesQuery = "park"))

@PreviewTest
@Preview(name = "History Places Empty", device = PHONE, showBackground = true)
@Composable
fun HumanLocationHistoryEmptyPlacesScreenshot() = HistoryScene(historyDay.copy(tab = HistoryTab.Places, places = emptyList()))

@Composable
private fun HistoryDetailScene(id: String) {
    ScreenshotTheme {
        Surface(Modifier.fillMaxSize()) {
            HumanLocationHistoryDetailContent(
                item = historyDay.items.first { it.id == id },
                actions = HumanLocationHistoryActions(),
                onRequestDelete = {},
            )
        }
    }
}
