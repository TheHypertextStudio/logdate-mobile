package app.logdate.client.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.logdate.client.MainActivity
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_DRAFT
import app.logdate.client.ambient.AMBIENT_PROMPT_TARGET_MEMORY_RECALL
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_DRAFT_ID
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_RECALL_DATE
import app.logdate.client.ambient.EXTRA_AMBIENT_PROMPT_TARGET
import app.logdate.client.bubble.RecordingBubbleNotifier
import app.logdate.client.feature.widgets.R
import app.logdate.client.feature.widgets.shortcuts.DynamicShortcutDescriptor
import app.logdate.client.rewind.EXTRA_REWIND_NOTIFICATION_ID
import app.logdate.client.rewind.EXTRA_REWIND_NOTIFICATION_TARGET
import app.logdate.client.rewind.REWIND_NOTIFICATION_TARGET_DETAIL
import coil3.ImageLoader
import io.github.aakira.napier.Napier
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Converts [DynamicShortcutDescriptor]s into Android `ShortcutInfoCompat` instances and
 * publishes them through `ShortcutManagerCompat`.
 *
 * Two logical groups end up in the same atomic `setDynamicShortcuts` call:
 *
 * - **Launcher group** — `ContinueDraft`, `TodayTimeline`, `WeekRewind`. These reuse the
 *   existing AMBIENT_PROMPT and REWIND_NOTIFICATION intent extras that
 *   `MainActivity.resolveNavKey` already understands. Synchronous to build (vector
 *   drawable icons), and ranked 0..2 so they appear at the top of the launcher long-press
 *   menu.
 * - **Sharing group** — one [DynamicShortcutDescriptor.ShareToJournal] per journal,
 *   marked with the conversation category so the OS hoists them into the share sheet.
 *   Each one carries the journal's icon from [JournalShortcutIcons]: its cover photo, or
 *   the colored monogram the in-app `JournalCover` uses, so a journal looks the same
 *   everywhere. Ranked at [SHARING_RANK_BASE]+ so they overflow past the launcher
 *   entries in long-press while staying eligible for the share sheet.
 *
 * The publisher emits sharing descriptors uncapped; this applier queries the
 * system's per-activity shortcut limit at apply time and trims the sharing tail
 * if needed (most-recently-updated journals win, since the publisher already
 * sorts by `lastUpdated`).
 *
 * `apply` is `suspend` because the per-journal icon loading is async — the
 * worker that calls it is already a CoroutineWorker, so this is natural.
 */
class AndroidDynamicShortcutApplier(
    private val context: Context,
    imageLoader: ImageLoader,
) {
    private val journalIcons = JournalShortcutIcons(context, imageLoader)

    suspend fun apply(descriptors: List<DynamicShortcutDescriptor>): Boolean {
        val (sharingDescriptors, launcherDescriptors) =
            descriptors.partition { it is DynamicShortcutDescriptor.ShareToJournal }

        // A shortcut a live surface depends on, such as the floating recorder's conversation,
        // survives the refresh; dropping it would orphan that surface.
        val liveShortcuts =
            ShortcutManagerCompat.getDynamicShortcuts(context).filter { it.id in LIVE_SURFACE_SHORTCUT_IDS }
        val systemLimit =
            dynamicShortcutSlots(
                maxPerActivity = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context),
                manifestShortcuts = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_MANIFEST).size,
                liveShortcuts = liveShortcuts.size,
            )
        val launcherSlots = launcherDescriptors.take(systemLimit)
        val remainingSlots = (systemLimit - launcherSlots.size).coerceAtLeast(0)
        val sharingSlots =
            sharingDescriptors
                .filterIsInstance<DynamicShortcutDescriptor.ShareToJournal>()
                .take(remainingSlots)

        val launcherShortcuts =
            launcherSlots.mapIndexed { index, descriptor ->
                buildLauncherShortcut(descriptor, rank = index)
            }

        val sharingShortcuts =
            coroutineScope {
                sharingSlots
                    .mapIndexed { index, descriptor ->
                        async { buildShareToJournal(descriptor, rank = SHARING_RANK_BASE + index) }
                    }.awaitAll()
            }

        val publishSucceeded =
            ShortcutManagerCompat.setDynamicShortcuts(context, launcherShortcuts + sharingShortcuts + liveShortcuts)
        if (!publishSucceeded) {
            Napier.w("Dynamic shortcut publish was rate-limited by ShortcutManagerCompat")
        }
        return publishSucceeded
    }

    private fun buildLauncherShortcut(
        descriptor: DynamicShortcutDescriptor,
        rank: Int,
    ): ShortcutInfoCompat =
        when (descriptor) {
            is DynamicShortcutDescriptor.ContinueDraft -> {
                buildContinueDraft(descriptor, rank)
            }

            is DynamicShortcutDescriptor.TodayTimeline -> {
                buildTodayTimeline(descriptor, rank)
            }

            is DynamicShortcutDescriptor.WeekRewind -> {
                buildWeekRewind(descriptor, rank)
            }

            is DynamicShortcutDescriptor.ShareToJournal -> {
                error("Sharing descriptors are routed through the async builder")
            }
        }

    private fun buildContinueDraft(
        descriptor: DynamicShortcutDescriptor.ContinueDraft,
        rank: Int,
    ): ShortcutInfoCompat {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_DRAFT)
                putExtra(EXTRA_AMBIENT_PROMPT_DRAFT_ID, descriptor.draftId.toString())
            }
        return ShortcutInfoCompat
            .Builder(context, descriptor.id)
            .setShortLabel(context.getString(R.string.dynamic_shortcut_continue_draft_short))
            .setLongLabel(context.getString(R.string.dynamic_shortcut_continue_draft_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_continue_draft))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private fun buildTodayTimeline(
        descriptor: DynamicShortcutDescriptor.TodayTimeline,
        rank: Int,
    ): ShortcutInfoCompat {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_AMBIENT_PROMPT_TARGET, AMBIENT_PROMPT_TARGET_MEMORY_RECALL)
                putExtra(EXTRA_AMBIENT_PROMPT_RECALL_DATE, descriptor.date.toString())
            }
        return ShortcutInfoCompat
            .Builder(context, descriptor.id)
            .setShortLabel(context.getString(R.string.dynamic_shortcut_today_short))
            .setLongLabel(context.getString(R.string.dynamic_shortcut_today_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_today))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private fun buildWeekRewind(
        descriptor: DynamicShortcutDescriptor.WeekRewind,
        rank: Int,
    ): ShortcutInfoCompat {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_REWIND_NOTIFICATION_TARGET, REWIND_NOTIFICATION_TARGET_DETAIL)
                putExtra(EXTRA_REWIND_NOTIFICATION_ID, descriptor.rewindId.toString())
            }
        return ShortcutInfoCompat
            .Builder(context, descriptor.id)
            .setShortLabel(context.getString(R.string.dynamic_shortcut_week_rewind_short))
            .setLongLabel(context.getString(R.string.dynamic_shortcut_week_rewind_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_week_rewind))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private suspend fun buildShareToJournal(
        descriptor: DynamicShortcutDescriptor.ShareToJournal,
        rank: Int,
    ): ShortcutInfoCompat {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_SHORTCUT_TARGET_JOURNAL_ID, descriptor.journalId.toString())
            }
        val icon = journalIcons.icon(descriptor.journalId, descriptor.journalTitle, descriptor.coverImageUri)
        val person =
            Person
                .Builder()
                .setName(descriptor.journalTitle)
                .setKey(descriptor.id)
                .build()
        return ShortcutInfoCompat
            .Builder(context, descriptor.id)
            // The share sheet shows the long label when there is one; a "Share to" prefix there
            // pushes the journal's name out of its two short lines, so the name is the only label.
            .setShortLabel(descriptor.journalTitle)
            .setIcon(icon)
            .setIntent(intent)
            .setRank(rank)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(descriptor.id))
            .setCategories(setOf(SHORTCUT_CATEGORY_CONVERSATION))
            .setPerson(person)
            .build()
    }

    private companion object {
        /**
         * Rank offset that pushes sharing shortcuts past launcher shortcuts in long-press
         * menu ordering. Sharing shortcuts still appear in the share sheet regardless of
         * rank — this only affects launcher long-press visibility.
         */
        const val SHARING_RANK_BASE = 100

        /** Dynamic shortcuts owned by a running surface rather than by this refresh. */
        private val LIVE_SURFACE_SHORTCUT_IDS = setOf(RecordingBubbleNotifier.SHORTCUT_ID)

        /**
         * Conversation category required for the system to surface a dynamic shortcut as a
         * Direct Share target. Mirrored from `ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION`.
         */
        const val SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation"
    }
}
