package app.logdate.client.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import app.logdate.client.device.restore.NativeRestoredPreferenceFiles
import app.logdate.client.domain.recommendation.RecallMode
import app.logdate.client.domain.recommendation.WidgetContentType
import io.github.aakira.napier.Napier

/** Launcher widget IDs are independent, so choices must be stored per placed widget. */
class WidgetInstanceSettings(
    context: Context,
) {
    private val application = context.applicationContext
    private val preferences = application.getSharedPreferences("logdate_widget_instances", Context.MODE_PRIVATE)
    private val preferenceStore = AndroidWidgetPreferencesStore(preferences)
    private val repair =
        WidgetRestoreRepair(
            AndroidWidgetRestoreStateStore(application.noBackupFilesDir) {
                WidgetRestoreState(
                    preferenceStore
                        .read()
                        .filterKeys { it in liveWidgetIds() }
                        .mapValues { RestoredWidgetChoices(values = it.value) },
                )
            },
            preferenceStore,
        )
    var isRecoveryPending: Boolean = false
        private set

    init {
        synchronized(restoreLock) {
            try {
                NativeRestoredPreferenceFiles(application).read("logdate_widget_instances")
                applyPendingWidgetSettings(application, repair, liveWidgetIds())
                repair.reconcile(liveWidgetIds())
            } catch (_: Exception) {
                isRecoveryPending = true
                Napier.w("Widget choice recovery remains pending")
            }
        }
    }

    private fun liveWidgetIds(): Set<Int> {
        val manager = AppWidgetManager.getInstance(application)
        return listOf(FixedMemoryWidgetReceiver::class.java, OnThisDayWidgetReceiver::class.java, NewEntryWidgetReceiver::class.java)
            .flatMap { manager.getAppWidgetIds(ComponentName(application, it)).toList() }
            .toSet()
    }

    fun recallMode(
        id: Int,
        fallback: RecallMode,
    ): RecallMode = preferences.getString("$id.recallMode", null)?.let { runCatching { RecallMode.valueOf(it) }.getOrNull() } ?: fallback

    fun contentTypes(
        id: Int,
        fallback: Set<WidgetContentType>,
    ): Set<WidgetContentType> =
        preferences
            .getString("$id.contentTypes", null)
            ?.split(',')
            ?.mapNotNull { runCatching { WidgetContentType.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?.takeIf(Set<WidgetContentType>::isNotEmpty) ?: fallback

    fun chosenNoteId(id: Int): String? = preferences.getString("$id.chosenNoteId", null)

    fun usePhotoPrompt(id: Int): Boolean = preferences.getBoolean("$id.usePhotoPrompt", true)

    // AndroidTest WidgetInstanceSettingsTest checks whether a choice was stored; tests are not roots
    @Suppress("unused")
    fun hasPhotoPromptChoice(id: Int): Boolean = preferences.contains("$id.usePhotoPrompt")

    fun photoShape(id: Int): PhotoWidgetShape =
        preferences
            .getString("$id.photoShape", null)
            ?.let { runCatching { PhotoWidgetShape.valueOf(it) }.getOrNull() }
            ?: PhotoWidgetShape.SYSTEM

    fun ensureRecallDefaults(
        id: Int,
        mode: RecallMode,
        types: Set<WidgetContentType>,
    ) {
        synchronized(restoreLock) {
            NativeRestoredPreferenceFiles(application).read("logdate_widget_instances")
            applyPendingWidgetSettings(application, repair, liveWidgetIds())
            if (preferences.contains("$id.recallMode")) return
            repair.update(id, explicitlyChosen = false) { values ->
                values.copy(
                    strings =
                        values.strings + mapOf("recallMode" to mode.name, "contentTypes" to types.joinToString(",") { it.name }),
                )
            }
        }
    }

    // App/android-main androidTest widget restore tests seed per-widget choices with it; tests are not roots
    @Suppress("unused")
    fun saveRecall(
        id: Int,
        mode: RecallMode,
        types: Set<WidgetContentType>,
    ): Boolean =
        update(id) { values ->
            values.copy(strings = values.strings + mapOf("recallMode" to mode.name, "contentTypes" to types.joinToString(",") { it.name }))
        }

    // App/android-main androidTest widget restore tests seed per-widget choices with it; tests are not roots
    @Suppress("unused")
    fun saveChosenNote(
        id: Int,
        noteId: String,
    ): Boolean = update(id) { it.copy(strings = it.strings + ("chosenNoteId" to noteId)) }

    // App/android-main androidTest widget restore tests seed per-widget choices with it; tests are not roots
    @Suppress("unused")
    fun saveUsePhotoPrompt(
        id: Int,
        enabled: Boolean,
    ): Boolean = update(id) { it.copy(flags = it.flags + ("usePhotoPrompt" to enabled)) }

    // App/android-main androidTest widget restore tests seed per-widget choices with it; tests are not roots
    @Suppress("unused")
    fun savePhotoShape(
        id: Int,
        shape: PhotoWidgetShape,
    ): Boolean = update(id) { it.copy(strings = it.strings + ("photoShape" to shape.name)) }

    // App/android-main androidTest widget restore tests seed per-widget choices with it; tests are not roots
    @Suppress("unused")
    fun savePhotoPrompt(
        id: Int,
        enabled: Boolean,
        shape: PhotoWidgetShape,
    ): Boolean =
        update(id) { values ->
            values.copy(strings = values.strings + ("photoShape" to shape.name), flags = values.flags + ("usePhotoPrompt" to enabled))
        }

    internal fun saveSetup(
        request: WidgetSetupRequest,
        canCommit: () -> Boolean,
    ): Boolean =
        update(request.widgetId, canCommit) { existing ->
            existing.copy(strings = existing.strings + request.values.strings, flags = existing.flags + request.values.flags)
        }

    fun remap(
        oldId: Int,
        newId: Int,
    ) = remap(intArrayOf(oldId), intArrayOf(newId))

    fun remap(
        oldIds: IntArray,
        newIds: IntArray,
    ) {
        synchronized(restoreLock) { repair.remap(oldIds, newIds, liveWidgetIds()) }
    }

    fun remove(id: Int) {
        synchronized(restoreLock) { repair.remove(id) }
    }

    private fun update(
        id: Int,
        canCommit: () -> Boolean = { true },
        change: (WidgetSettingValues) -> WidgetSettingValues,
    ): Boolean =
        synchronized(restoreLock) {
            try {
                NativeRestoredPreferenceFiles(application).read("logdate_widget_instances")
                applyPendingWidgetSettings(application, repair, liveWidgetIds())
                if (!repair.update(id, canCommit = canCommit, change = change)) return@synchronized false
                isRecoveryPending = false
                true
            } catch (_: Exception) {
                isRecoveryPending = true
                Napier.w("Widget selection has not been saved yet")
                false
            }
        }

    private companion object {
        val restoreLock = Any()
    }
}
