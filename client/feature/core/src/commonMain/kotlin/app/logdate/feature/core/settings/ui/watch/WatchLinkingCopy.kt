package app.logdate.feature.core.settings.ui.watch

import androidx.compose.runtime.Composable
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.watch_linking_another_account_description
import logdate.client.feature.core.generated.resources.watch_linking_another_account_title
import logdate.client.feature.core.generated.resources.watch_linking_description
import logdate.client.feature.core.generated.resources.watch_linking_device_limit_description
import logdate.client.feature.core.generated.resources.watch_linking_device_limit_title
import logdate.client.feature.core.generated.resources.watch_linking_removed_description
import logdate.client.feature.core.generated.resources.watch_linking_removed_title
import logdate.client.feature.core.generated.resources.watch_linking_retrying_description
import logdate.client.feature.core.generated.resources.watch_linking_retrying_title
import logdate.client.feature.core.generated.resources.watch_linking_set_up_again_action
import logdate.client.feature.core.generated.resources.watch_linking_set_up_again_description
import logdate.client.feature.core.generated.resources.watch_linking_set_up_again_title
import logdate.client.feature.core.generated.resources.watch_linking_title
import logdate.client.feature.core.generated.resources.watch_linking_try_again
import org.jetbrains.compose.resources.stringResource

/**
 * What the Watch page says while LogDate links a watch by itself.
 *
 * @property action The label of the one button, or null while linking is under way and there is nothing to do.
 */
internal data class WatchLinkingCopy(
    val title: String,
    val description: String,
    val action: String?,
)

@Composable
internal fun watchLinkingCopy(step: WatchSetupStep.Linking): WatchLinkingCopy {
    val name = step.watchName
    val tryAgain = stringResource(Res.string.watch_linking_try_again)
    return when (step.problem) {
        null -> {
            WatchLinkingCopy(
                stringResource(Res.string.watch_linking_title, name),
                stringResource(Res.string.watch_linking_description),
                null,
            )
        }

        WatchLinkProblem.RETRYING -> {
            WatchLinkingCopy(
                stringResource(Res.string.watch_linking_retrying_title, name),
                stringResource(Res.string.watch_linking_retrying_description),
                tryAgain,
            )
        }

        WatchLinkProblem.ANOTHER_ACCOUNT -> {
            WatchLinkingCopy(
                stringResource(Res.string.watch_linking_another_account_title, name),
                stringResource(Res.string.watch_linking_another_account_description),
                tryAgain,
            )
        }

        WatchLinkProblem.DEVICE_LIMIT -> {
            WatchLinkingCopy(
                stringResource(Res.string.watch_linking_device_limit_title),
                stringResource(Res.string.watch_linking_device_limit_description),
                tryAgain,
            )
        }

        WatchLinkProblem.SET_UP_AGAIN -> {
            WatchLinkingCopy(
                stringResource(Res.string.watch_linking_set_up_again_title, name),
                stringResource(Res.string.watch_linking_set_up_again_description),
                tryAgain,
            )
        }

        WatchLinkProblem.REMOVED -> {
            WatchLinkingCopy(
                stringResource(Res.string.watch_linking_removed_title, name),
                stringResource(Res.string.watch_linking_removed_description),
                stringResource(Res.string.watch_linking_set_up_again_action),
            )
        }
    }
}
