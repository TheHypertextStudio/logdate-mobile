package app.logdate.server.auth

import app.logdate.server.crypto.sha256Hex
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

interface RefreshTokenRevocationRepository {
    suspend fun revoke(refreshToken: String): Boolean

    suspend fun isRevoked(refreshToken: String): Boolean
}

class InMemoryRefreshTokenRevocationRepository : RefreshTokenRevocationRepository {
    private val revokedTokenHashes = ConcurrentHashMap<String, Instant>()

    override suspend fun revoke(refreshToken: String): Boolean {
        if (refreshToken.isBlank()) return false
        revokedTokenHashes[sha256Hex(refreshToken)] = Clock.System.now()
        return true
    }

    override suspend fun isRevoked(refreshToken: String): Boolean =
        refreshToken.isNotBlank() && revokedTokenHashes.containsKey(sha256Hex(refreshToken))
}
