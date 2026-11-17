package app.logdate.server.logdate

import app.logdate.shared.model.sync.DeviceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.hypertext.atproto.identity.AtprotoDid
import studio.hypertext.atproto.repo.RepoRecordId
import studio.hypertext.atproto.repo.UnsupportedCollectionException
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey

internal val ENTRY_LEXICON_NSID: Nsid = Nsid.require("studio.hypertext.logdate.entry")

internal enum class LogDateCollectionKind(
    val storageName: String,
    val nsid: Nsid,
) {
    ENTRY(
        storageName = "entry",
        nsid = Nsid.require("studio.hypertext.logdate.content"),
    ),
    JOURNAL(
        storageName = "journal",
        nsid = Nsid.require("studio.hypertext.logdate.journal"),
    ),
    ASSOCIATION(
        storageName = "association",
        nsid = Nsid.require("studio.hypertext.logdate.association"),
    ),
    DRAFT(
        storageName = "draft",
        nsid = Nsid.require("studio.hypertext.logdate.draft"),
    ),
    MOMENT(
        storageName = "moment",
        nsid = Nsid.require("studio.hypertext.logdate.moment"),
    ),
}

internal fun entryRecordId(
    repo: AtprotoDid,
    id: String,
): RepoRecordId =
    RepoRecordId(
        repo = repo,
        collection = LogDateCollectionKind.ENTRY.nsid,
        recordKey = RecordKey.require(id),
    )

internal fun journalRecordId(
    repo: AtprotoDid,
    id: String,
): RepoRecordId =
    RepoRecordId(
        repo = repo,
        collection = LogDateCollectionKind.JOURNAL.nsid,
        recordKey = RecordKey.require(id),
    )

internal fun associationRecordId(
    repo: AtprotoDid,
    journalId: String,
    entryId: String,
): RepoRecordId =
    RepoRecordId(
        repo = repo,
        collection = LogDateCollectionKind.ASSOCIATION.nsid,
        recordKey = associationRecordKey(journalId, entryId),
    )

internal fun draftRecordId(
    repo: AtprotoDid,
    id: String,
): RepoRecordId =
    RepoRecordId(
        repo = repo,
        collection = LogDateCollectionKind.DRAFT.nsid,
        recordKey = RecordKey.require(id),
    )

internal fun momentRecordId(
    repo: AtprotoDid,
    id: String,
): RepoRecordId =
    RepoRecordId(
        repo = repo,
        collection = LogDateCollectionKind.MOMENT.nsid,
        recordKey = RecordKey.require(id),
    )

internal fun LogDateMoment.toRepoJson(): JsonObject =
    buildJsonObject {
        put(TYPE_FIELD_NAME, LogDateCollectionKind.MOMENT.nsid.toString())
        put("id", id)
        put("capturedAt", capturedAt)
        put("lastUpdated", lastUpdated)
        put("deviceId", deviceId.value)
        put("payload", payload)
        put("schema", schema)
    }

internal fun JsonObject.toLogDateMoment(
    recordKey: RecordKey,
    version: Long,
): LogDateMoment =
    LogDateMoment(
        id = stringValue("id") ?: recordKey.toString(),
        capturedAt = longValue("capturedAt") ?: 0L,
        lastUpdated = longValue("lastUpdated") ?: 0L,
        version = version,
        deviceId = deviceIdOrDefault(),
        payload = stringValue("payload").orEmpty(),
        schema = this["schema"]?.jsonPrimitive?.intOrNull ?: 1,
    )

internal fun associationRecordKey(
    journalId: String,
    entryId: String,
): RecordKey = RecordKey.require("$journalId~$entryId")

internal fun associationRecordKey(value: JsonObject): RecordKey =
    associationRecordKey(
        journalId = requireNotNull(value.stringValue("journalId")) { "journalId is required" },
        entryId = requireNotNull(value.stringValue("contentId")) { "contentId is required" },
    )

internal fun journalIdFromAssociationRecordKey(recordKey: RecordKey): String = recordKey.toString().substringBefore('~')

internal fun entryIdFromAssociationRecordKey(recordKey: RecordKey): String = recordKey.toString().substringAfter('~')

internal fun LogDateEntry.toRepoJson(): JsonObject =
    buildJsonObject {
        put(TYPE_FIELD_NAME, LogDateCollectionKind.ENTRY.nsid.toString())
        put("id", id)
        put("type", type)
        if (content != null) {
            put("content", content)
        } else {
            put("content", JsonNull)
        }
        if (mediaUri != null) {
            put("mediaUri", mediaUri)
        } else {
            put("mediaUri", JsonNull)
        }
        put("durationMs", durationMs ?: DEFAULT_DURATION_MS)
        put("createdAt", createdAt)
        put("lastUpdated", lastUpdated)
        put("deviceId", deviceId.value)
        if (photoPresentation != null) {
            put("photoPresentation", photoPresentation)
        }
        if (caption != null) {
            put("caption", caption)
        }
        if (location != null) {
            put("location", location)
        }
        if (transcript != null) {
            put("transcript", transcript)
        }
        if (capturedOn != null) {
            put("capturedOn", capturedOn)
        }
        if (capturedDuring != null) {
            put("capturedDuring", capturedDuring)
        }
    }

internal fun LogDateEntry.toEntryRepoJson(): JsonObject =
    buildJsonObject {
        put(TYPE_FIELD_NAME, ENTRY_LEXICON_NSID.toString())
        put("id", id)
        put("type", type)
        if (content != null) {
            put("content", content)
        } else {
            put("content", JsonNull)
        }
        put("durationMs", durationMs ?: DEFAULT_DURATION_MS)
        put("createdAt", createdAt)
        put("lastUpdated", lastUpdated)
        put("deviceId", deviceId.value)
        if (photoPresentation != null) {
            put("photoPresentation", photoPresentation)
        }
        if (caption != null) {
            put("caption", caption)
        }
        if (location != null) {
            put("location", location)
        }
        if (transcript != null) {
            put("transcript", transcript)
        }
        if (capturedOn != null) {
            put("capturedOn", capturedOn)
        }
        if (capturedDuring != null) {
            put("capturedDuring", capturedDuring)
        }
    }

internal fun JsonObject.toLogDateEntry(
    recordKey: RecordKey,
    version: Long,
): LogDateEntry =
    LogDateEntry(
        id = stringValue("id") ?: recordKey.toString(),
        type = stringValue("type") ?: DEFAULT_CONTENT_TYPE,
        content = nullableStringValue("content"),
        mediaUri = nullableStringValue("mediaUri"),
        durationMs = longValue("durationMs") ?: DEFAULT_DURATION_MS,
        createdAt = longValue("createdAt") ?: 0L,
        lastUpdated = longValue("lastUpdated") ?: 0L,
        version = version,
        deviceId = deviceIdOrDefault(),
        caption = nullableStringValue("caption"),
        photoPresentation = nullableStringValue("photoPresentation"),
        location = nullableStringValue("location"),
        transcript = nullableStringValue("transcript"),
        capturedOn = nullableStringValue("capturedOn"),
        capturedDuring = nullableStringValue("capturedDuring"),
    )

internal fun LogDateJournal.toRepoJson(): JsonObject =
    buildJsonObject {
        put(TYPE_FIELD_NAME, LogDateCollectionKind.JOURNAL.nsid.toString())
        put("id", id)
        put("title", title)
        put("description", description)
        put("createdAt", createdAt)
        put("lastUpdated", lastUpdated)
        put("deviceId", deviceId.value)
    }

internal fun JsonObject.toLogDateJournal(
    recordKey: RecordKey,
    version: Long,
): LogDateJournal =
    LogDateJournal(
        id = stringValue("id") ?: recordKey.toString(),
        title = stringValue("title").orEmpty(),
        description = stringValue("description").orEmpty(),
        createdAt = longValue("createdAt") ?: 0L,
        lastUpdated = longValue("lastUpdated") ?: 0L,
        version = version,
        deviceId = deviceIdOrDefault(),
    )

internal fun LogDateAssociation.toRepoJson(): JsonObject =
    buildJsonObject {
        put(TYPE_FIELD_NAME, LogDateCollectionKind.ASSOCIATION.nsid.toString())
        put("journalId", journalId)
        put("contentId", entryId)
        put("createdAt", createdAt)
        put("deviceId", deviceId.value)
    }

internal fun JsonObject.toLogDateAssociation(
    recordKey: RecordKey,
    version: Long,
): LogDateAssociation {
    val journalId = stringValue("journalId") ?: journalIdFromAssociationRecordKey(recordKey)
    val entryId = stringValue("contentId") ?: entryIdFromAssociationRecordKey(recordKey)
    return LogDateAssociation(
        journalId = journalId,
        entryId = entryId,
        createdAt = longValue("createdAt") ?: 0L,
        version = version,
        deviceId = deviceIdOrDefault(),
    )
}

internal fun requireMatchingType(
    value: JsonObject,
    collection: Nsid,
) {
    val typeField = value[TYPE_FIELD_NAME]?.jsonPrimitive?.contentOrNull
    if (typeField != null && typeField != collection.toString()) {
        throw UnsupportedCollectionException(typeField)
    }
}

internal fun requireMatchingId(
    recordId: RepoRecordId,
    value: JsonObject,
    fieldName: String,
): String {
    val explicitId = value.stringValue(fieldName)
    require(explicitId == null || explicitId == recordId.recordKey.toString()) {
        "$fieldName must match the record key ${recordId.recordKey}"
    }
    return explicitId ?: recordId.recordKey.toString()
}

internal fun JsonObject.stringValue(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.nullableStringValue(key: String): String? =
    when (val element = this[key]) {
        null, JsonNull -> null
        else -> element.jsonPrimitive.contentOrNull
    }

internal fun JsonObject.longValue(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

internal fun JsonObject.deviceIdOrDefault(default: DeviceId? = null): DeviceId =
    if (containsKey("deviceId")) {
        DeviceId(nullableStringValue("deviceId") ?: DeviceId.UNKNOWN.value)
    } else {
        default ?: DeviceId.UNKNOWN
    }

internal fun LogDateDraft.toRepoJson(): JsonObject =
    buildJsonObject {
        put(TYPE_FIELD_NAME, LogDateCollectionKind.DRAFT.nsid.toString())
        put("id", id)
        put("content", content)
        put("blockTypes", JsonArray(blockTypes.map(::JsonPrimitive)))
        put("journalIds", JsonArray(journalIds.map(::JsonPrimitive)))
        if (encryptedBlocksVersion != null) put("encryptedBlocksVersion", encryptedBlocksVersion)
        if (encryptedBlocks != null) put("encryptedBlocks", encryptedBlocks)
        put("createdAt", createdAt)
        put("lastUpdated", lastUpdated)
        put("deviceId", deviceId.value)
    }

internal fun JsonObject.toLogDateDraft(
    recordKey: RecordKey,
    version: Long,
): LogDateDraft =
    LogDateDraft(
        id = stringValue("id") ?: recordKey.toString(),
        content = stringValue("content").orEmpty(),
        blockTypes = this["blockTypes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        journalIds = this["journalIds"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        createdAt = longValue("createdAt") ?: 0L,
        lastUpdated = longValue("lastUpdated") ?: 0L,
        version = version,
        deviceId = deviceIdOrDefault(),
        encryptedBlocksVersion = this["encryptedBlocksVersion"]?.jsonPrimitive?.intOrNull,
        encryptedBlocks = stringValue("encryptedBlocks"),
    )

internal const val DEFAULT_CONTENT_TYPE: String = "TEXT"
internal const val DEFAULT_DURATION_MS: Long = 0L
internal const val TYPE_FIELD_NAME: String = "\$type"
