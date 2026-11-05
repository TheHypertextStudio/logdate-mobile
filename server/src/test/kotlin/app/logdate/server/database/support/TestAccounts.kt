package app.logdate.server.database.support

import app.logdate.server.auth.Account
import app.logdate.server.database.PostgreSQLAccountRepository
import app.logdate.server.database.toKotlinUuid
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi

/** A real owner fixture for metadata repositories whose writes are account-fenced. */
@OptIn(ExperimentalUuidApi::class)
internal fun createTestAccount(
    accountId: UUID,
    did: String? = null,
): Account =
    runBlocking {
        val id = accountId.toKotlinUuid()
        val account = Account(id, id.toHexString(), "Test owner", did = did, createdAt = Clock.System.now())
        val repository = PostgreSQLAccountRepository()
        check(repository.create(account)) { "Test account could not be created" }
        requireNotNull(repository.findById(id))
    }
