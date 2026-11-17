@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.content.audience

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.logdate.ui.profiles.PersonUiState

@Composable
fun MiniProfileIcons(people: List<PersonUiState>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
        verticalAlignment = Alignment.Top,
    ) {
        val representativePeople = people.take(3)
        representativePeople.forEach {
            MiniProfileIcon()
        }
    }
}

@Composable
fun MiniProfileIcon() {
    Canvas(
        modifier =
            Modifier
                .border(width = 2.dp, color = MaterialTheme.colorScheme.onSurface)
                .padding(2.dp)
                .size(16.dp)
                .background(color = MaterialTheme.colorScheme.primaryContainer)
                .clip(CircleShape),
    ) {
        // TODO: Render profile image
    }
}
