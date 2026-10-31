package app.logdate.client

import android.app.ActivityManager
import android.app.HandoffActivityData
import android.app.HandoffActivityDataRequestInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.WindowMetricsCalculator
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.editor.editorWindowTitle
import app.logdate.client.launch.whenApplicationReady
import app.logdate.client.location.tracking.LocationTrackingManager
import app.logdate.feature.editor.handoff.DraftRefreshResult
import app.logdate.feature.editor.handoff.DraftRefresher
import app.logdate.feature.editor.handoff.HandoffDraftGate
import app.logdate.feature.editor.handoff.HandoffDraftReceiver
import app.logdate.feature.editor.ui.NoteEditorScreen
import app.logdate.feature.editor.ui.editor.EditorState
import app.logdate.feature.editor.ui.editor.EntryEditorViewModel
import app.logdate.feature.editor.ui.editor.TextBlockUiState
import app.logdate.ui.keyboard.AppShortcutScope
import app.logdate.ui.keyboard.appKeyboardShortcutGroups
import app.logdate.ui.platform.enableTransparentSystemBars
import app.logdate.ui.theme.LogDateTheme
import io.github.aakira.napier.Napier
import io.github.vinceglb.filekit.core.FileKit
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import studio.hypertext.logdate.R
import kotlin.uuid.Uuid

/**
 * Activity dedicated to handling editor windows.
 *
 * This activity is designed to be launched multiple times, with each instance having its own window
 * and editor state. Each instance is independent and can be managed separately on devices that support
 * multi-window mode (Android N+).
 *
 * The activity supports two modes:
 * 1. Creating new entries: Launch with initialTextContent and/or attachments to create a new entry
 * 2. Editing existing entries: Launch with entryId to load and edit an existing entry
 *
 * Auto-save is triggered when the activity is paused (moved to background). Window metrics are updated
 * when the activity is resumed to handle device rotations and foldable/split-screen layout changes.
 */
class EditorActivity : FragmentActivity() {
    private val viewModel by viewModel<EntryEditorViewModel>()
    private val locationTrackingManager: LocationTrackingManager by inject()
    private val featureFlags: FeatureFlagStore by inject()
    private val draftRefresher: DraftRefresher by inject()
    private val handoffDraftReceiver: HandoffDraftReceiver by inject()

    private var bootstrapReady = false

    // We'll implement EditorInstanceId handling later
    private var instanceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableTransparentSystemBars()
        whenApplicationReady {
            bootstrapReady = true
            completeStartup()
        }
    }

    private fun completeStartup() {
        // Initialize FileKit for file operations
        FileKit.init(this)

        // Extract entry ID if editing an existing entry.
        // When users click "Open in New Window" on a timeline entry, this activity is launched
        // with the entry ID to load and edit that specific entry.
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)?.let { Uuid.parse(it) }
        val journalId = intent.getStringExtra(EXTRA_JOURNAL_ID)?.let { Uuid.parse(it) }

        // Extract initial content if provided (for new entries).
        // This is used when creating a brand new entry (e.g., from Share intent or new entry button).
        val initialText = intent.getStringExtra(EXTRA_INITIAL_TEXT)
        val attachmentUris = intent.getStringArrayListExtra(EXTRA_ATTACHMENTS)?.toList() ?: emptyList()

        // Extract pre-selected journal ids for new entries (e.g., from a per-journal sharing shortcut).
        val initialJournalIds =
            intent
                .getStringArrayListExtra(EXTRA_INITIAL_JOURNAL_IDS)
                ?.mapNotNull { runCatching { Uuid.parse(it) }.getOrNull() }
                ?: emptyList()

        // Android Handoff opens the editor on another device with the draft it was writing.
        val handoffDraftId = handoffDraftId(intent.getStringExtra(EXTRA_HANDOFF_DRAFT_ID))

        // Configure window features
        enableTransparentSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        // Set up lifecycle-aware operations
        setupLifecycleHandling()
        followWindowTitle(isExistingEntry = entryId != null)
        followEditorHandoffFlag(featureFlags)

        setContent {
            LogDateTheme {
                val editor: @Composable (Uuid?) -> Unit = { draftId ->
                    NoteEditorScreen(
                        onNavigateBack = { finish() },
                        onEntrySaved = {
                            setResult(RESULT_OK)
                            finish()
                        },
                        entryId = entryId,
                        draftId = draftId,
                        journalId = journalId,
                        journalIds = initialJournalIds,
                        initialTextContent = initialText,
                        attachments = attachmentUris,
                        viewModel = viewModel,
                    )
                }
                if (handoffDraftId != null) {
                    HandoffDraftGate(draftId = handoffDraftId, receiver = handoffDraftReceiver, editor = editor)
                } else {
                    editor(null)
                }
            }
        }
    }

    /**
     * Hands this editor to another of the person's devices. Text not saved as a draft yet is handed
     * off under the id its first draft is about to be saved with. When the person picks LogDate there
     * ([HandoffActivityDataRequestInfo.isActiveRequest]), the latest text is also saved and uploaded
     * at once instead of on backup's schedule, so the other device can fetch it while it waits.
     */
    override fun onHandoffActivityDataRequested(info: HandoffActivityDataRequestInfo): HandoffActivityData? {
        if (!bootstrapReady) return null
        val draftId = viewModel.draftIdForHandoff()
        if (info.isActiveRequest) uploadDraftForHandoff(viewModel.editorState.value)
        return editorHandoffData(draftId)
    }

    private fun uploadDraftForHandoff(state: EditorState) {
        lifecycleScope.launch {
            if (state.isDirty) viewModel.persistDraft(state)
            when (val result = draftRefresher.refresh()) {
                DraftRefreshResult.Refreshed -> Napier.d("Uploaded the draft for handoff")
                DraftRefreshResult.Unavailable -> Napier.w("The draft was not uploaded for handoff: backup is off or needs attention")
                is DraftRefreshResult.Failed -> Napier.w("The draft was not uploaded for handoff: ${result.message}")
            }
        }
    }

    /**
     * Names this window after the first line of the entry, so several editor windows can be told
     * apart in Recents, the taskbar, and desktop caption bars.
     */
    private fun followWindowTitle(isExistingEntry: Boolean) {
        val fallback = getString(if (isExistingEntry) R.string.editor_window_existing_entry else R.string.editor_window_new_entry)
        lifecycleScope.launch {
            viewModel.editorState
                .map { state ->
                    editorWindowTitle(
                        state.blocks
                            .filterIsInstance<TextBlockUiState>()
                            .firstOrNull()
                            ?.content,
                    ) ?: fallback
                }.distinctUntilChanged()
                .collect { title -> setTaskTitle(title) }
        }
    }

    private fun setTaskTitle(title: String) {
        val description =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityManager.TaskDescription
                    .Builder()
                    .setLabel(title)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(title)
            }
        setTaskDescription(description)
        this.title = title
    }

    /**
     * Sets up handling of lifecycle events to ensure editor content is saved
     * when appropriate and window size changes are handled.
     */
    private fun setupLifecycleHandling() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Update window metrics whenever the window becomes active
                updateWindowMetrics()
            }
        }

        // Trigger auto-save when pausing the activity (going to background)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Napier.d("Editor window is visible")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        locationTrackingManager.onActivityPaused()
        // Auto-save content when going to background, but not if we're
        // already saving or about to exit (which would resurrect a deleted draft)
        val editorState = viewModel.editorState.value
        if (editorState.isDirty && !editorState.shouldExit && !editorState.isSaving) {
            Napier.d("Auto-saving editor content on pause")
            viewModel.autoSaveEntry(editorState)
        }
    }

    override fun onResume() {
        super.onResume()
        locationTrackingManager.onActivityResumed()
    }

    /**
     * Calculates and updates window metrics for the editor.
     */
    private fun updateWindowMetrics() {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this)
        val widthDp = metrics.bounds.width() / resources.displayMetrics.density
        val heightDp = metrics.bounds.height() / resources.displayMetrics.density

        Napier.d("Window metrics updated: ${widthDp}dp x ${heightDp}dp")

        // Adapt UI based on window size if needed
        // For example, we could enable/disable certain features based on window size
        // This is handled internally in the ViewModel to keep UI concerns separate
    }

    companion object {
        private const val EXTRA_INSTANCE_ID = "editor_instance_id"
        private const val EXTRA_ENTRY_ID = "entry_id"
        private const val EXTRA_JOURNAL_ID = "journal_id"
        private const val EXTRA_INITIAL_TEXT = "initial_text"
        private const val EXTRA_ATTACHMENTS = "attachments"
        private const val EXTRA_INITIAL_JOURNAL_IDS = "initial_journal_ids"

        /**
         * Creates an intent to launch a new editor window.
         *
         * @param context The application context
         * @param entryId Optional ID for editing an existing entry
         * @param journalId Optional journal ID for context when editing
         * @param initialText Optional initial text content for new entries
         * @param attachments Optional list of attachment URIs
         * @param journalIds Optional pre-selected journal IDs for new entries (e.g. when a
         *   sharing shortcut targets a specific journal). Distinct from [journalId] which
         *   is used only for editing-context on existing entries.
         * @return Intent configured to launch the editor activity
         */
        fun createIntent(
            context: Context,
            entryId: Uuid? = null,
            journalId: Uuid? = null,
            initialText: String? = null,
            attachments: List<String>? = null,
            journalIds: List<Uuid> = emptyList(),
        ): Intent =
            Intent(context, EditorActivity::class.java).apply {
                entryId?.let {
                    putExtra(EXTRA_ENTRY_ID, it.toString())
                }
                journalId?.let {
                    putExtra(EXTRA_JOURNAL_ID, it.toString())
                }
                initialText?.let {
                    putExtra(EXTRA_INITIAL_TEXT, it)
                }
                attachments?.let {
                    putStringArrayListExtra(EXTRA_ATTACHMENTS, ArrayList(it))
                }
                if (journalIds.isNotEmpty()) {
                    putStringArrayListExtra(
                        EXTRA_INITIAL_JOURNAL_IDS,
                        ArrayList(journalIds.map { it.toString() }),
                    )
                }

                // Flags to launch as a new document/task
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }
    }

    /** Lists LogDate's keyboard shortcuts in Android's shortcut helper (Meta+/). */
    override fun onProvideKeyboardShortcuts(
        data: MutableList<KeyboardShortcutGroup>,
        menu: Menu?,
        deviceId: Int,
    ) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        data += appKeyboardShortcutGroups(listOf(AppShortcutScope.Editor, AppShortcutScope.App))
    }
}
