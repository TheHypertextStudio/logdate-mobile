@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.tooling.preview.Preview
import app.logdate.ui.content.audience.MiniProfileIcons
import app.logdate.ui.profiles.PersonUiState
import app.logdate.ui.theme.Spacing
import kotlin.uuid.Uuid

@Composable
fun JournalMetadataBlock(
    title: String,
    audience: List<PersonUiState>,
    coverUri: String? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImageContentCover(imageUri = coverUri)
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(title)
            JournalMetadata(audience)
        }
    }
}

@Composable
private fun JournalMetadata(audience: List<PersonUiState>) {
    Row {
        MiniProfileIcons(people = audience)
    }
}

@Preview
@Composable
private fun JournalMetadataBlockPreview() {
    JournalMetadataBlock(
        title = "Climbing Buddies",
        audience =
            listOf(
                PersonUiState(
                    uid = Uuid.random(),
                    name = "Margaret Belford",
                    photoUri = null,
                ),
                PersonUiState(
                    uid = Uuid.random(),
                    name = "Willie Chalmers III",
                    photoUri = null,
                ),
            ),
    )
}
