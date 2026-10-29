package app.logdate.feature.core.settings.account.signin

import app.logdate.client.datastore.SessionStorage
import app.logdate.client.device.auth.AtprotoConnectionBinding
import app.logdate.shared.config.LogDateConfigRepository
import kotlinx.coroutines.flow.combine
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal val atprotoConnectionFeatureModule =
    module {
        viewModel {
            val sessions = get<SessionStorage>()
            val config = get<LogDateConfigRepository>()
            val changes =
                combine(sessions.getSessionFlow(), config.backendUrl) { _, _ ->
                    sessions.getOriginBoundSession()?.let { owned ->
                        runCatching {
                            AtprotoConnectionBinding.fromGrant(
                                owned.origin,
                                owned.session.accountId,
                                owned.session.refreshToken,
                            )
                        }.getOrNull()
                    }
                }
            AtprotoConnectionViewModel(get(), changes)
        }
    }
