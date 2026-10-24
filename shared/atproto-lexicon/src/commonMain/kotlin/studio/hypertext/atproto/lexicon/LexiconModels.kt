package studio.hypertext.atproto.lexicon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import studio.hypertext.atproto.syntax.Nsid

/**
 * Parsed AT Protocol lexicon document.
 */
public data class LexiconDocument(
    val lexicon: Int,
    val id: Nsid,
    val definitions: Map<String, LexiconDefinition>,
)

/**
 * Parsed lexicon definition entry.
 */
public data class LexiconDefinition(
    val name: String,
    val type: LexiconType,
    val description: String? = null,
    val required: Set<String> = emptySet(),
    val nullable: Set<String> = emptySet(),
    val properties: Map<String, LexiconField> = emptyMap(),
    val items: LexiconField? = null,
    val reference: LexiconReference? = null,
    val knownValues: List<String> = emptyList(),
    val parameters: LexiconField? = null,
    val input: LexiconBody? = null,
    val output: LexiconBody? = null,
    val errors: List<LexiconError> = emptyList(),
    val keyRule: String? = null,
    val record: LexiconField? = null,
    val refs: List<LexiconReference> = emptyList(),
    val closed: Boolean = false,
    val accept: List<String> = emptyList(),
    val maxSize: Long? = null,
    val minLength: Long? = null,
    val maxLength: Long? = null,
    val unsupportedType: String? = null,
    val format: String? = null,
    val minGraphemes: Long? = null,
    val maxGraphemes: Long? = null,
    val minimum: Long? = null,
    val maximum: Long? = null,
    val closedValues: List<String>? = null,
    val constValue: JsonPrimitive? = null,
    /** The contents of a `permission-set` definition, and null for every other type. */
    val permissionSet: LexiconPermissionSet? = null,
    /** The contents of a `space` definition, and null for every other type. */
    val space: LexiconSpace? = null,
) {
    /** The record-key rule of a `record` definition, or null when it's missing or malformed. */
    val recordKey: LexiconRecordKey?
        get() = keyRule?.let(LexiconRecordKey::parse)

    /** This definition's value shape as a field, for code that checks fields and named definitions alike. */
    internal val asField: LexiconField by lazy {
        LexiconField(
            type = type,
            description = description,
            properties = properties,
            items = items,
            reference = reference,
            knownValues = knownValues,
            refs = refs,
            closed = closed,
            accept = accept,
            maxSize = maxSize,
            minLength = minLength,
            maxLength = maxLength,
            unsupportedType = unsupportedType,
            format = format,
            minGraphemes = minGraphemes,
            maxGraphemes = maxGraphemes,
            minimum = minimum,
            maximum = maximum,
            closedValues = closedValues,
            constValue = constValue,
        )
    }
}

/**
 * Parsed field inside a lexicon object or array definition.
 */
public data class LexiconField(
    val type: LexiconType,
    val description: String? = null,
    val required: Boolean = false,
    val nullable: Boolean = false,
    val properties: Map<String, LexiconField> = emptyMap(),
    val items: LexiconField? = null,
    val reference: LexiconReference? = null,
    val knownValues: List<String> = emptyList(),
    val refs: List<LexiconReference> = emptyList(),
    val closed: Boolean = false,
    val accept: List<String> = emptyList(),
    val maxSize: Long? = null,
    val minLength: Long? = null,
    val maxLength: Long? = null,
    val unsupportedType: String? = null,
    val format: String? = null,
    val minGraphemes: Long? = null,
    val maxGraphemes: Long? = null,
    val minimum: Long? = null,
    val maximum: Long? = null,
    val closedValues: List<String>? = null,
    val constValue: JsonPrimitive? = null,
)

/**
 * Parsed query/procedure input or output body.
 */
public data class LexiconBody(
    val encoding: String,
    val schema: LexiconField? = null,
)

/**
 * Parsed error declaration for queries and procedures.
 */
public data class LexiconError(
    val name: String,
    val description: String? = null,
)

/**
 * Lexicon definition and field types.
 */
public enum class LexiconType {
    OBJECT,
    QUERY,
    PROCEDURE,
    PARAMS,
    STRING,
    BOOLEAN,
    INTEGER,
    ARRAY,
    BLOB,
    BYTES,
    CID_LINK,
    TOKEN,
    REF,
    UNION,
    UNKNOWN,
    RECORD,
    SUBSCRIPTION,
    PERMISSION_SET,
    PERMISSION,

    /** A space type declaration, from the AT Protocol Spaces proposal. */
    SPACE,

    /** A type name the Lexicon specification doesn't define. */
    UNSUPPORTED,
    ;

    /** Whether this is a primary type, which may only be the `main` definition of a document. */
    public val isPrimary: Boolean
        get() = this in primaryTypes

    private companion object {
        private val primaryTypes = setOf(RECORD, QUERY, PROCEDURE, SUBSCRIPTION, PERMISSION_SET, SPACE)
    }
}

/**
 * A permission set: access an app asks for as one OAuth scope, `include:<set NSID>`, described in
 * words people see when they approve it. See https://atproto.com/specs/permission.
 */
public data class LexiconPermissionSet(
    /** The short name people see when an app asks for this set. */
    val title: String? = null,
    /** [title] translated, keyed by language tag. */
    val titleLang: Map<String, String> = emptyMap(),
    /** A summary of what the set allows, shown with [title]. */
    val detail: String? = null,
    /** [detail] translated, keyed by language tag. */
    val detailLang: Map<String, String> = emptyMap(),
    /** The permissions the set grants, or null when the schema doesn't list them. */
    val permissions: List<LexiconPermission>? = null,
)

/**
 * One permission in a permission set, such as write access to a record collection.
 *
 * The [resource] decides which [parameters] apply: `collection` and `action` for `repo`, `lxm`,
 * `aud` and `inheritAud` for `rpc`, and `spaceType`, `authority`, `skey`, `collection`, `action`
 * and `manage` for `space`. Parameters are kept as written so that resources and parameters
 * added to the specification later survive parsing.
 */
public data class LexiconPermission(
    /** The entry's `type`, which must be `permission`. */
    val type: String? = null,
    /** What the permission grants access to: `repo`, `rpc`, `space` and so on. */
    val resource: String? = null,
    /** Every other field of the entry, by name. */
    val parameters: Map<String, JsonElement> = emptyMap(),
) {
    /** The string values of parameter [name], which may be written as one value or a list; empty when it's absent. */
    public fun values(name: String): List<String> =
        when (val value = parameters[name]) {
            null -> emptyList()
            is JsonArray -> value.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            is JsonPrimitive -> listOf(value.contentOrNull ?: value.toString())
            else -> listOf(value.toString())
        }

    /** The value of parameter [name] when it's a single string. */
    public fun string(name: String): String? = (parameters[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The value of parameter [name] when it's a boolean. */
    public fun boolean(name: String): Boolean? = (parameters[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

    /** Whether the entry sets parameter [name] at all. */
    public fun has(name: String): Boolean = name in parameters
}

/**
 * A space type declaration: what a kind of AT Protocol space is called, how its spaces are keyed,
 * and which record collections apps should expect in it. Its NSID is the `{spaceType}` part of a
 * space's URI, `at://{authority}/space/{spaceType}/{skey}`.
 *
 * Spaces is still an alpha in the AT Protocol, so this follows the proposal as it stands.
 */
public data class LexiconSpace(
    /** The short name people see when an app asks for access to spaces of this type. */
    val name: String? = null,
    /** [name] translated, keyed by language tag. */
    val nameLang: Map<String, String> = emptyMap(),
    /** The schema's `key` value, the rule for space keys, as written. */
    val keyRule: String? = null,
    /** The record collections apps should expect, or null when the schema doesn't list them. */
    val collections: List<String>? = null,
) {
    /** The rule for this type's space keys, which uses the record-key rules, or null when it's missing or malformed. */
    val key: LexiconRecordKey?
        get() = keyRule?.let(LexiconRecordKey::parse)
}

/**
 * The rule a `record` definition sets for the keys of records in its collection. A `space`
 * declaration uses the same rules for the keys of its spaces.
 */
public sealed interface LexiconRecordKey {
    /** A timestamp identifier, the usual key for records someone creates over time. */
    public data object Tid : LexiconRecordKey

    /** A key that is itself a valid NSID. */
    public data object Nsid : LexiconRecordKey

    /** Any valid record key. */
    public data object Any : LexiconRecordKey

    /** Exactly one fixed key, such as `self` for a single profile record. */
    public data class Literal(
        val value: String,
    ) : LexiconRecordKey

    public companion object {
        /** Parses a schema's `key` value, or returns null when it isn't a known rule. */
        public fun parse(value: String): LexiconRecordKey? =
            when {
                value == "tid" -> {
                    Tid
                }

                value == "nsid" -> {
                    Nsid
                }

                value == "any" -> {
                    Any
                }

                value.startsWith(LITERAL_PREFIX) -> {
                    value.removePrefix(LITERAL_PREFIX).takeIf { it.isNotEmpty() }?.let(::Literal)
                }

                else -> {
                    null
                }
            }

        private const val LITERAL_PREFIX = "literal:"
    }
}

/**
 * Parsed reference to another lexicon definition.
 */
public data class LexiconReference(
    val documentId: Nsid,
    val definitionName: String,
) {
    /** `nsid#name`, for messages. */
    internal val label: String
        get() = "$documentId#$definitionName"

    /** The `$type` a union member of this definition carries: the bare NSID for `main`. */
    internal val unionTypeId: String
        get() = if (definitionName == MAIN_DEFINITION_NAME) documentId.toString() else label

    /** Whether [type], a union member's `$type`, names this definition, without building a string. */
    internal fun isUnionType(type: String): Boolean {
        val document = documentId.toString()
        if (definitionName == MAIN_DEFINITION_NAME) return type == document
        return type.length == document.length + 1 + definitionName.length &&
            type.startsWith(document) &&
            type[document.length] == '#' &&
            type.endsWith(definitionName)
    }

    public companion object {
        /**
         * Parses [value] relative to [owner].
         */
        public fun parse(
            owner: Nsid,
            value: String,
        ): LexiconReference {
            val trimmed = value.trim()
            require(trimmed.isNotBlank()) { "Lexicon ref must not be blank" }
            return when {
                trimmed.startsWith('#') -> {
                    LexiconReference(
                        documentId = owner,
                        definitionName = trimmed.removePrefix("#").ifBlank { MAIN_DEFINITION_NAME },
                    )
                }

                '#' in trimmed -> {
                    val documentId = Nsid.require(trimmed.substringBefore('#'))
                    val definitionName = trimmed.substringAfter('#').ifBlank { MAIN_DEFINITION_NAME }
                    LexiconReference(documentId = documentId, definitionName = definitionName)
                }

                else -> {
                    LexiconReference(
                        documentId = Nsid.require(trimmed),
                        definitionName = MAIN_DEFINITION_NAME,
                    )
                }
            }
        }
    }
}

internal const val MAIN_DEFINITION_NAME: String = "main"
