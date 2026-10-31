package app.logdate.server.database

import app.logdate.server.oauth.OAuthRuntimeStateRepository
import app.logdate.server.oauth.StoredAuthorizationCode
import app.logdate.server.oauth.StoredAuthorizationRequest
import app.logdate.server.oauth.StoredRefreshToken
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

internal class PostgreSQLOAuthRuntimeStateRepository : OAuthRuntimeStateRepository {
    // The database clock, as for revocations, so every instance agrees on what has expired.
    override suspend fun purgeExpired(now: kotlin.time.Instant): Int =
        transaction {
            OAuthAuthorizationRequestsTable.deleteWhere { OAuthAuthorizationRequestsTable.expiresAt less CurrentTimestamp } +
                OAuthAuthorizationCodesTable.deleteWhere { OAuthAuthorizationCodesTable.expiresAt less CurrentTimestamp } +
                OAuthRefreshTokensTable.deleteWhere { OAuthRefreshTokensTable.expiresAt less CurrentTimestamp }
        }

    override suspend fun saveAuthorizationRequest(request: StoredAuthorizationRequest): StoredAuthorizationRequest =
        transaction {
            val existing =
                OAuthAuthorizationRequestsTable
                    .selectAll()
                    .where { OAuthAuthorizationRequestsTable.requestUri eq request.requestUri }
                    .singleOrNull()

            if (existing == null) {
                OAuthAuthorizationRequestsTable.insert {
                    it[requestUri] = request.requestUri
                    it[clientId] = request.clientId
                    it[clientName] = request.clientName
                    it[redirectUri] = request.redirectUri
                    it[scope] = request.scope
                    it[state] = request.state
                    it[loginHint] = request.loginHint
                    it[codeChallenge] = request.codeChallenge
                    it[dpopKeyThumbprint] = request.dpopKeyThumbprint
                    it[clientAuthKeyId] = request.clientAuthKeyId
                    it[clientAuthKeyThumbprint] = request.clientAuthKeyThumbprint
                    it[expiresAt] = request.expiresAt
                }
            } else {
                OAuthAuthorizationRequestsTable.update({ OAuthAuthorizationRequestsTable.requestUri eq request.requestUri }) {
                    it[clientId] = request.clientId
                    it[clientName] = request.clientName
                    it[redirectUri] = request.redirectUri
                    it[scope] = request.scope
                    it[state] = request.state
                    it[loginHint] = request.loginHint
                    it[codeChallenge] = request.codeChallenge
                    it[dpopKeyThumbprint] = request.dpopKeyThumbprint
                    it[clientAuthKeyId] = request.clientAuthKeyId
                    it[clientAuthKeyThumbprint] = request.clientAuthKeyThumbprint
                    it[expiresAt] = request.expiresAt
                }
            }
            request
        }

    override suspend fun findAuthorizationRequest(requestUri: String): StoredAuthorizationRequest? =
        transaction {
            OAuthAuthorizationRequestsTable
                .selectAll()
                .where { OAuthAuthorizationRequestsTable.requestUri eq requestUri }
                .singleOrNull()
                ?.toAuthorizationRequestState()
        }

    override suspend fun deleteAuthorizationRequest(requestUri: String): Boolean =
        transaction {
            OAuthAuthorizationRequestsTable.deleteWhere { OAuthAuthorizationRequestsTable.requestUri eq requestUri } > 0
        }

    override suspend fun saveAuthorizationCode(code: StoredAuthorizationCode): StoredAuthorizationCode =
        transaction {
            val existing =
                OAuthAuthorizationCodesTable
                    .selectAll()
                    .where { OAuthAuthorizationCodesTable.code eq code.code }
                    .singleOrNull()

            if (existing == null) {
                OAuthAuthorizationCodesTable.insert {
                    it[OAuthAuthorizationCodesTable.code] = code.code
                    it[clientId] = code.clientId
                    it[redirectUri] = code.redirectUri
                    it[subjectDid] = code.subjectDid
                    it[subjectHandle] = code.subjectHandle
                    it[scope] = code.scope
                    it[codeChallenge] = code.codeChallenge
                    it[dpopKeyThumbprint] = code.dpopKeyThumbprint
                    it[clientAuthKeyId] = code.clientAuthKeyId
                    it[clientAuthKeyThumbprint] = code.clientAuthKeyThumbprint
                    it[expiresAt] = code.expiresAt
                    // The database clock, so every instance stamps approvals on one timeline.
                    it[grantedAt] = CurrentTimestamp
                }
            } else {
                OAuthAuthorizationCodesTable.update({ OAuthAuthorizationCodesTable.code eq code.code }) {
                    it[clientId] = code.clientId
                    it[redirectUri] = code.redirectUri
                    it[subjectDid] = code.subjectDid
                    it[subjectHandle] = code.subjectHandle
                    it[scope] = code.scope
                    it[codeChallenge] = code.codeChallenge
                    it[dpopKeyThumbprint] = code.dpopKeyThumbprint
                    it[clientAuthKeyId] = code.clientAuthKeyId
                    it[clientAuthKeyThumbprint] = code.clientAuthKeyThumbprint
                    it[expiresAt] = code.expiresAt
                    // The database clock, so every instance stamps approvals on one timeline.
                    it[grantedAt] = CurrentTimestamp
                }
            }
            code
        }

    override suspend fun takeAuthorizationCode(code: String): StoredAuthorizationCode? =
        transaction {
            // The row lock makes a concurrent exchange wait, then find the code gone.
            val existing =
                OAuthAuthorizationCodesTable
                    .selectAll()
                    .where { OAuthAuthorizationCodesTable.code eq code }
                    .forUpdate()
                    .singleOrNull()
                    ?.toAuthorizationCodeState()
                    ?: return@transaction null
            val removed = OAuthAuthorizationCodesTable.deleteWhere { OAuthAuthorizationCodesTable.code eq code }
            existing.takeIf { removed == 1 }
        }

    override suspend fun saveRefreshToken(token: StoredRefreshToken): StoredRefreshToken =
        transaction {
            val existing =
                OAuthRefreshTokensTable
                    .selectAll()
                    .where { OAuthRefreshTokensTable.token eq token.token }
                    .singleOrNull()

            if (existing == null) {
                OAuthRefreshTokensTable.insert {
                    it[OAuthRefreshTokensTable.token] = token.token
                    it[clientId] = token.clientId
                    it[subjectDid] = token.subjectDid
                    it[scope] = token.scope
                    it[dpopKeyThumbprint] = token.dpopKeyThumbprint
                    it[clientAuthKeyId] = token.clientAuthKeyId
                    it[clientAuthKeyThumbprint] = token.clientAuthKeyThumbprint
                    it[expiresAt] = token.expiresAt
                    it[revokedAt] = token.revokedAt
                    it[grantedAt] = token.grantedAt
                }
            } else {
                OAuthRefreshTokensTable.update({ OAuthRefreshTokensTable.token eq token.token }) {
                    it[clientId] = token.clientId
                    it[subjectDid] = token.subjectDid
                    it[scope] = token.scope
                    it[dpopKeyThumbprint] = token.dpopKeyThumbprint
                    it[clientAuthKeyId] = token.clientAuthKeyId
                    it[clientAuthKeyThumbprint] = token.clientAuthKeyThumbprint
                    it[expiresAt] = token.expiresAt
                    it[revokedAt] = token.revokedAt
                    it[grantedAt] = token.grantedAt
                }
            }
            token
        }

    override suspend fun saveGrantedRefreshToken(token: StoredRefreshToken): Boolean =
        transaction {
            if (revokedSince(token.subjectDid, token.grantedAt)) return@transaction false
            insertRefreshToken(token)
            true
        }

    override suspend fun rotateRefreshToken(
        oldToken: String,
        replacement: StoredRefreshToken,
        now: kotlin.time.Instant,
    ): Boolean =
        transaction {
            // The row lock serializes refreshes of one token: the second waits, then finds it gone.
            val old =
                OAuthRefreshTokensTable
                    .selectAll()
                    .where { OAuthRefreshTokensTable.token eq oldToken }
                    .forUpdate()
                    .singleOrNull()
                    ?.toRefreshTokenState()
                    ?: return@transaction false
            if (old.revokedAt != null || old.expiresAt <= now) return@transaction false
            if (revokedSince(old.subjectDid, old.grantedAt)) return@transaction false
            val removed =
                OAuthRefreshTokensTable.deleteWhere {
                    (OAuthRefreshTokensTable.token eq oldToken) and OAuthRefreshTokensTable.revokedAt.isNull()
                }
            if (removed != 1) return@transaction false
            insertRefreshToken(replacement)
            true
        }

    override suspend fun findRefreshToken(token: String): StoredRefreshToken? =
        transaction {
            OAuthRefreshTokensTable
                .selectAll()
                .where { OAuthRefreshTokensTable.token eq token }
                .singleOrNull()
                ?.toRefreshTokenState()
        }

    override suspend fun revokeRefreshToken(
        token: String,
        revokedAt: kotlin.time.Instant,
    ): Boolean =
        transaction {
            OAuthRefreshTokensTable.update({ OAuthRefreshTokensTable.token eq token }) {
                it[OAuthRefreshTokensTable.revokedAt] = revokedAt
            } > 0
        }

    override suspend fun deleteRefreshToken(token: String): Boolean =
        transaction {
            OAuthRefreshTokensTable.deleteWhere { OAuthRefreshTokensTable.token eq token } > 0
        }

    override suspend fun revokeRefreshTokensForSubject(
        subjectDid: String,
        revokedAt: kotlin.time.Instant,
    ): Int =
        transaction {
            val previous =
                OAuthSubjectRevocationsTable
                    .selectAll()
                    .where { OAuthSubjectRevocationsTable.subjectDid eq subjectDid }
                    .forUpdate()
                    .singleOrNull()
                    ?.get(OAuthSubjectRevocationsTable.revokedAt)
            // Revocation times come from the database clock, the same clock that stamps approvals, so
            // clock differences between server instances cannot let a grant outlive its revocation.
            if (previous == null) {
                OAuthSubjectRevocationsTable.insertIgnore {
                    it[OAuthSubjectRevocationsTable.subjectDid] = subjectDid
                    it[OAuthSubjectRevocationsTable.revokedAt] = CurrentTimestamp
                }
            }
            OAuthSubjectRevocationsTable.update({
                (OAuthSubjectRevocationsTable.subjectDid eq subjectDid) and (OAuthSubjectRevocationsTable.revokedAt less CurrentTimestamp)
            }) {
                it[OAuthSubjectRevocationsTable.revokedAt] = CurrentTimestamp
            }
            OAuthAuthorizationCodesTable.deleteWhere { OAuthAuthorizationCodesTable.subjectDid eq subjectDid }
            OAuthRefreshTokensTable.update({
                (OAuthRefreshTokensTable.subjectDid eq subjectDid) and OAuthRefreshTokensTable.revokedAt.isNull()
            }) {
                it[OAuthRefreshTokensTable.revokedAt] = CurrentTimestamp
            }
        }

    private fun revokedSince(
        subjectDid: String,
        grantedAt: kotlin.time.Instant,
    ): Boolean {
        val revokedAt =
            OAuthSubjectRevocationsTable
                .selectAll()
                .where { OAuthSubjectRevocationsTable.subjectDid eq subjectDid }
                .singleOrNull()
                ?.get(OAuthSubjectRevocationsTable.revokedAt)
                ?: return false
        return revokedAt >= grantedAt
    }

    private fun insertRefreshToken(token: StoredRefreshToken) {
        OAuthRefreshTokensTable.insert {
            it[OAuthRefreshTokensTable.token] = token.token
            it[clientId] = token.clientId
            it[subjectDid] = token.subjectDid
            it[scope] = token.scope
            it[dpopKeyThumbprint] = token.dpopKeyThumbprint
            it[clientAuthKeyId] = token.clientAuthKeyId
            it[clientAuthKeyThumbprint] = token.clientAuthKeyThumbprint
            it[expiresAt] = token.expiresAt
            it[revokedAt] = token.revokedAt
            it[grantedAt] = token.grantedAt
        }
    }
}

private fun ResultRow.toAuthorizationRequestState(): StoredAuthorizationRequest =
    StoredAuthorizationRequest(
        requestUri = this[OAuthAuthorizationRequestsTable.requestUri],
        clientId = this[OAuthAuthorizationRequestsTable.clientId],
        clientName = this[OAuthAuthorizationRequestsTable.clientName],
        redirectUri = this[OAuthAuthorizationRequestsTable.redirectUri],
        scope = this[OAuthAuthorizationRequestsTable.scope],
        state = this[OAuthAuthorizationRequestsTable.state],
        loginHint = this[OAuthAuthorizationRequestsTable.loginHint],
        codeChallenge = this[OAuthAuthorizationRequestsTable.codeChallenge],
        dpopKeyThumbprint = this[OAuthAuthorizationRequestsTable.dpopKeyThumbprint],
        clientAuthKeyId = this[OAuthAuthorizationRequestsTable.clientAuthKeyId],
        clientAuthKeyThumbprint = this[OAuthAuthorizationRequestsTable.clientAuthKeyThumbprint],
        expiresAt = this[OAuthAuthorizationRequestsTable.expiresAt],
    )

private fun ResultRow.toAuthorizationCodeState(): StoredAuthorizationCode =
    StoredAuthorizationCode(
        code = this[OAuthAuthorizationCodesTable.code],
        clientId = this[OAuthAuthorizationCodesTable.clientId],
        redirectUri = this[OAuthAuthorizationCodesTable.redirectUri],
        subjectDid = this[OAuthAuthorizationCodesTable.subjectDid],
        subjectHandle = this[OAuthAuthorizationCodesTable.subjectHandle],
        scope = this[OAuthAuthorizationCodesTable.scope],
        codeChallenge = this[OAuthAuthorizationCodesTable.codeChallenge],
        dpopKeyThumbprint = this[OAuthAuthorizationCodesTable.dpopKeyThumbprint],
        clientAuthKeyId = this[OAuthAuthorizationCodesTable.clientAuthKeyId],
        clientAuthKeyThumbprint = this[OAuthAuthorizationCodesTable.clientAuthKeyThumbprint],
        expiresAt = this[OAuthAuthorizationCodesTable.expiresAt],
        grantedAt = this[OAuthAuthorizationCodesTable.grantedAt],
    )

private fun ResultRow.toRefreshTokenState(): StoredRefreshToken =
    StoredRefreshToken(
        token = this[OAuthRefreshTokensTable.token],
        clientId = this[OAuthRefreshTokensTable.clientId],
        subjectDid = this[OAuthRefreshTokensTable.subjectDid],
        scope = this[OAuthRefreshTokensTable.scope],
        dpopKeyThumbprint = this[OAuthRefreshTokensTable.dpopKeyThumbprint],
        clientAuthKeyId = this[OAuthRefreshTokensTable.clientAuthKeyId],
        clientAuthKeyThumbprint = this[OAuthRefreshTokensTable.clientAuthKeyThumbprint],
        expiresAt = this[OAuthRefreshTokensTable.expiresAt],
        revokedAt = this[OAuthRefreshTokensTable.revokedAt],
        grantedAt = this[OAuthRefreshTokensTable.grantedAt],
    )
