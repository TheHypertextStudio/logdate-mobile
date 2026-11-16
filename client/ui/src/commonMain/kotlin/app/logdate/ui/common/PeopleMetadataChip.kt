@file:Suppress("ktlint:standard:function-naming")

package app.logdate.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import logdate.client.ui.generated.resources.Res
import logdate.client.ui.generated.resources.people_chip_just_you
import logdate.client.ui.generated.resources.people_chip_names_and_others
import logdate.client.ui.generated.resources.people_chip_two_names
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PeopleMetadataChip(people: List<String>) {
    // Label logic:
    // If there is only one other person, display that name.
    // If there are two people, display both names separated by "and".
    // If there are more than two people, display the first two names followed by a count of the remaining people.
    // e.g. If there are four people (Lane, Anna, Alice, and Jake), the text should be "Lane, Anna, and 2 others".
    val label =
        when (people.size) {
            0 -> stringResource(Res.string.people_chip_just_you)
            1 -> people.first()
            2 -> stringResource(Res.string.people_chip_two_names, people.first(), people.last())
            else -> stringResource(Res.string.people_chip_names_and_others, people.take(2).joinToString(), people.size - 2)
        }
    MetadataChip(
        label = label,
        icon = {
            Icon(
                Icons.Rounded.Group,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}
