@file:Suppress("ktlint:standard:function-naming")
@file:OptIn(ExperimentalFoundationApi::class)

package app.logdate.feature.search.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.logdate.client.repository.search.SearchContentType
import app.logdate.client.repository.search.SearchResult
import app.logdate.client.repository.search.SearchResultDetail
import app.logdate.ui.common.focusableWithRing
import app.logdate.ui.search.UniversalSearchResultUiState
import app.logdate.ui.theme.Spacing
import app.logdate.util.toReadableDateShort
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logdate.client.feature.search.generated.resources.Res
import logdate.client.feature.search.generated.resources.search_entry_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One search result as a list row: a journal's spine-shaped cover, a person's monogram, or a round
 * tile with the kind of result, then the text and one supporting line.
 */
@Composable
internal fun SearchResultListItem(
    state: UniversalSearchResultUiState,
    result: SearchResult,
    detail: SearchResultDetail?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val typeLabel = stringResource(state.typeLabelKey)
    val supporting =
        when (detail) {
            is SearchResultDetail.Journal -> pluralStringResource(Res.plurals.search_entry_count, detail.entryCount, detail.entryCount)
            is SearchResultDetail.Person -> detail.relationshipLabel ?: typeLabel
            else ->
                listOf(
                    result.created
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                        .date
                        .toReadableDateShort(),
                    typeLabel,
                ).joinToString(" · ")
        }
    ListItem(
        headlineContent = {
            Text(
                text = state.contentText,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { Leading(state, result, detail) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier =
            Modifier
                .padding(
                    vertical = Spacing.xs,
                ).focusableWithRing()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun Leading(
    state: UniversalSearchResultUiState,
    result: SearchResult,
    detail: SearchResultDetail?,
) {
    val label = stringResource(state.typeLabelKey)
    when (result.contentType) {
        SearchContentType.JOURNAL ->
            Cover(
                result.contentType,
                (detail as? SearchResultDetail.Journal)?.coverUri,
                state.typeIcon,
                label,
                size = 53.dp,
            )
        SearchContentType.PERSON ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text =
                            state.contentText.text
                                .firstOrNull()
                                ?.uppercase()
                                .orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        else ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) { Icon(imageVector = state.typeIcon, contentDescription = label) }
            }
    }
}
