package app.logdate.wear.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Text
import app.logdate.wear.R
import app.logdate.wear.launch.WearStartupState
import app.logdate.wear.v2.theme.LogDateWearTheme

@Composable
internal fun WearStartupScreen(
    state: WearStartupState,
    onRetry: () -> Unit,
) {
    LogDateWearTheme {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            if (state is WearStartupState.Failed) {
                Text(stringResource(R.string.wear_startup_failed), textAlign = TextAlign.Center)
                Button(onClick = onRetry) { Text(stringResource(R.string.wear_startup_retry)) }
            } else {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }
    }
}
