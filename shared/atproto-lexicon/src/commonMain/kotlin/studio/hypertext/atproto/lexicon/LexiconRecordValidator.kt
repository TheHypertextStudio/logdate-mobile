package studio.hypertext.atproto.lexicon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import studio.hypertext.atproto.lexicon.data.BLOB_TYPE
import studio.hypertext.atproto.lexicon.data.BYTES_KEY
import studio.hypertext.atproto.lexicon.data.LINK_KEY
import studio.hypertext.atproto.lexicon.data.TYPE_KEY
import studio.hypertext.atproto.lexicon.data.bytesBase64
import studio.hypertext.atproto.lexicon.data.singleString
import studio.hypertext.atproto.lexicon.data.string
import studio.hypertext.atproto.syntax.AtUri
import studio.hypertext.atproto.syntax.Datetime
import studio.hypertext.atproto.syntax.Did
import studio.hypertext.atproto.syntax.Handle
import studio.hypertext.atproto.syntax.LanguageTag
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey
import studio.hypertext.atproto.syntax.Tid

/**
 * Checks records against their lexicon schemas.
 *
 * Every problem is reported with the path of the value it concerns, such as `blocks[0].text`.
 * Data the schema doesn't describe is accepted, so records written by a newer version of a schema
 * stay valid: unknown fields, and members of open unions this schema doesn't list.
 */
public class LexiconRecordValidator(
    private val registry: LexiconRegistry,
) {
    /** Checks [record], a record in the [collection], against that collection's schema. */
    public fun validateRecord(
        collection: Nsid,
        record: JsonObject,
    ): LexiconValidationResult {
        val definition = registry.resolve(LexiconReference(collection, MAIN_DEFINITION_NAME))
        val schema =
            definition?.record?.takeIf { definition.type == LexiconType.RECORD }
                ?: return LexiconValidationResult(listOf(LexiconValidationIssue("", notARecordType(collection, definition?.type))))
        val issues = mutableListOf<LexiconValidationIssue>()
        record[TYPE_KEY]?.let { type ->
            val name = type.stringOrNull()
            if (name != collection.toString()) {
                issues += LexiconValidationIssue(TYPE_KEY, "${name ?: type.display()} doesn't match the collection $collection")
            }
        }
        Check(issues).value("", record, schema)
        return LexiconValidationResult(issues)
    }

    private inner class Check(
        private val issues: MutableList<LexiconValidationIssue>,
    ) {
        fun value(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            when (field.type) {
                LexiconType.OBJECT -> objectValue(path, value, field)
                LexiconType.STRING -> stringValue(path, value, field)
                LexiconType.INTEGER -> integerValue(path, value, field)
                LexiconType.BOOLEAN -> booleanValue(path, value, field)
                LexiconType.ARRAY -> arrayValue(path, value, field)
                LexiconType.BLOB -> blobValue(path, value, field)
                LexiconType.BYTES -> bytesValue(path, value, field)
                LexiconType.CID_LINK -> if (value.singleString(LINK_KEY) == null) report(path, "not a valid content link")
                LexiconType.UNKNOWN -> if (value !is JsonObject) report(path, "expected an object")
                LexiconType.REF -> refValue(path, value, requireNotNull(field.reference))
                LexiconType.UNION -> unionValue(path, value, field)
                else -> Unit
            }
        }

        private fun objectValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            if (value !is JsonObject) return report(path, "expected an object")
            field.properties.forEach { (name, property) ->
                val propertyPath = if (path.isEmpty()) name else "$path.$name"
                when (val propertyValue = value[name]) {
                    null -> if (property.required) report(propertyPath, "required field is missing")
                    JsonNull -> if (!property.nullable) report(propertyPath, "null isn't allowed")
                    else -> value(propertyPath, propertyValue, property)
                }
            }
        }

        private fun stringValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            val text = value.stringOrNull() ?: return report(path, "expected a string")
            if (field.maxLength != null || field.minLength != null) byteLength(path, utf8Length(text), field)
            if (field.maxGraphemes != null || field.minGraphemes != null) {
                val graphemes = graphemeCount(text).toLong()
                field.maxGraphemes?.let { if (graphemes > it) report(path, "longer than $it graphemes") }
                field.minGraphemes?.let { if (graphemes < it) report(path, "shorter than $it graphemes") }
            }
            field.format?.let { format -> if (!matchesFormat(format, text)) report(path, "not a valid $format") }
            closedValues(path, value as JsonPrimitive, field)
        }

        private fun integerValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            val primitive = value as? JsonPrimitive
            val number = primitive?.takeUnless { it.isString }?.content?.toLongOrNull() ?: return report(path, "expected an integer")
            field.minimum?.let { if (number < it) report(path, "less than the minimum of $it") }
            field.maximum?.let { if (number > it) report(path, "more than the maximum of $it") }
            closedValues(path, primitive, field)
        }

        private fun booleanValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            val primitive = value as? JsonPrimitive
            if (primitive?.takeUnless { it.isString }?.booleanOrNull == null) return report(path, "expected a boolean")
            closedValues(path, primitive, field)
        }

        private fun closedValues(
            path: String,
            value: JsonPrimitive,
            field: LexiconField,
        ) {
            val content = value.content
            field.closedValues?.let { allowed ->
                if (content !in allowed) report(path, "${value.display()} isn't one of the allowed values")
            }
            field.constValue?.let { constant ->
                if (content != constant.content) report(path, "must be ${constant.display()}")
            }
        }

        private fun arrayValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            if (value !is JsonArray) return report(path, "expected an array")
            field.maxLength?.let { if (value.size > it) report(path, "more than $it items") }
            field.minLength?.let { if (value.size < it) report(path, "fewer than $it items") }
            val items = field.items ?: return
            value.forEachIndexed { index, item ->
                if (item == JsonNull) report("$path[$index]", "null isn't allowed") else value("$path[$index]", item, items)
            }
        }

        private fun blobValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            val blob = value as? JsonObject ?: return report(path, "not a valid blob")
            val mimeType = blob.string("mimeType")
            val size = (blob["size"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            if (blob.string(TYPE_KEY) != BLOB_TYPE ||
                blob["ref"]?.singleString(LINK_KEY) == null ||
                mimeType.isNullOrEmpty() ||
                size == null ||
                size < 0
            ) {
                return report(path, "not a valid blob")
            }
            if (field.accept.isNotEmpty() && field.accept.none { mimeMatches(it, mimeType) }) {
                report(path, "$mimeType isn't an accepted type")
            }
            field.maxSize?.let { if (size > it) report(path, "$size bytes is more than the maximum of $it") }
        }

        private fun bytesValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            val decoded =
                value.singleString(BYTES_KEY)?.let { encoded -> runCatching { bytesBase64.decode(encoded) }.getOrNull() }
                    ?: return report(path, "not valid bytes")
            byteLength(path, decoded.size.toLong(), field)
        }

        private fun byteLength(
            path: String,
            length: Long,
            field: LexiconField,
        ) {
            field.maxLength?.let { if (length > it) report(path, "longer than $it bytes") }
            field.minLength?.let { if (length < it) report(path, "shorter than $it bytes") }
        }

        private fun refValue(
            path: String,
            value: JsonElement,
            reference: LexiconReference,
        ) {
            val target = registry.resolve(reference) ?: return report(path, "the schema refers to ${reference.label}, which isn't known")
            value(path, value, if (target.type == LexiconType.RECORD) requireNotNull(target.record) else target.asField)
        }

        private fun unionValue(
            path: String,
            value: JsonElement,
            field: LexiconField,
        ) {
            if (value !is JsonObject) return report(path, "expected an object")
            val type = value[TYPE_KEY]?.stringOrNull() ?: return report(path, "a union member must have a $TYPE_KEY")
            val member = field.refs.firstOrNull { it.isUnionType(type) }
            when {
                member != null -> refValue(path, value, member)
                field.closed -> report(path, "$TYPE_KEY $type isn't allowed in this closed union")
            }
        }

        private fun report(
            path: String,
            message: String,
        ) {
            issues += LexiconValidationIssue(path, message)
        }
    }
}

private fun notARecordType(
    collection: Nsid,
    type: LexiconType?,
): String =
    when (type) {
        LexiconType.SPACE -> "$collection is a space type, not a record type"
        LexiconType.PERMISSION_SET -> "$collection is a permission set, not a record type"
        else -> "$collection isn't a known record type"
    }

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonElement.display(): String = if (this is JsonPrimitive && isString) "\"$content\"" else toString()

private fun mimeMatches(
    pattern: String,
    mimeType: String,
): Boolean = pattern == "*/*" || pattern == mimeType || (pattern.endsWith("/*") && mimeType.startsWith(pattern.dropLast(1)))

private val uriPattern = Regex("^[A-Za-z][A-Za-z0-9+.-]*:\\S+$")
private val cidPattern = Regex("^[A-Za-z0-9+=]{8,256}$")

private fun matchesFormat(
    format: String,
    value: String,
): Boolean =
    when (format) {
        "datetime" -> Datetime.isValid(value)

        "uri" -> uriPattern.matches(value)

        "at-uri" -> AtUri.isValid(value)

        "did" -> Did.isValid(value)

        "handle" -> Handle.isValid(value)

        "at-identifier" -> if (value.startsWith("did:")) Did.isValid(value) else Handle.isValid(value)

        "nsid" -> Nsid.isValid(value)

        "cid" -> cidPattern.matches(value)

        "language" -> LanguageTag.isValid(value)

        "tid" -> Tid.isValid(value)

        "record-key" -> RecordKey.isValid(value)

        "space-ref" -> isSpaceRef(value)

        // A format this version doesn't know, from a newer schema, isn't held against the record.
        else -> true
    }

// A space, not a record in one: at://{authority DID}/space/{space type NSID}/{skey}, from the AT
// Protocol Spaces proposal. Unlike a public AT URI, the authority can't be a handle.
private fun isSpaceRef(value: String): Boolean {
    if (!value.startsWith(AT_SCHEME)) return false
    val parts = value.removePrefix(AT_SCHEME).split('/')
    if (parts.size != SPACE_REF_PARTS) return false
    val (authority, marker, spaceType, skey) = parts
    return marker == SPACE_MARKER && Did.isValid(authority) && Nsid.isValid(spaceType) && RecordKey.isValid(skey)
}

private const val AT_SCHEME = "at://"
private const val SPACE_MARKER = "space"
private const val SPACE_REF_PARTS = 4

/**
 * Counts user-perceived characters, close to Unicode extended grapheme clusters: combining marks,
 * zero-width-joined emoji sequences, emoji modifiers and variation selectors, tag sequences,
 * regional-indicator flag pairs, and CRLF each stay part of the character before them.
 */
private fun graphemeCount(text: String): Int {
    var count = 0
    var index = 0
    var previous = -1
    var regionalRun = 0
    while (index < text.length) {
        val codePoint = codePointAt(text, index)
        index += if (codePoint > 0xFFFF) 2 else 1

        val regional = codePoint in 0x1F1E6..0x1F1FF
        regionalRun = if (regional) regionalRun + 1 else 0
        val joinsPrevious =
            previous != -1 &&
                (
                    previous == ZERO_WIDTH_JOINER ||
                        codePoint == ZERO_WIDTH_JOINER ||
                        extendsCharacter(codePoint) ||
                        (previous == '\r'.code && codePoint == '\n'.code) ||
                        (regional && regionalRun % 2 == 0)
                )
        if (!joinsPrevious) count++
        previous = codePoint
    }
    return count
}

private const val ZERO_WIDTH_JOINER = 0x200D

private fun extendsCharacter(codePoint: Int): Boolean =
    when {
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF -> true
        codePoint in 0x1F3FB..0x1F3FF -> true
        codePoint in 0xE0020..0xE007F -> true
        codePoint <= 0xFFFF -> codePoint.toChar().category in markCategories
        else -> false
    }

private val markCategories = setOf(CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK, CharCategory.COMBINING_SPACING_MARK)

// commonMain has no String.codePointAt.
private fun codePointAt(
    text: String,
    index: Int,
): Int {
    val high = text[index]
    if (!high.isHighSurrogate() || index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return high.code
    return ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00) + 0x10000
}

// The UTF-8 length of the text, counted without encoding it.
private fun utf8Length(text: String): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val codePoint = codePointAt(text, index)
        index += if (codePoint > 0xFFFF) 2 else 1
        bytes +=
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint <= 0xFFFF -> 3
                else -> 4
            }
    }
    return bytes
}
