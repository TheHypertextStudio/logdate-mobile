package app.logdate.server.database

import app.logdate.server.auth.RefreshTokenRevocationRepository
import app.logdate.server.crypto.sha256Hex
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Clock

object RevokedRefreshTokensTable : Table("revoked_refresh_tokens") {
    val tokenHash = varchar("token_hash", 64)
    val revokedAt = timestamp("revoked_at")

    override val primaryKey = PrimaryKey(tokenHash)
}

class PostgreSQLRefreshTokenRevocationRepository : RefreshTokenRevocationRepository {
    override suspend fun revoke(refreshToken: String): Boolean {
        if (refreshToken.isBlank()) return false
        val tokenHash = sha256Hex(refreshToken)
        dbQuery {
            RevokedRefreshTokensTable.insertIgnore {
                it[RevokedRefreshTokensTable.tokenHash] = tokenHash
                it[revokedAt] = Clock.System.now()
            }
        }
        return true
    }

    override suspend fun isRevoked(refreshToken: String): Boolean {
        if (refreshToken.isBlank()) return false
        val tokenHash = sha256Hex(refreshToken)
        return dbQuery {
            RevokedRefreshTokensTable
                .selectAll()
                .where { RevokedRefreshTokensTable.tokenHash eq tokenHash }
                .any()
        }
    }
}
