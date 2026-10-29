package app.logdate.feature.core.account.di

import app.logdate.client.domain.account.BackfillLocalDataUseCase
import app.logdate.client.domain.account.TriggerInitialSyncUseCase
import app.logdate.client.repository.account.AccountCreationRequest
import app.logdate.client.repository.profile.ProfileRepository
import app.logdate.feature.core.account.BlueskySignInViewModel
import app.logdate.feature.core.account.InitialSyncStatus
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val blueskySignInFeatureModule =
    module {
        viewModel {
            val profiles = get<ProfileRepository>()
            val backfill = get<BackfillLocalDataUseCase>()
            val sync = get<TriggerInitialSyncUseCase>()
            BlueskySignInViewModel(
                get(),
                get(),
                get(),
                get(),
                initialProfile = {
                    val profile = profiles.getCurrentProfile()
                    AccountCreationRequest("", profile.displayName, profile.bio, profile.bio)
                },
                finishAccount = { account ->
                    backfill(account.id.toString())
                    when (sync()) {
                        TriggerInitialSyncUseCase.Result.Success -> InitialSyncStatus.Success
                        is TriggerInitialSyncUseCase.Result.Partial -> InitialSyncStatus.Partial
                        TriggerInitialSyncUseCase.Result.TimedOut -> InitialSyncStatus.TimedOut
                        is TriggerInitialSyncUseCase.Result.Error -> InitialSyncStatus.Failed
                    }
                },
            )
        }
    }
