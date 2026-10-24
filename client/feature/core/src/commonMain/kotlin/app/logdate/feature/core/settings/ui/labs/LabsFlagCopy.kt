package app.logdate.feature.core.settings.ui.labs

import app.logdate.client.datastore.featureflags.FeatureFlag
import logdate.client.feature.core.generated.resources.Res
import logdate.client.feature.core.generated.resources.labs_activity_awareness_description
import logdate.client.feature.core.generated.resources.labs_activity_awareness_title
import logdate.client.feature.core.generated.resources.labs_identity_recovery_key_description
import logdate.client.feature.core.generated.resources.labs_identity_recovery_key_title
import logdate.client.feature.core.generated.resources.labs_watch_experience_description
import logdate.client.feature.core.generated.resources.labs_watch_experience_title
import org.jetbrains.compose.resources.StringResource

/** The title and description Labs shows for a flag. */
internal data class LabsFlagCopy(
    val title: StringResource,
    val description: StringResource,
)

/**
 * The Labs wording for this flag, or `null` for a flag Labs does not offer.
 *
 * Labs offers only the flags listed here. Every other flag, including the ones other features keep
 * off while they are built, stays out of Labs until someone adds wording for it.
 */
internal fun FeatureFlag.labsCopy(): LabsFlagCopy? =
    when (this) {
        FeatureFlag.WATCH_EXPERIENCE_V2 -> {
            LabsFlagCopy(
                title = Res.string.labs_watch_experience_title,
                description = Res.string.labs_watch_experience_description,
            )
        }

        FeatureFlag.ACTIVITY_AWARENESS -> {
            LabsFlagCopy(
                title = Res.string.labs_activity_awareness_title,
                description = Res.string.labs_activity_awareness_description,
            )
        }

        FeatureFlag.IDENTITY_RECOVERY_KEY -> {
            LabsFlagCopy(
                title = Res.string.labs_identity_recovery_key_title,
                description = Res.string.labs_identity_recovery_key_description,
            )
        }

        else -> {
            null
        }
    }
