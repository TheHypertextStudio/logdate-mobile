@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.identity.adoption

import app.logdate.server.logdate.ENTRY_LEXICON_NSID
import app.logdate.server.logdate.LogDateCollectionKind
import app.logdate.shared.model.AccountStorageMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

internal class IdentityAdoptionStorageAuthority private constructor(
    val retiresLegacyJournal: Boolean,
) {
    fun retainsSource(
        kind: IdentityAdoptionSourceKind,
        item: IdentityAdoptionSourceItem,
    ): Boolean =
        retiresLegacyJournal &&
            when (kind) {
                IdentityAdoptionSourceKind.SYNC_CONTENT ->
                    (item.fields["type"] as? JsonPrimitive)?.content in setOf("TEXT", "IMAGE", "VIDEO", "AUDIO", "HANDWRITING")
                IdentityAdoptionSourceKind.SYNC_JOURNAL,
                IdentityAdoptionSourceKind.SYNC_ASSOCIATION, IdentityAdoptionSourceKind.LOCATION,
                -> true
                IdentityAdoptionSourceKind.COLLECTION_RECORD ->
                    LogDateCollectionKind.entries.any { it.storageName == (item.fields["collection"] as? JsonPrimitive)?.content }
                else -> false
            }

    fun activeRepositoryRecords(inventory: IdentityAdoptionRepositoryInventory): List<IdentityAdoptionRepositoryRecord> =
        inventory.records.filter { !retiresLegacyJournal || it.collection !in legacyCollections }

    fun retainsRepositoryBlock(
        cid: String,
        inventory: IdentityAdoptionRepositoryInventory,
    ): Boolean =
        retiresLegacyJournal &&
            inventory.records.any { it.cid == cid } &&
            inventory.records.none { it.cid == cid && it.collection !in legacyCollections }

    companion object {
        private val legacyCollections = LogDateCollectionKind.entries.map { it.nsid.toString() }.toSet() + ENTRY_LEXICON_NSID.toString()

        fun read(
            item: IdentityAdoptionSourceItem,
            binding: IdentityAdoptionBinding,
        ): IdentityAdoptionStorageAuthority {
            requireAuthority(item.binary == null && item.key == listOf(binding.accountId.toString()))
            requireAuthority(item.text("account_id") == binding.accountId.toString() && item.text("repo_did") == binding.sourceDid)
            requireAuthority(item.number("identity_epoch") == binding.sourceEpoch && binding.sourceEpoch >= 0)
            val mode = item.text("storage_mode")
            requireAuthority(mode == AccountStorageMode.LEGACY.wireValue || mode == AccountStorageMode.PERSONAL_SPACE_V1.wireValue)
            val fields =
                listOf(
                    "migration_id",
                    "migration_phase",
                    "migration_version",
                    "migration_identity_epoch",
                    "migration_switched_at",
                    "migration_switched_at_seq",
                    "migration_reverted_at",
                    "migration_cleaned_at",
                )
            if (item.fields["migration_id"] == JsonNull) {
                requireAuthority(fields.all { item.fields[it] == JsonNull && it in item.sqlNullColumns })
                return IdentityAdoptionStorageAuthority(false)
            }
            val id = item.text("migration_id")
            requireAuthority(id.isNotEmpty() && id.encodeToByteArray().size <= 64)
            val phase = item.text("migration_phase")
            requireAuthority(phase in setOf("uploading", "switched", "reverted", "cleaned"))
            requireAuthority(requireNotNull(item.number("migration_version")) > 0)
            val epoch = item.number("migration_identity_epoch")
            val switched = item.timestamp("migration_switched_at")
            val sequence = item.number("migration_switched_at_seq", optional = true)
            val reverted = item.timestamp("migration_reverted_at")
            item.timestamp("migration_cleaned_at")
            val completed = phase == "switched" || phase == "cleaned"
            requireAuthority(!completed || (switched != null && sequence != null && reverted == null))
            return IdentityAdoptionStorageAuthority(
                mode == AccountStorageMode.PERSONAL_SPACE_V1.wireValue && completed && epoch == binding.sourceEpoch,
            )
        }

        private fun IdentityAdoptionSourceItem.text(field: String): String {
            val value = fields[field] as? JsonPrimitive
            requireAuthority(value?.isString == true)
            return requireNotNull(value).content
        }

        private fun IdentityAdoptionSourceItem.number(
            field: String,
            optional: Boolean = false,
        ): Long? {
            if (optional && fields[field] == JsonNull && field in sqlNullColumns) return null
            val value = fields[field] as? JsonPrimitive
            val number = value?.takeUnless { it.isString }?.longOrNull
            requireAuthority(number != null && number >= 0)
            return number
        }

        private fun IdentityAdoptionSourceItem.timestamp(field: String): Instant? {
            if (fields[field] == JsonNull && field in sqlNullColumns) return null
            val raw = text(field)
            return try {
                Instant.parse(raw)
            } catch (_: IllegalArgumentException) {
                throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
            }
        }

        private fun requireAuthority(value: Boolean) {
            if (!value) throw IdentityAdoptionStagingException(IdentityAdoptionStagingFailure.UNSUPPORTED_SOURCE)
        }
    }
}
