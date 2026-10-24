package app.logdate.client.feature.widgets

import android.app.ActivityOptions
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import app.logdate.feature.core.settings.ui.HomeWidgetKind
import app.logdate.feature.core.settings.ui.MemoriesWidgetInstallController
import app.logdate.feature.core.settings.ui.MemoriesWidgetInstallUiState
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Requests launcher pinning for each LogDate widget type.
 */
class AndroidMemoriesWidgetInstallController(
    private val context: Context,
) : MemoriesWidgetInstallController {
    private val appContext = context.applicationContext
    private val _uiState = MutableStateFlow(resolveUiState())

    override val uiState: StateFlow<MemoriesWidgetInstallUiState> = _uiState.asStateFlow()

    override suspend fun requestAddToHomeScreen() = requestAddToHomeScreen(HomeWidgetKind.RECALL)

    override suspend fun requestAddToHomeScreen(kind: HomeWidgetKind) {
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        if (!appWidgetManager.isRequestPinAppWidgetSupported) {
            _uiState.value = MemoriesWidgetInstallUiState.Unsupported
            return
        }

        val successIntent =
            if (kind == HomeWidgetKind.NEW_ENTRY) null else MemoriesWidgetPinSuccessCallback.createPendingIntent(appContext, kind)
        val manager = GlanceAppWidgetManager(appContext)
        val accepted =
            when (kind) {
                HomeWidgetKind.RECALL -> {
                    manager.requestPinGlanceAppWidget(
                        receiver = OnThisDayWidgetReceiver::class.java,
                        preview = OnThisDayWidget(),
                        previewSize = DpSize(250.dp, 180.dp),
                        successCallback = successIntent,
                    )
                }

                HomeWidgetKind.FIXED_MEMORY -> {
                    manager.requestPinGlanceAppWidget(
                        receiver = FixedMemoryWidgetReceiver::class.java,
                        preview = FixedMemoryWidget(),
                        previewSize = DpSize(250.dp, 180.dp),
                        successCallback = successIntent,
                    )
                }

                HomeWidgetKind.NEW_ENTRY -> {
                    manager.requestPinGlanceAppWidget(
                        receiver = NewEntryWidgetReceiver::class.java,
                        preview = NewEntryWidget(),
                        previewSize = DpSize(250.dp, 180.dp),
                        successCallback = successIntent,
                    )
                }
            }

        if (!accepted) {
            Napier.w("Launcher rejected the Memories widget pin request")
            _uiState.value = MemoriesWidgetInstallUiState.Unsupported
            return
        }

        _uiState.value = MemoriesWidgetInstallUiState.Available
    }

    private fun resolveUiState(): MemoriesWidgetInstallUiState {
        val appWidgetManager = AppWidgetManager.getInstance(appContext)
        return if (appWidgetManager.isRequestPinAppWidgetSupported) {
            MemoriesWidgetInstallUiState.Available
        } else {
            MemoriesWidgetInstallUiState.Unsupported
        }
    }
}

internal object MemoriesWidgetPinSuccessCallback {
    private const val REQUEST_CODE = 7001

    fun createPendingIntent(
        context: Context,
        kind: HomeWidgetKind,
    ): PendingIntent {
        val configActivity =
            when (kind) {
                HomeWidgetKind.RECALL -> OnThisDayWidgetConfigActivity::class.java
                HomeWidgetKind.FIXED_MEMORY -> FixedMemoryWidgetConfigActivity::class.java
                HomeWidgetKind.NEW_ENTRY -> NewEntryWidgetConfigActivity::class.java
            }
        val intent = Intent(context, configActivity)
        val options =
            if (Build.VERSION.SDK_INT >= 35) {
                ActivityOptions
                    .makeBasic()
                    .apply {
                        pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }.toBundle()
            } else {
                null
            }
        return PendingIntent.getActivity(
            context,
            REQUEST_CODE + kind.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            options,
        )
    }
}
