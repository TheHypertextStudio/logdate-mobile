@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.assistants

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.compose.viewmodel.koinViewModel

/** Settings › Privacy & security › Android assistants. */
@Composable
fun AndroidAssistantsScreen(
    onBack: () -> Unit,
    viewModel: AndroidAssistantsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val loaded = state ?: return
    AndroidAssistantsContent(loaded.journals, loaded.access, viewModel::setAccess, onBack)
}
