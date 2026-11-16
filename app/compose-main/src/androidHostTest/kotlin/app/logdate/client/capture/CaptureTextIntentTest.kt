package app.logdate.client.capture

import android.content.Intent
import app.logdate.client.resolveMainActivityNavKey
import app.logdate.feature.editor.navigation.EditorLaunchMode
import app.logdate.feature.editor.navigation.EntryEditorRoute
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CaptureTextIntentTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `another app cannot start an entry with its own text`() {
        val fromAnotherApp =
            Intent(ACTION_CAPTURE)
                .setClassName(context, "app.logdate.client.MainActivity")
                .putExtra(EXTRA_CAPTURE_MODE, "text")
                .putExtra(EXTRA_CAPTURE_TEXT, "Written by an assistant")
        assertEquals(EntryEditorRoute(launchMode = EditorLaunchMode.Text), resolveMainActivityNavKey(fromAnotherApp))
    }

    @Test
    fun `an implicit capture intent starts empty too`() {
        val implicit = Intent(ACTION_CAPTURE).putExtra(EXTRA_CAPTURE_TEXT, "Written by an assistant")
        assertEquals(EntryEditorRoute(), resolveMainActivityNavKey(implicit))
    }

    @Test
    fun `LogDate's own route keeps the text the person wrote`() {
        assertEquals(
            EntryEditorRoute(launchMode = EditorLaunchMode.Text, initialText = "Dinner on the steps"),
            resolveMainActivityNavKey(context.internalCaptureIntent("Dinner on the steps")),
        )
    }
}
