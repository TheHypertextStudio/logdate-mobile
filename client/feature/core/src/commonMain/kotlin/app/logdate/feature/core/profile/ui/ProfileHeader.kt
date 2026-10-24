@file:Suppress("ktlint:standard:function-naming")

package app.logdate.feature.core.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.ui.theme.Spacing
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.display_name
import logdate.client.feature.core.generated.resources.edit_display_name
import logdate.client.feature.core.generated.resources.no_display_name
import logdate.client.feature.core.generated.resources.profile
import logdate.client.feature.core.generated.resources.profile_photo
import logdate.client.feature.core.generated.resources.username_handle
import logdate.client.ui.generated.resources.common_cancel
import logdate.client.ui.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource
import logdate.client.ui.generated.resources.Res as UiRes

@Composable
internal fun ProfileHeader(
    profile: ProfileDisplayModel,
    editState: ProfileEditState,
    onStartEditingDisplayName: () -> Unit,
    onCancelEditing: () -> Unit,
    onSaveDisplayName: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Profile photo placeholder
        Box(
            modifier =
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = stringResource(Res.string.profile_photo),
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        // Display name with inline editing
        when (editState) {
            is ProfileEditState.DisplayName -> {
                var editedName by rememberSaveable(editState.currentValue) { mutableStateOf(editState.currentValue) }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text(stringResource(Res.string.display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        IconButton(onClick = onCancelEditing) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(UiRes.string.common_cancel))
                        }
                        IconButton(onClick = { onSaveDisplayName(editedName) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(UiRes.string.common_save))
                        }
                    }
                }
            }

            else -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text =
                            if (profile.displayName.isEmpty() && !profile.isAuthenticated) {
                                stringResource(Res.string.no_display_name)
                            } else {
                                profile.displayName
                            },
                        style =
                            MaterialTheme.typography.headlineLarge.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        color =
                            if (profile.displayName.isEmpty() && !profile.isAuthenticated) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    IconButton(
                        onClick = onStartEditingDisplayName,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(Res.string.edit_display_name),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        // Username (only show if user has cloud account)
        profile.username?.let { username ->
            Text(
                text = stringResource(Res.string.username_handle, username),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
