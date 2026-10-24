package app.logdate.client.feature.widgets

import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import app.logdate.client.device.restore.NativeRestoredPreferenceFiles
import app.logdate.client.device.restore.PostRestoreDetector
import io.github.aakira.napier.Napier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.uuid.Uuid

/** Canonical provider identities stay stable while manifest delivery survives restore teardown. */
abstract class DurableWidgetReceiver : GlanceAppWidgetReceiver() {
    final override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val receiver = javaClass.simpleName
        if (intent.action == widgetRuntimeAction(context, receiver)) {
            check(Application.getProcessName() == context.packageName)
            val id = requireNotNull(intent.getStringExtra(WIDGET_EVENT_ID))
            val record = widgetLifecycleInbox(context).pending().single { it.event.id == id }
            check(record.settingsApplied && record.event.receiver == receiver)
            setResultCode(Activity.RESULT_OK)
            setResultData(id)
            super.onReceive(context, widgetLifecycleIntent(context, record.event))
            return
        }
        val event = widgetLifecycleEvent(context, receiver, intent)
        retainWidgetLifecycleEvent(context, event)
    }
}

internal fun retainWidgetLifecycleEvent(
    context: Context,
    event: WidgetLifecycleEvent,
): Boolean =
    retainWidgetLifecycleCustody(
        persist = { widgetLifecycleInbox(context).append(event) },
        schedule = { includePayload -> scheduleWidgetLifecycle(context, if (includePayload) event else null) },
        persistedFallbackSupported = Build.VERSION.SDK_INT >= 34,
        onFailure = { Napier.w("Widget lifecycle delivery could not be retained", it) },
    )

internal const val WIDGET_EVENT_ID = "widgetEventId"
internal const val WIDGET_INGRESS_PROCESS = ":widget_ingress"

internal fun widgetLifecycleInbox(context: Context) = WidgetLifecycleInbox(File(context.noBackupFilesDir, "widget-lifecycle"))

internal fun widgetRuntimeAction(
    context: Context,
    receiver: String,
) = "${context.packageName}.widget.runtime.$receiver"

internal fun widgetReceivers(): List<DurableWidgetReceiver> =
    listOf(FixedMemoryWidgetReceiver(), OnThisDayWidgetReceiver(), NewEntryWidgetReceiver(), CaptureWidgetReceiver())

internal fun registeredWidgetIds(
    context: Context,
    receiver: String,
): Set<Int> {
    val provider = widgetReceivers().single { it.javaClass.simpleName == receiver }.javaClass
    return AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, provider)).toSet()
}

internal fun widgetLifecycleEvent(
    context: Context,
    receiver: String,
    intent: Intent,
): WidgetLifecycleEvent {
    require(widgetReceivers().any { it.javaClass.simpleName == receiver })
    val action = requireNotNull(intent.action)
    val ids =
        when (action) {
            AppWidgetManager.ACTION_APPWIDGET_UPDATE, AppWidgetManager.ACTION_APPWIDGET_RESTORED -> {
                requireNotNull(intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)).toList()
            }

            AppWidgetManager.ACTION_APPWIDGET_DELETED, AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED -> {
                listOf(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
            }

            "ACTION_TRIGGER_LAMBDA" -> {
                listOf(intent.getIntExtra("EXTRA_APPWIDGET_ID", -1))
            }

            AppWidgetManager.ACTION_APPWIDGET_ENABLED, AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            Intent.ACTION_LOCALE_CHANGED, GlanceAppWidgetReceiver.ACTION_DEBUG_UPDATE,
            -> {
                emptyList()
            }

            else -> {
                error("Unsupported widget lifecycle action")
            }
        }
    require(ids.size <= 4096 && ids.all { it > 0 } && ids.distinct().size == ids.size)
    val oldIds =
        if (action == AppWidgetManager.ACTION_APPWIDGET_RESTORED) {
            requireNotNull(intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS)).toList().also {
                require(it.size == ids.size && it.all { id -> id > 0 } && it.distinct().size == it.size)
            }
        } else {
            emptyList()
        }
    val actionKey = if (action == "ACTION_TRIGGER_LAMBDA") requireNotNull(intent.getStringExtra("EXTRA_ACTION_KEY")) else null
    require(actionKey == null || actionKey.length <= 4096)
    val sourceGeneration =
        if (action == AppWidgetManager.ACTION_APPWIDGET_RESTORED) {
            PostRestoreDetector(context).retainedPreferenceSessionId()
        } else {
            null
        }
    return WidgetLifecycleEvent(
        Uuid.random().toString(),
        receiver,
        action,
        ids,
        oldIds,
        actionKey = actionKey,
        sourceResolved = action != AppWidgetManager.ACTION_APPWIDGET_RESTORED || sourceGeneration == null,
        sourceGeneration = sourceGeneration,
    )
}

internal fun widgetLifecycleIntent(
    context: Context,
    event: WidgetLifecycleEvent,
): Intent {
    val live = registeredWidgetIds(context, event.receiver)
    val ids =
        when (event.action) {
            AppWidgetManager.ACTION_APPWIDGET_DELETED -> event.ids.filter { it !in live }
            Intent.ACTION_LOCALE_CHANGED, GlanceAppWidgetReceiver.ACTION_DEBUG_UPDATE, WIDGET_REFRESH_ACTION -> live.toList()
            else -> event.ids.filter { it in live }
        }
    return Intent(if (event.action == WIDGET_REFRESH_ACTION) AppWidgetManager.ACTION_APPWIDGET_UPDATE else event.action).apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids.toIntArray())
        if (event.oldIds.isNotEmpty()) {
            putExtra(
                AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS,
                event.oldIds
                    .zip(event.ids)
                    .filter { it.second in ids }
                    .map { it.first }
                    .toIntArray(),
            )
        }
        ids.singleOrNull()?.let { id ->
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            putExtra("EXTRA_APPWIDGET_ID", id)
            if (event.action == AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED) {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, AppWidgetManager.getInstance(context).getAppWidgetOptions(id))
            }
        }
        event.actionKey?.let { putExtra("EXTRA_ACTION_KEY", it) }
        if (event.action == AppWidgetManager.ACTION_APPWIDGET_DELETED && ids.isEmpty()) action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
    }
}

internal fun applyPendingWidgetSettings(
    context: Context,
    repair: WidgetRestoreRepair,
    liveIds: Set<Int>,
) {
    val inbox = widgetLifecycleInbox(context)
    inbox.pending().filterNot { it.settingsApplied }.forEach { record ->
        val event =
            if (record.event.sourceResolved) {
                record.event
            } else {
                val values =
                    NativeRestoredPreferenceFiles(
                        context,
                    ).read("logdate_widget_instances", record.event.sourceGeneration)?.let(::parseWidgetSourcePreferences).orEmpty()
                inbox.resolveSource(record.event.id, values)
                record.event.copy(sourceValues = values, sourceResolved = true)
            }
        when (event.action) {
            AppWidgetManager.ACTION_APPWIDGET_RESTORED -> {
                repair.remap(event.oldIds.toIntArray(), event.ids.toIntArray(), liveIds, sourceValues = event.sourceValues)
            }

            AppWidgetManager.ACTION_APPWIDGET_DELETED -> {
                event.ids.filter { it !in liveIds }.forEach(repair::remove)
            }
        }
        inbox.settingsApplied(event.id)
    }
}

/** Reuses pending installation-local work after process death, reboot, and application upgrades. */
fun resumeWidgetLifecycle(context: Context) {
    if (widgetLifecycleInbox(context).pending().isNotEmpty()) scheduleWidgetLifecycle(context)
}

/** Startup replay retains application ownership without blocking the caller on durable inbox IO. */
fun resumeWidgetLifecycle(
    context: Context,
    scope: CoroutineScope,
): Job =
    scope.launch(Dispatchers.IO) {
        runCatching { resumeWidgetLifecycle(context) }.onFailure { Napier.w("Widget lifecycle recovery remains pending", it) }
    }

fun isWidgetIngressProcess(packageName: String): Boolean = Application.getProcessName() == packageName + WIDGET_INGRESS_PROCESS
