package app.logdate.client

import android.app.HandoffActivityData
import android.app.HandoffActivityParams
import android.content.ComponentName
import android.os.Build
import android.os.PersistableBundle
import androidx.annotation.RequiresApi
import androidx.lifecycle.lifecycleScope
import app.logdate.client.datastore.featureflags.FeatureFlag
import app.logdate.client.datastore.featureflags.FeatureFlagStore
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** The launch extra naming the draft that an editor handed off from another device continues. */
internal const val EXTRA_HANDOFF_DRAFT_ID = "app.logdate.extra.HANDOFF_DRAFT_ID"

/** The draft id an editor was launched to continue, when Android Handoff started it. */
internal fun handoffDraftId(extra: String?): Uuid? = extra?.let { runCatching { Uuid.parse(it) }.getOrNull() }

/**
 * Offers this editor window to the person's other Android devices while [FeatureFlag.EDITOR_HANDOFF]
 * is on (Android 17 and later). Without the app installed there, nothing is offered: the web
 * fallback would need logdate.app to open drafts.
 */
internal fun EditorActivity.followEditorHandoffFlag(flags: FeatureFlagStore) {
    if (Build.VERSION.SDK_INT < 37) return
    val params = HandoffActivityParams.Builder().setAllowHandoffWithoutPackageInstalled(false).build()
    lifecycleScope.launch {
        flags.observe(FeatureFlag.EDITOR_HANDOFF).distinctUntilChanged().collect { enabled ->
            setHandoffEnabled(enabled, params)
        }
    }
}

/**
 * The data Android passes to the other device to open LogDate's editor there: on [draftId], or on a
 * new entry when nothing is written yet.
 */
@RequiresApi(37)
internal fun EditorActivity.editorHandoffData(draftId: Uuid?): HandoffActivityData {
    val extras = PersistableBundle()
    if (draftId != null) extras.putString(EXTRA_HANDOFF_DRAFT_ID, draftId.toString())
    return HandoffActivityData
        .Builder(ComponentName(this, EditorActivity::class.java))
        .setExtras(extras)
        .build()
}
