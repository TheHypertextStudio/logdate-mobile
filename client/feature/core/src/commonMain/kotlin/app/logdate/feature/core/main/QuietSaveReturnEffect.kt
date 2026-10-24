@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.main

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import app.logdate.client.domain.notes.drafts.QuietSaveMessageKind
import app.logdate.client.domain.notes.drafts.quietSaveMessageKind
import app.logdate.client.domain.reminders.WindDownTimeResolver
import app.logdate.client.health.SleepRepository
import app.logdate.client.repository.account.AccountRepository
import app.logdate.client.repository.journals.EntryDraftRepository
import app.logdate.client.repository.journals.JournalNote
import app.logdate.util.formatDateLocalized
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.quiet_save_bedtime
import logdate.client.feature.core.generated.resources.quiet_save_dated
import logdate.client.feature.core.generated.resources.quiet_save_morning
import logdate.client.feature.core.generated.resources.quiet_save_neutral
import logdate.client.feature.core.generated.resources.quiet_save_open_draft
import org.jetbrains.compose.resources.getString
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import kotlin.time.Clock

@Composable
internal fun QuietSaveReturnEffect(
    host: SnackbarHostState,
    openDraft: (String) -> Unit,
) {
    val flags = getKoin().getOrNull<FeatureFlagStore>() ?: return
    val available by remember(flags) { flags.observe(FeatureFlag.QUIET_SAVE) }.collectAsStateWithLifecycle(false)
    if (!available) return
    val repository: EntryDraftRepository = koinInject()
    val accounts: AccountRepository = koinInject()
    val sleep: SleepRepository = koinInject()
    val account by accounts.currentAccount.collectAsStateWithLifecycle(null)
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow
        .collectAsState()
    val owner = account?.id?.toString() ?: "anonymous"
    LaunchedEffect(owner, state) {
        if (state != Lifecycle.State.RESUMED) return@LaunchedEffect
        try {
            val draft =
                repository
                    .getDrafts()
                    .first { entries ->
                        entries.any { it.quietSave?.let { result -> !result.acknowledged && result.ownerId == owner } == true }
                    }.filter { it.quietSave?.let { result -> !result.acknowledged && result.ownerId == owner } == true }
                    .maxBy { it.quietSave!!.savedAt }
            val outcome = draft.quietSave ?: return@LaunchedEffect
            val audio =
                draft.notes.filterIsInstance<JournalNote.Audio>().find { it.uid.toString() == outcome.blockId } ?: return@LaunchedEffect
            val viewingZone = TimeZone.currentSystemDefault()
            val knownCaptureZone = audio.timeZoneId?.let { runCatching { TimeZone.of(it) }.getOrNull() }
            val captureZone = knownCaptureZone ?: viewingZone
            val now = Clock.System.now()
            val hours =
                if (knownCaptureZone == null) {
                    null
                } else {
                    withTimeoutOrNull(2_000) { WindDownTimeResolver(sleep).resolveKnown(now, captureZone) }
                }
            val message =
                when (quietSaveMessageKind(outcome.savedAt, now, captureZone, viewingZone, hours)) {
                    QuietSaveMessageKind.Neutral -> {
                        getString(Res.string.quiet_save_neutral)
                    }

                    QuietSaveMessageKind.Bedtime -> {
                        getString(Res.string.quiet_save_bedtime)
                    }

                    QuietSaveMessageKind.Morning -> {
                        getString(Res.string.quiet_save_morning)
                    }

                    QuietSaveMessageKind.Dated -> {
                        getString(
                            Res.string.quiet_save_dated,
                            formatDateLocalized(audio.creationTimestamp.toLocalDateTime(captureZone).date),
                        )
                    }
                }
            val result =
                showQuietSaveReturn(host, message, getString(Res.string.quiet_save_open_draft)) {
                    withContext(NonCancellable) {
                        if ((
                                accounts.currentAccount
                                    .first()
                                    ?.id
                                    ?.toString() ?: "anonymous"
                            ) == owner &&
                            repository.getDraft(draft.id).first().isSuccess
                        ) {
                            repository.acknowledgeQuietSave(draft.id)
                        }
                    }
                }
            if ((
                    accounts.currentAccount
                        .first()
                        ?.id
                        ?.toString() ?: "anonymous"
                ) != owner
            ) {
                return@LaunchedEffect
            }
            if (repository.getDraft(draft.id).first().isFailure) return@LaunchedEffect
            withContext(NonCancellable) {
                if (result == SnackbarResult.ActionPerformed &&
                    (
                        accounts.currentAccount
                            .first()
                            ?.id
                            ?.toString() ?: "anonymous"
                    ) == owner
                ) {
                    openDraft(draft.id.toString())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Napier.w("Quiet Save acknowledgement unavailable; retaining it for the next visit", e)
        }
    }
}

internal suspend fun showQuietSaveReturn(
    host: SnackbarHostState,
    message: String,
    actionLabel: String,
    acknowledge: suspend () -> Unit,
): SnackbarResult =
    coroutineScope {
        val shown =
            async(start = CoroutineStart.UNDISPATCHED) {
                host.showSnackbar(message, actionLabel, withDismissAction = true, duration = SnackbarDuration.Long)
            }
        snapshotFlow { host.currentSnackbarData }.first { it?.visuals?.message == message }
        acknowledge()
        shown.await()
    }
