package app.logdate.server.database

import javax.sql.DataSource

/** Startup validation is read-only; schema changes belong to the existing migration workflow. */
internal fun requireIdentityAdoptionSchema(source: DataSource) {
    requireBlueskyOAuthSchema(source)
    try {
        source.connection.use { connection ->
            for (query in listOf(
                "SELECT identity_epoch FROM accounts LIMIT 0",
                "SELECT id,revision,phase,lease_expires_at FROM identity_adoption_operations LIMIT 0",
                "SELECT device_origin,device_public_key FROM identity_adoption_authorizations LIMIT 0",
                "SELECT binding_hash,challenge_hash FROM identity_adoption_decisions LIMIT 0",
                "SELECT proposal_id,canceled_at FROM identity_adoption_browser_proposals LIMIT 0",
                "SELECT operation_id,hold_generation FROM identity_adoption_staged_records LIMIT 0",
                "SELECT operation_id,hold_generation FROM identity_adoption_staged_blobs LIMIT 0",
                "SELECT operation_id,source_digest FROM identity_adoption_source_mappings LIMIT 0",
                "SELECT operation_id FROM identity_adoption_staged_origins LIMIT 0",
                "SELECT coverage_digest FROM identity_adoption_verifications LIMIT 0",
                "SELECT operation_id FROM identity_adoption_record_receipts LIMIT 0",
                "SELECT operation_id FROM identity_adoption_origin_receipts LIMIT 0",
                "SELECT owner_token,last_used_at,phase FROM identity_adoption_media_uploads LIMIT 0",
            )) {
                connection.prepareStatement(query).use {
                    it.queryTimeout = 5
                    it.executeQuery().close()
                }
            }
            connection.prepareStatement("SELECT 1 FROM flyway_schema_history WHERE version='72' AND success").use {
                it.queryTimeout = 5
                it.executeQuery().use { rows -> check(rows.next()) }
            }
            connection
                .prepareStatement(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                        "WHERE conrelid='webauthn_challenges'::regclass AND conname='challenge_type_valid' AND convalidated",
                ).use {
                    it.queryTimeout = 5
                    it.executeQuery().use { rows -> check(rows.next() && rows.getString(1).contains("'identity_adoption'")) }
                }
        }
    } catch (_: Exception) {
        throw IllegalStateException("Identity adoption storage is unavailable; apply its migrations before enabling it")
    }
}
