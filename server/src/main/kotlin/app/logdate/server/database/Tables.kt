package app.logdate.server.database

import app.logdate.shared.model.AccountIdentityKind
import app.logdate.shared.model.AccountStorageMode
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.uuid.ExperimentalUuidApi

/**
 * Database table definitions using Jetbrains Exposed ORM.
 * Using regular Table with explicit UUID columns for better type control.
 */

@OptIn(ExperimentalUuidApi::class)
object AccountsTable : Table("accounts") {
    val id = javaUUID("id").autoGenerate()
    val username = varchar("username", 50).uniqueIndex()
    val displayName = varchar("display_name", 100)
    val did = varchar("did", 2048).nullable().uniqueIndex()
    val handle = varchar("handle", 255).nullable().uniqueIndex()
    val signingKeyPublic = text("signing_key_public").nullable()
    val plcRecoveryDidKey = text("plc_recovery_did_key").nullable()
    val email = varchar("email", 255).nullable()
    val emailVerified = bool("email_verified").default(false)
    val emailVerifiedAt = timestamp("email_verified_at").nullable()
    val bio = text("bio").nullable()
    val originalBio = text("original_bio").nullable()
    val createdAt = timestamp("created_at")
    val lastSignInAt = timestamp("last_sign_in_at").nullable()
    val isActive = bool("is_active").default(true)
    val preferences = text("preferences").default("{}")

    /** The [app.logdate.shared.model.AccountStorageMode.wireValue] of how the account's data is kept. */
    val storageMode = varchar("storage_mode", 32).default(AccountStorageMode.LEGACY.wireValue)

    val identityKind = varchar("identity_kind", 32).default(AccountIdentityKind.HOSTED.wireValue)

    /** The [app.logdate.server.auth.AccountModeration.wireValue] when operators suspended or took down the account; null otherwise. */
    val moderation = varchar("moderation", 16).nullable()

    /** The exact DID the owner made public; null until the informed publication flow completes. */
    val publicIdentityDid = varchar("public_identity_did", 255).nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * Short-lived (~5 min) one-row-per-attempt binding of a server-issued nonce to
 * an account during the Android Digital Credentials email-verification flow.
 * Rows are burned by [app.logdate.server.auth.EmailVerificationService.complete]
 * regardless of outcome to prevent nonce replay.
 */
@OptIn(ExperimentalUuidApi::class)
object PendingEmailVerificationsTable : Table("pending_email_verifications") {
    val transactionId = javaUUID("transaction_id")
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val nonce = varchar("nonce", 128)
    val expiresAt = timestamp("expires_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(transactionId)

    init {
        index("idx_pev_account", false, accountId)
        index("idx_pev_expires", false, expiresAt)
    }
}

@OptIn(ExperimentalUuidApi::class)
object AtprotoPasswordCredentialsTable : Table("atproto_password_credentials") {
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val salt = text("salt")
    val hash = text("hash")
    val iterations = integer("iterations")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(accountId)
}

@OptIn(ExperimentalUuidApi::class)
object AtprotoSessionsTable : Table("atproto_sessions") {
    val id = varchar("id", 64)
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val createdAt = timestamp("created_at")
    val refreshExpiresAt = timestamp("refresh_expires_at")
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_atproto_sessions_account", false, accountId)
    }
}

object OAuthAuthorizationRequestsTable : Table("oauth_authorization_requests") {
    val requestUri = varchar("request_uri", 255)
    val clientId = text("client_id")
    val clientName = varchar("client_name", 255)
    val redirectUri = text("redirect_uri")
    val scope = text("scope")
    val state = text("state").nullable()
    val loginHint = text("login_hint").nullable()
    val codeChallenge = text("code_challenge")
    val dpopKeyThumbprint = varchar("dpop_key_thumbprint", 255)
    val clientAuthKeyId = text("client_auth_key_id").nullable()
    val clientAuthKeyThumbprint = varchar("client_auth_key_thumbprint", 255).nullable()
    val expiresAt = timestamp("expires_at")

    override val primaryKey = PrimaryKey(requestUri)
}

object OAuthAuthorizationCodesTable : Table("oauth_authorization_codes") {
    val code = varchar("code", 255)
    val clientId = text("client_id")
    val redirectUri = text("redirect_uri")
    val subjectDid = varchar("subject_did", 255)
    val subjectHandle = varchar("subject_handle", 255)
    val scope = text("scope")
    val codeChallenge = text("code_challenge")
    val dpopKeyThumbprint = varchar("dpop_key_thumbprint", 255)
    val clientAuthKeyId = text("client_auth_key_id").nullable()
    val clientAuthKeyThumbprint = varchar("client_auth_key_thumbprint", 255).nullable()
    val expiresAt = timestamp("expires_at")
    val grantedAt = timestamp("granted_at")

    override val primaryKey = PrimaryKey(code)
}

object OAuthSigningKeysTable : Table("oauth_signing_keys") {
    val slot = varchar("slot", 32)
    val keyId = varchar("key_id", 255)
    val privateKeyEncrypted = text("private_key_encrypted")
    val publicKeySpki = text("public_key_spki")
    val createdAt = timestamp("created_at")
    val retireAfter = timestamp("retire_after").nullable()

    override val primaryKey = PrimaryKey(slot)
}

/** Single-use OAuth values (DPoP proof IDs, PKCE challenges), by SHA-256, until they could no longer be accepted. */
object OAuthUsedValuesTable : Table("oauth_used_values") {
    val valueHash = varchar("value_hash", 64)
    val expiresAt = timestamp("expires_at")

    override val primaryKey = PrimaryKey(valueHash)

    init {
        index("idx_oauth_used_values_expires_at", false, expiresAt)
    }
}

/** When each account last had all of its OAuth grants revoked; grants approved at or before it are dead. */
object OAuthSubjectRevocationsTable : Table("oauth_subject_revocations") {
    val subjectDid = varchar("subject_did", 255)
    val revokedAt = timestamp("revoked_at")

    override val primaryKey = PrimaryKey(subjectDid)
}

object OAuthRefreshTokensTable : Table("oauth_refresh_tokens") {
    val token = varchar("token", 255)
    val clientId = text("client_id")
    val subjectDid = varchar("subject_did", 255)
    val scope = text("scope")
    val dpopKeyThumbprint = varchar("dpop_key_thumbprint", 255)
    val clientAuthKeyId = text("client_auth_key_id").nullable()
    val clientAuthKeyThumbprint = varchar("client_auth_key_thumbprint", 255).nullable()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val grantedAt = timestamp("granted_at")

    override val primaryKey = PrimaryKey(token)

    init {
        index("idx_oauth_refresh_tokens_subject", false, subjectDid)
    }
}

@OptIn(ExperimentalUuidApi::class)
object SigningKeysTable : Table("signing_keys") {
    val id = javaUUID("id").autoGenerate()
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val purpose = varchar("purpose", 32).default("atproto")
    val algorithm = varchar("algorithm", 32).default("K-256")
    val publicKeyMultibase = text("public_key_multibase")
    val privateKeyEncrypted = text("private_key_encrypted")
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

@OptIn(ExperimentalUuidApi::class)
object HostedPlcOperationsTable : Table("hosted_plc_operations") {
    val id = javaUUID("id").autoGenerate()
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val did = varchar("did", 255)
    val cid = varchar("cid", 255).nullable()
    val prevCid = varchar("prev_cid", 255).nullable()
    val operationType = varchar("operation_type", 32)
    val operationJson = text("operation_json")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_hosted_plc_operations_account_created", false, accountId, createdAt)
        index("idx_hosted_plc_operations_did_created", false, did, createdAt)
        uniqueIndex("uq_hosted_plc_operations_did_prev", did, prevCid)
    }
}

@OptIn(ExperimentalUuidApi::class)
object AccountIdentitiesTable : Table("account_identities") {
    val id = javaUUID("id").autoGenerate()
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val provider = varchar("provider", 32)
    val providerSubject = varchar("provider_subject", 2048)
    val email = varchar("email", 255).nullable()
    val emailVerified = bool("email_verified").default(false)
    val createdAt = timestamp("created_at")
    val lastSignInAt = timestamp("last_sign_in_at").nullable()
    val metadataJson = text("metadata_json").default("{}")

    override val primaryKey = PrimaryKey(id)
}

@OptIn(ExperimentalUuidApi::class)
object AccountLinkEventsTable : Table("account_link_events") {
    val id = javaUUID("id").autoGenerate()
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val provider = varchar("provider", 32)
    val providerSubject = varchar("provider_subject", 255)
    val reason = varchar("reason", 64)
    val ipHash = varchar("ip_hash", 128).nullable()
    val userAgentHash = varchar("user_agent_hash", 128).nullable()
    val createdAt = timestamp("created_at")
    val metadataJson = text("metadata_json").default("{}")

    override val primaryKey = PrimaryKey(id)
}

@OptIn(ExperimentalUuidApi::class)
object PasskeysTable : Table("passkeys") {
    val id = javaUUID("id").autoGenerate()
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val credentialId = text("credential_id").uniqueIndex()
    val publicKey = text("public_key")
    val signCount = long("sign_count").default(0)
    val nickname = varchar("nickname", 100).nullable()
    val deviceType = varchar("device_type", 50).default("platform")
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val isActive = bool("is_active").default(true)
    val webauthnData = text("webauthn_data").default("{}")

    override val primaryKey = PrimaryKey(id)
}

@OptIn(ExperimentalUuidApi::class)
object RestoreCredentialsTable : Table("restore_credentials") {
    val id = javaUUID("id").autoGenerate()
    val accountId = javaUUID("account_id").references(AccountsTable.id)
    val credentialId = text("credential_id").uniqueIndex()
    val publicKey = text("public_key")
    val signCount = long("sign_count").default(0)
    val isActive = bool("is_active").default(true)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object SessionsTable : Table("sessions") {
    val id = varchar("id", 64)
    val temporaryUserId = javaUUID("temporary_user_id")
    val challenge = text("challenge")
    val sessionType = varchar("session_type", 20)
    val username = varchar("username", 50).nullable()
    val displayName = varchar("display_name", 100).nullable()
    val bio = text("bio").nullable()
    val originalBio = text("original_bio").nullable()
    val deviceInfo = text("device_info").nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val isUsed = bool("is_used").default(false)

    override val primaryKey = PrimaryKey(id)
}

object AtprotoRepoHeadsTable : Table("atproto_repo_heads") {
    val repoDid = varchar("repo_did", 255)
    val rootCid = varchar("root_cid", 255)
    val commitCid = varchar("commit_cid", 255)
    val revision = long("revision")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(repoDid)
}

object AtprotoRepoBlocksTable : Table("atproto_repo_blocks") {
    val cid = varchar("cid", 255)
    val bytes = binary("bytes")

    override val primaryKey = PrimaryKey(cid)
}

object AtprotoRepoBlockLinksTable : Table("atproto_repo_block_links") {
    val repoDid = varchar("repo_did", 255)
    val cid = varchar("cid", 255).references(AtprotoRepoBlocksTable.cid)

    override val primaryKey = PrimaryKey(repoDid, cid)

    init {
        index("idx_atproto_repo_block_links_repo", false, repoDid)
    }
}

object AtprotoRepoCommitsTable : Table("atproto_repo_commits") {
    val repoDid = varchar("repo_did", 255)
    val revision = long("revision")
    val cid = varchar("cid", 255)
    val rootCid = varchar("root_cid", 255)
    val prevCid = varchar("prev_cid", 255).nullable()
    val createdAtEpochMillis = long("created_at_epoch_millis")
    val recordCount = integer("record_count")
    val signature = text("signature")

    override val primaryKey = PrimaryKey(repoDid, revision)

    init {
        index("idx_atproto_repo_commits_repo_revision", false, repoDid, revision)
    }
}

/** The log `com.atproto.sync.subscribeRepos` replays; see V51 for what each column holds. */
object AtprotoRepoEventsTable : Table("atproto_repo_events") {
    val seq = long("seq")
    val repoDid = varchar("repo_did", 255)
    val eventType = varchar("event_type", 16)
    val revision = long("revision").nullable()
    val frame = binary("frame")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(seq)

    init {
        index("idx_atproto_repo_events_repo", false, repoDid, seq)
        index("idx_atproto_repo_events_created", false, createdAt)
    }
}

/** Every repository followers were ever told about, kept after its events are pruned; see V51. */
object AtprotoRepoAnnouncedTable : Table("atproto_repo_announced") {
    val repoDid = varchar("repo_did", 255)
    val firstAnnouncedAt = timestamp("first_announced_at")

    override val primaryKey = PrimaryKey(repoDid)
}

/** The single row holding the last event number handed out; appending locks it. */
object AtprotoRepoEventSequenceTable : Table("atproto_repo_event_sequence") {
    val id = short("id")
    val lastSeq = long("last_seq")

    override val primaryKey = PrimaryKey(id)
}

object LogDateCollectionStatesTable : Table("logdate_collection_states") {
    val userId = javaUUID("user_id")
    val repoDid = varchar("repo_did", 255)
    val lastVersion = long("last_version")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(userId)
}

object LogDateCollectionRecordsTable : Table("logdate_collection_records") {
    val userId = javaUUID("user_id")
    val collection = varchar("collection", 32)
    val recordKey = varchar("record_key", 255)
    val serverVersion = long("server_version")
    val deleted = bool("deleted").default(false)
    val deletedAt = long("deleted_at").nullable()

    override val primaryKey = PrimaryKey(userId, collection, recordKey)

    init {
        index(
            "idx_logdate_collection_records_user_collection_deleted_version",
            false,
            userId,
            collection,
            deleted,
            serverVersion,
        )
    }
}

object LogDateMediaRecordsTable : Table("logdate_media_records") {
    val userId = javaUUID("user_id")
    val mediaId = varchar("media_id", 128)
    val contentId = varchar("content_id", 128)
    val fileName = varchar("file_name", 256)
    val mimeType = varchar("mime_type", 128)
    val sizeBytes = long("size_bytes")
    val data = binary("data")
    val storagePath = text("storage_path").nullable()
    val createdAt = long("created_at")
    val version = long("version")
    val deviceId = varchar("device_id", 128)
    val deleted = bool("deleted").default(false)
    val deletedAt = long("deleted_at").nullable()
    val encryptionVersion = integer("encryption_version").nullable()
    val encryptionKeyId = varchar("encryption_key_id", 128).nullable()
    val encryptionMode = varchar("encryption_mode", 16).nullable()

    override val primaryKey = PrimaryKey(userId, mediaId)

    init {
        index("idx_logdate_media_records_user", false, userId)
        index("idx_logdate_media_records_user_content", false, userId, contentId)
        index("idx_logdate_media_records_user_deleted", false, userId, deleted)
    }
}

object LogDateBackupsTable : Table("logdate_backups") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id")
    val deviceId = varchar("device_id", 128)
    val manifest = text("manifest")
    val storagePath = text("storage_path")
    val createdAt = long("created_at")
    val sizeBytes = long("size_bytes")

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_logdate_backups_user", false, userId)
        index("idx_logdate_backups_user_created_at", false, userId, createdAt)
    }
}

object LogDateAtprotoBlobsTable : Table("logdate_atproto_blobs") {
    val userId = javaUUID("user_id")
    val cid = varchar("cid", 255)
    val mimeType = varchar("mime_type", 128)
    val sizeBytes = long("size_bytes")
    val storagePath = text("storage_path")
    val createdAt = long("created_at")

    override val primaryKey = PrimaryKey(userId, cid)

    init {
        index("idx_logdate_atproto_blobs_user", false, userId)
        index("idx_logdate_atproto_blobs_user_created_at", false, userId, createdAt)
    }
}

/** Mirrors V39: one row per linked device session. */
object DeviceSessionsTable : Table("device_sessions") {
    val id = javaUUID("id")
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val deviceName = varchar("device_name", 120)
    val platform = varchar("platform", 32)
    val createdAt = long("created_at")
    val lastRefreshedAt = long("last_refreshed_at")
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_device_sessions_account", false, accountId)
    }
}

/** Mirrors V39: one row per minted link code, keyed by the SHA-256 hash of the code's bytes. */
object DeviceLinkCodesTable : Table("device_link_codes") {
    val codeHash = varchar("code_hash", 64)
    val accountId = javaUUID("account_id").references(AccountsTable.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val redeemedAt = long("redeemed_at").nullable()
    val sessionId = javaUUID("session_id").nullable()

    override val primaryKey = PrimaryKey(codeHash)

    init {
        index("idx_device_link_codes_account_created", false, accountId, createdAt)
        index("idx_device_link_codes_expires", false, expiresAt)
    }
}
