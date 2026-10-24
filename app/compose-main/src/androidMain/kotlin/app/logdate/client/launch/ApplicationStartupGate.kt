package app.logdate.client.launch

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.logdate.client.ApplicationStartupCoordinator
import app.logdate.client.database.NativeRestoreInProgressException
import app.logdate.client.database.NewerDatabaseVersionException
import app.logdate.client.database.encryption.DatabaseKeyRecoveryRequiredException
import app.logdate.client.device.restore.NativeRestoreDeliveryGate
import app.logdate.client.device.restore.NativeRestorePrivacyMetadataRequiredException
import app.logdate.client.device.restore.retainedJournalWorkspace
import app.logdate.client.restore.NativeMediaQuarantinePendingException
import app.logdate.ui.GenericLoadingScreen
import app.logdate.ui.theme.LogDateTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import studio.hypertext.logdate.R

/** Attaches a responsive loading surface before resolving any storage-backed dependency. */
internal fun FragmentActivity.whenApplicationReady(onReady: () -> Unit) {
    val startup = ApplicationStartupCoordinator.get(application)
    if (startup.state.value is ApplicationStartupState.Ready) {
        onReady()
        return
    }
    setContent {
        val state by startup.state.collectAsState()
        val failure = state as? ApplicationStartupState.Failed
        LogDateTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                if (state is ApplicationStartupState.Loading) GenericLoadingScreen()
                if (failure != null) {
                    val message = applicationStartupFailureMessage(failure.cause)
                    AlertDialog(
                        onDismissRequest = {},
                        title = {
                            Text(stringResource(message.titleResource()))
                        },
                        text = {
                            Text(
                                stringResource(message.stringResource()),
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        },
                        confirmButton = {
                            val updateRequired = message == ApplicationStartupFailureMessage.UPDATE_REQUIRED
                            TextButton(onClick = { if (updateRequired) openJournalAppUpdate() else startup.start() }) {
                                Text(
                                    stringResource(
                                        if (updateRequired) {
                                            R.string.database_update_required_action
                                        } else {
                                            R.string.application_startup_retry
                                        },
                                    ),
                                )
                            }
                        },
                        dismissButton = {
                            if (message == ApplicationStartupFailureMessage.DATABASE_KEY_RECOVERY) {
                                TextButton(onClick = {
                                    startup.recoverRetainedJournal {
                                        GlobalContext.get().get<NativeRestoreDeliveryGate>().withLock {
                                            retainedJournalWorkspace(applicationContext).prepare()
                                        }
                                    }
                                }) { Text(stringResource(R.string.database_key_recovery_backup_action)) }
                            }
                        },
                    )
                }
            }
        }
    }
    lifecycleScope.launch {
        startup.state.first { it is ApplicationStartupState.Ready }
        onReady()
    }
}

internal enum class ApplicationStartupFailureMessage {
    GENERIC,
    NATIVE_RESTORE_PENDING,
    NATIVE_MEDIA_PENDING,
    STORAGE_FULL,
    DATABASE_KEY_RECOVERY,
    PRIVACY_METADATA_RECOVERY,
    UPDATE_REQUIRED,
}

internal fun applicationStartupFailureMessage(cause: Throwable): ApplicationStartupFailureMessage =
    when {
        cause.hasStorageFullCause() -> {
            ApplicationStartupFailureMessage.STORAGE_FULL
        }

        cause.hasNativeMediaQuarantinePending() -> {
            ApplicationStartupFailureMessage.NATIVE_MEDIA_PENDING
        }

        cause.hasNativeRestoreInProgress() -> {
            ApplicationStartupFailureMessage.NATIVE_RESTORE_PENDING
        }

        cause.causesAndSuppressed().any { it is NewerDatabaseVersionException } -> {
            ApplicationStartupFailureMessage.UPDATE_REQUIRED
        }

        cause.causesAndSuppressed().any { it is NativeRestorePrivacyMetadataRequiredException } -> {
            ApplicationStartupFailureMessage.PRIVACY_METADATA_RECOVERY
        }

        cause.causesAndSuppressed().any {
            it is DatabaseKeyRecoveryRequiredException
        } -> {
            ApplicationStartupFailureMessage.DATABASE_KEY_RECOVERY
        }

        else -> {
            ApplicationStartupFailureMessage.GENERIC
        }
    }

private fun ApplicationStartupFailureMessage.titleResource(): Int =
    when (this) {
        ApplicationStartupFailureMessage.UPDATE_REQUIRED -> R.string.database_update_required_title

        ApplicationStartupFailureMessage.DATABASE_KEY_RECOVERY,
        ApplicationStartupFailureMessage.PRIVACY_METADATA_RECOVERY,
        -> R.string.database_key_recovery_title

        else -> R.string.application_startup_failed_title
    }

private fun ApplicationStartupFailureMessage.stringResource(): Int =
    when (this) {
        ApplicationStartupFailureMessage.GENERIC -> R.string.application_startup_failed_message
        ApplicationStartupFailureMessage.NATIVE_RESTORE_PENDING -> R.string.native_restore_startup_incomplete_message
        ApplicationStartupFailureMessage.NATIVE_MEDIA_PENDING -> R.string.native_media_quarantine_pending_message
        ApplicationStartupFailureMessage.STORAGE_FULL -> R.string.native_media_quarantine_storage_message
        ApplicationStartupFailureMessage.DATABASE_KEY_RECOVERY -> R.string.database_key_recovery_message
        ApplicationStartupFailureMessage.PRIVACY_METADATA_RECOVERY -> R.string.native_privacy_metadata_recovery_message
        ApplicationStartupFailureMessage.UPDATE_REQUIRED -> R.string.database_update_required_message
    }

private fun FragmentActivity.openJournalAppUpdate() {
    val opened =
        openLogDateUpdate(packageName) { url ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    if (!opened) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.database_update_required_action),
                "https://play.google.com/store/apps/details?id=$packageName",
            ),
        )
        Toast.makeText(this, R.string.database_update_link_copied, Toast.LENGTH_LONG).show()
    }
}

private fun Throwable.hasNativeRestoreInProgress(): Boolean = causesAndSuppressed().any { it is NativeRestoreInProgressException }

private fun Throwable.hasNativeMediaQuarantinePending(): Boolean = causesAndSuppressed().any { it is NativeMediaQuarantinePendingException }

private fun Throwable.hasStorageFullCause(): Boolean =
    causesAndSuppressed().any { cause ->
        cause.javaClass.simpleName == "SQLiteFullException" ||
            cause.message
                ?.lowercase()
                ?.let { "no space left" in it || "disk is full" in it || "database or disk is full" in it } == true
    }

private fun Throwable.causesAndSuppressed(): Sequence<Throwable> =
    sequence {
        val pending = ArrayDeque<Throwable>()
        val visited = mutableSetOf<Throwable>()
        pending.addLast(this@causesAndSuppressed)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            yield(current)
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
    }
