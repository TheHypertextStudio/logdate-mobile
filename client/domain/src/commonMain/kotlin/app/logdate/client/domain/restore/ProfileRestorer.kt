package app.logdate.client.domain.restore

import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.shared.model.profile.LogDateProfile
import kotlin.time.Instant

/** Applies an archive's profile choices without replacing populated local choices during Cloud recovery. */
internal class ProfileRestorer(
    private val profileRepository: ProfileRepository,
) {
    suspend fun restore(
        profile: LogDateProfile?,
        options: RestoreOptions,
        onProgress: (suspend (RestoreProgressPhase) -> Unit)?,
        scope: app.logdate.client.repository.journals.JournalMergeScope?,
        recoveryId: String,
    ) {
        if (profile == null) return
        onProgress?.invoke(RestoreProgressPhase.RESTORING_PROFILE)
        val existing = profileRepository.getCurrentProfile()
        val replacement =
            when {
                options.preservePopulatedLocalProfile -> {
                    existing.copy(
                        displayName = existing.displayName.takeIf { it.isNotBlank() } ?: profile.displayName,
                        birthday = existing.birthday ?: profile.birthday,
                        profilePhotoUri = existing.profilePhotoUri.takeUnless { it.isNullOrBlank() } ?: profile.profilePhotoUri,
                        bio = existing.bio.takeUnless { it.isNullOrBlank() } ?: profile.bio,
                        originalBio = existing.originalBio.takeUnless { it.isNullOrBlank() } ?: profile.originalBio,
                        createdAt = if (existing.createdAt == Instant.DISTANT_PAST) profile.createdAt else existing.createdAt,
                        lastUpdatedAt = maxOf(existing.lastUpdatedAt, profile.lastUpdatedAt),
                    )
                }

                shouldOverwrite(existing.lastUpdatedAt, profile.lastUpdatedAt, options.strategy) -> {
                    profile
                }

                existing == LogDateProfile() -> {
                    profile
                }

                else -> {
                    existing
                }
            }
        profileRepository.compareAndSetProfileForRestore(existing, replacement, scope, recoveryId)
    }
}

internal fun shouldOverwrite(
    existing: Instant?,
    incoming: Instant,
    strategy: RestoreStrategy,
): Boolean =
    when (strategy) {
        RestoreStrategy.MERGE_KEEP_NEWEST -> existing == null || incoming > existing
        RestoreStrategy.REPLACE_EXISTING -> true
    }
