@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.database

import app.logdate.server.bluesky.BLUESKY_SIGN_IN_CHALLENGE_TYPE
import app.logdate.server.bluesky.connection.ATPROTO_DISCONNECT_CHALLENGE_TYPE
import app.logdate.server.passkeys.OAUTH_AUTHORIZATION_CHALLENGE_TYPE
import app.logdate.server.passkeys.PasskeyChallengeRepository
import app.logdate.shared.model.PasskeyChallenge
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class PostgreSQLPasskeyChallengeRepository : PasskeyChallengeRepository {
    override suspend fun save(
        scope: String,
        challenge: PasskeyChallenge,
    ) {
        val ceremony = requireNotNull(storedType(scope, challenge.type))
        transaction {
            WebAuthnChallengesTable.deleteWhere { expiresAt lessEq Clock.System.now().databasePrecision() }
            WebAuthnChallengesTable.insert {
                it[WebAuthnChallengesTable.challenge] = "$scope:${challenge.challenge}"
                it[userId] = challenge.userId.toJavaUUID()
                it[type] = ceremony
                it[expiresAt] = Instant.parse(challenge.expiresAt).databasePrecision()
                it[isUsed] = challenge.isUsed
            }
        }
    }

    override suspend fun consume(
        scope: String,
        challenge: String,
        expectedType: String,
        expectedUserId: Uuid?,
        now: Instant,
    ): PasskeyChallenge? {
        val ceremony = storedType(scope, expectedType) ?: return null
        val key = "$scope:$challenge"
        return transaction {
            var predicate =
                (WebAuthnChallengesTable.challenge eq key) and
                    (WebAuthnChallengesTable.type eq ceremony) and
                    (WebAuthnChallengesTable.isUsed eq false) and
                    (WebAuthnChallengesTable.expiresAt greater now.databasePrecision())
            if (expectedUserId != null) {
                predicate = predicate and (WebAuthnChallengesTable.userId eq expectedUserId.toJavaUUID())
            }
            val consumed = WebAuthnChallengesTable.update({ predicate }) { it[isUsed] = true }
            if (consumed != 1) return@transaction null
            val row = WebAuthnChallengesTable.selectAll().where { WebAuthnChallengesTable.challenge eq key }.single()
            PasskeyChallenge(
                challenge = challenge,
                userId = row[WebAuthnChallengesTable.userId].toKotlinUuid(),
                type = expectedType,
                expiresAt = row[WebAuthnChallengesTable.expiresAt].toString(),
                isUsed = true,
            )
        }
    }

    private fun storedType(
        scope: String,
        type: String,
    ): String? {
        if (scope == "passkey" &&
            type in
            setOf(
                OAUTH_AUTHORIZATION_CHALLENGE_TYPE,
                BLUESKY_SIGN_IN_CHALLENGE_TYPE,
                ATPROTO_DISCONNECT_CHALLENGE_TYPE,
            )
        ) {
            return type
        }
        val ceremony = if (scope == "restore") type.removePrefix("restore-") else type
        if (scope == "passkey" && type in setOf("step_up", "step_up_grant")) return type
        return ceremony.takeIf {
            scope in setOf("passkey", "restore") &&
                (scope != "restore" || type.startsWith("restore-")) &&
                it in setOf("registration", "authentication")
        }
    }

    private fun Instant.databasePrecision(): Instant = Instant.fromEpochSeconds(epochSeconds, nanosecondsOfSecond / 1_000 * 1_000)
}
