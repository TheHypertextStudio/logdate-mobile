package app.logdate.server.atproto

import app.logdate.server.identity.AtprotoIdentityService
import io.ktor.server.application.Application
import org.koin.ktor.ext.inject

/**
 * Lets `com.atproto.repo.describeRepo` list the collections a public repository holds, read from
 * the LogDate record store. Without it the identity service answers every repository with no
 * collections.
 */
internal fun Application.installRepoCollections() {
    val identity by inject<AtprotoIdentityService>()
    val repoStore by inject<LogDateRepoStore>()
    identity.setRepoCollectionsResolver(repoStore::collectionsForDid)
}
