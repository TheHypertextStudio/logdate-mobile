@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.settings.account.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.logdate.ui.platform.PlatformSheet
import app.logdate.ui.theme.Spacing
import io.github.aakira.napier.Napier
import kotlinx.coroutines.launch
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.recovery_phrase_close_help
import logdate.client.feature.core.generated.resources.recovery_phrase_help_fallback
import logdate.client.feature.core.generated.resources.recovery_phrase_help_title
import logdate.client.feature.core.generated.resources.recovery_phrase_link_failed
import logdate.client.feature.core.generated.resources.recovery_phrase_policy
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryPhraseInfoSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val textAlignment = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    var closing by remember { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }

    fun dismiss() {
        if (closing) return
        closing = true
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }
    PlatformSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.recovery_phrase_help_title),
                    style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                    textAlign = textAlignment,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = ::dismiss, enabled = !closing) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(Res.string.recovery_phrase_close_help))
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                RecoveryRestoreDiagram()
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Icon(
                        Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        stringResource(Res.string.recovery_phrase_help_fallback),
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                        textAlign = textAlignment,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                TextButton(
                    onClick = {
                        linkFailed = false
                        try {
                            uriHandler.openUri("https://logdate.app/privacy")
                            dismiss()
                        } catch (error: Exception) {
                            Napier.w("Could not open recovery information", error)
                            linkFailed = true
                        }
                    },
                    enabled = !closing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.recovery_phrase_policy), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                if (linkFailed) {
                    Text(
                        stringResource(Res.string.recovery_phrase_link_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
