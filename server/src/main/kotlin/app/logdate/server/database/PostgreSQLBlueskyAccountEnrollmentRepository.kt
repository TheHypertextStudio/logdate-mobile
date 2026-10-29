package app.logdate.server.database

import app.logdate.server.auth.Account
import app.logdate.server.auth.requireValidNewIdentity
import app.logdate.server.bluesky.BlueskyAccountEnrollmentRepository
import app.logdate.server.passkeys.WebAuthnPasskeyService
import app.logdate.shared.model.AccountIdentityKind
import java.sql.Connection
import java.sql.SQLException
import java.util.Base64
import javax.sql.DataSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** A failed passkey or identity insert rolls back the account in the same transaction. */
@OptIn(ExperimentalUuidApi::class)
internal class PostgreSQLBlueskyAccountEnrollmentRepository(
    private val source: DataSource,
) : BlueskyAccountEnrollmentRepository {
    override suspend fun create(
        account: Account,
        passkey: WebAuthnPasskeyService.VerifiedRegistration,
    ): Boolean {
        account.requireValidNewIdentity()
        require(account.identityKind == AccountIdentityKind.EXTERNAL && account.isActive && account.moderation == null)
        require(passkey.credentialId == passkey.passkey.credentialId && passkey.passkey.isActive && passkey.publicKey.isNotEmpty())
        return try {
            source.blueskyTransaction {
                if (!insertAccount(account)) return@blueskyTransaction false
                insertPasskey(account, passkey)
                insertIdentity(account, passkey.credentialId)
                true
            }
        } catch (failure: SQLException) {
            if (failure.sqlState == "23505") false else throw failure
        }
    }

    private fun Connection.insertAccount(account: Account): Boolean =
        prepareStatement(
            "INSERT INTO accounts(id, username, display_name, did, handle, bio, original_bio, created_at, " +
                "last_sign_in_at, storage_mode, identity_kind, email, email_verified, preferences) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use {
            it.setObject(1, account.id.toJavaUUID())
            it.setString(2, account.username)
            it.setString(3, account.displayName)
            it.setString(4, account.did)
            it.setString(5, account.handle)
            it.setString(6, account.bio)
            it.setString(7, account.originalBio)
            it.setTimestamp(8, account.createdAt.timestamp())
            it.setTimestamp(9, account.lastSignInAt?.timestamp())
            it.setString(10, account.storageMode.wireValue)
            it.setString(11, account.identityKind.wireValue)
            it.setString(12, account.email)
            it.setBoolean(13, account.emailVerified)
            it.setString(14, account.preferences ?: "{}")
            it.executeUpdate() == 1
        }

    private fun Connection.insertPasskey(
        account: Account,
        verified: WebAuthnPasskeyService.VerifiedRegistration,
    ) {
        val info = verified.passkey
        prepareStatement(
            "INSERT INTO passkeys(id, account_id, credential_id, public_key, sign_count, nickname, " +
                "device_type, created_at, last_used_at, is_active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setObject(1, info.id.toJavaUUID())
            it.setObject(2, account.id.toJavaUUID())
            it.setString(3, verified.credentialId)
            it.setString(4, Base64.getUrlEncoder().withoutPadding().encodeToString(verified.publicKey))
            it.setLong(5, verified.signCount)
            it.setString(6, info.nickname)
            it.setString(7, info.deviceType)
            it.setTimestamp(8, info.createdAt.timestamp())
            it.setTimestamp(9, info.lastUsedAt?.timestamp())
            it.setBoolean(10, info.isActive)
            it.executeUpdate()
        }
    }

    private fun Connection.insertIdentity(
        account: Account,
        credentialId: String,
    ) {
        prepareStatement(
            "INSERT INTO account_identities(id, account_id, provider, provider_subject, created_at, last_sign_in_at) " +
                "VALUES (?, ?, 'PASSKEY', ?, ?, ?)",
        ).use {
            it.setObject(1, Uuid.random().toJavaUUID())
            it.setObject(2, account.id.toJavaUUID())
            it.setString(3, credentialId)
            it.setTimestamp(4, account.createdAt.timestamp())
            it.setTimestamp(5, account.lastSignInAt?.timestamp())
            it.executeUpdate()
        }
    }
}
