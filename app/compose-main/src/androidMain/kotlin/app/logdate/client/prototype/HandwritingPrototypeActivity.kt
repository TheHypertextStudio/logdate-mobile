package app.logdate.client.prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import app.logdate.feature.editor.ui.handwriting.EntryPageScene
import app.logdate.feature.editor.ui.handwriting.StoriesEntryPrototype
import app.logdate.ui.theme.LogDateTheme

/** Registered only by the Android debug manifest; never a production journal entry point. */
class HandwritingPrototypeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("entry_stories", false)) {
            setContent { LogDateTheme { StoriesEntryPrototype(onExit = ::finish) } }
        } else if (intent.getBooleanExtra("entry_start", false)) {
            setContent { LogDateTheme { EntryPageScene(onExit = ::finish) } }
        } else if (intent.getBooleanExtra("real_editor", false)) {
            setContent {
                LogDateTheme {
                    HandwritingRealEditorContent(
                        resumeDraft = intent.getBooleanExtra("resume_draft", false),
                        resumeSaved = intent.getBooleanExtra("resume_saved", false),
                        onExit = ::finish,
                    )
                }
            }
        } else {
            val state = ViewModelProvider(this)[HandwritingPrototypeViewModel::class.java]
            setContent { LogDateTheme { HandwritingPrototypeContent(state, onExit = ::finish) } }
        }
    }
}
