package app.logdate.wear.v2.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.domain.activity.ActivityPresenter
import app.logdate.client.domain.activity.ActivityUiState
import app.logdate.client.domain.streak.CampfireState
import app.logdate.client.domain.streak.ObserveCampfireUseCase
import app.logdate.client.domain.today.TodaySummaryCalculator
import app.logdate.client.repository.journals.JournalNote
import app.logdate.client.repository.journals.JournalNotesRepository
import app.logdate.client.sync.SyncPausedReason
import app.logdate.client.sync.SyncStatus
import app.logdate.client.sync.peer.state.PeerLinkState
import app.logdate.wear.capture.Feeling
import app.logdate.wear.sync.v2.link.WatchLinkRecord
import app.logdate.wear.sync.v2.link.WatchLinkStore
import app.logdate.wear.sync.v2.link.peerLinkState
import app.logdate.wear.v2.phone.HandoffResult
import app.logdate.wear.v2.phone.PhoneHandoff
import app.logdate.wear.v2.phone.PhoneLink
import app.logdate.wear.v2.phone.PhoneLinkRepository
import app.logdate.wear.v2.settings.WatchSettingsViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Today on the watch: the day's moments, places and voice time, the campfire, and an offer to get
 * LogDate on a paired Android phone that lacks it or still needs watch setup. Setup is offered again
 * when the watch's sync waits for its phone to link it. The day moves at midnight and when the time
 * zone changes.
 */
class TodayViewModel(
    dayClock: DayClock,
    notesRepository: JournalNotesRepository,
    observeCampfire: ObserveCampfireUseCase,
    phoneLinks: PhoneLinkRepository,
    watchLinks: WatchLinkStore,
    private val phoneHandoff: PhoneHandoff,
    syncStatus: Flow<SyncStatus>,
    private val activity: ActivityPresenter? = null,
) : ViewModel() {
    private val mutableHandoff = MutableStateFlow(HandoffState.IDLE)

    val handoff: StateFlow<HandoffState> = mutableHandoff.asStateFlow()

    val uiState: StateFlow<TodayUiState> =
        combine(
            observeDay(dayClock, notesRepository),
            observeCampfire(),
            phoneLinks.observe(),
            watchLinks.observe(),
            syncStatus.map { it.pausedReason == SyncPausedReason.NEEDS_DEVICE_LINK }.distinctUntilChanged(),
        ) { day, campfire, phone, link, needsLink ->
            readyState(day, campfire, phone, link, needsLink)
        }.combine((activity?.state ?: flowOf()).onStart { emit(ActivityUiState()) }) { state, doing ->
            if (state is TodayUiState.Ready) state.copy(activity = doing) else state
        }.catch { error ->
            Napier.e("Couldn't load today on the watch", error)
            emit(TodayUiState.Failed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState.Loading)

    /** Opens LogDate's Play listing on the phone Today offered it for. */
    fun getPhoneApp() {
        val card = (uiState.value as? TodayUiState.Ready)?.phoneCard as? PhoneCard.GetApp ?: return
        if (mutableHandoff.value == HandoffState.SENDING) return
        mutableHandoff.value = HandoffState.SENDING
        viewModelScope.launch {
            mutableHandoff.value =
                when (phoneHandoff.openAppListing(card.nodeId)) {
                    HandoffResult.SENT -> HandoffState.SENT
                    HandoffResult.FAILED -> HandoffState.FAILED
                }
        }
    }

    /** Opens the Watch setup destination on the paired phone while this watch is not linked. */
    fun setUpPhone() {
        val card = (uiState.value as? TodayUiState.Ready)?.phoneCard as? PhoneCard.SetUp ?: return
        if (mutableHandoff.value == HandoffState.SENDING) return
        mutableHandoff.value = HandoffState.SENDING
        viewModelScope.launch {
            mutableHandoff.value =
                when (phoneHandoff.open(WatchSettingsViewModel.PHONE_WATCH_SETTINGS_LINK, card.nodeId)) {
                    HandoffResult.SENT -> HandoffState.SENT
                    HandoffResult.FAILED -> HandoffState.FAILED
                }
        }
    }

    /** The person dismissed the suggestion or the morning start. */
    fun dismissActivity() {
        val presenter = activity ?: return
        viewModelScope.launch { presenter.dismiss() }
    }

    /** The person started a recording from the suggestion or the morning start. */
    fun usedActivity() {
        val presenter = activity ?: return
        viewModelScope.launch { presenter.actedOn() }
    }

    fun dismissHandoff() {
        mutableHandoff.value = HandoffState.IDLE
    }

    private data class Day(
        val window: TodayWindow,
        val notes: List<JournalNote>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeDay(
        dayClock: DayClock,
        notesRepository: JournalNotesRepository,
    ): Flow<Day> =
        dayClock.observe().flatMapLatest { window ->
            notesRepository.observeNotesInRange(window.start, window.end).map { notes ->
                Day(window = window, notes = notes.filter { note -> note.creationTimestamp in window })
            }
        }

    private fun readyState(
        day: Day,
        campfire: CampfireState?,
        phone: PhoneLink,
        link: WatchLinkRecord?,
        needsLink: Boolean,
    ): TodayUiState {
        val summary = TodaySummaryCalculator.calculate(day.notes) { tag -> Feeling.fromTag(tag) != null }
        return TodayUiState.Ready(
            date = day.window.date,
            timeZone = day.window.timeZone,
            summary = summary,
            feeling = summary.feelingTag?.let(Feeling::fromTag),
            campfire = campfire?.toPresentation(),
            phoneCard =
                when (phone) {
                    is PhoneLink.AppMissing -> {
                        PhoneCard.GetApp(phone.nodeId)
                    }

                    is PhoneLink.Connected -> {
                        if (link?.phoneNode == phone.nodeId && link.peerLinkState() == PeerLinkState.LINKED && !needsLink) {
                            PhoneCard.None
                        } else {
                            PhoneCard.SetUp(phone.nodeId)
                        }
                    }

                    else -> {
                        PhoneCard.None
                    }
                },
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
