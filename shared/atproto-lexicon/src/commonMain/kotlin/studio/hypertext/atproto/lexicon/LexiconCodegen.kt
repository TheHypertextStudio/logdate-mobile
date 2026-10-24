package studio.hypertext.atproto.lexicon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import studio.hypertext.atproto.syntax.Nsid

/**
 * Deterministic Kotlin source generator for lexicon documents.
 *
 * Each document becomes one file: an object holding its id, a data class per object, record,
 * params, input and output, and a sealed interface with its own serializer per union. Optional
 * fields default to null, so a record that omits them decodes and re-encodes unchanged.
 */
public object LexiconCodegen {
    /**
     * Generates Kotlin source for [document].
     */
    public fun generate(
        document: LexiconDocument,
        packageName: String = "studio.hypertext.atproto.lexicon.generated",
    ): String = generate(document, LexiconPackageLayout.flat(packageName))

    /**
     * Generates Kotlin source for [document], placed and referring to other documents according
     * to [layout].
     */
    public fun generate(
        document: LexiconDocument,
        layout: LexiconPackageLayout,
    ): String = DocumentWriter(document, layout).write()

    /**
     * Generates an object named [objectName] in [packageName] whose `records` map holds the
     * serializer for every record type in [documents], keyed by collection NSID.
     */
    public fun generateRecordIndex(
        documents: List<LexiconDocument>,
        layout: LexiconPackageLayout,
        packageName: String,
        objectName: String,
    ): String {
        val records =
            documents
                .filter { it.definitions[MAIN_DEFINITION_NAME]?.type == LexiconType.RECORD }
                .sortedBy { it.id.toString() }
        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import kotlinx.serialization.KSerializer")
            appendLine()
            appendLine("/** Every record type generated from these schemas. */")
            appendLine("public object $objectName {")
            appendLine("    /** The serializer for each record type, keyed by its collection NSID. */")
            appendLine("    public val records: Map<String, KSerializer<*>> =")
            appendLine("        mapOf(")
            records.forEach { document ->
                val type = "${layout.packageFor(document.id)}.${className(document.id, MAIN_DEFINITION_NAME)}"
                appendLine("            \"${document.id}\" to $type.serializer(),")
            }
            appendLine("        )")
            appendLine("}")
        }
    }

    /**
     * Generates an object named [objectName] in [packageName] whose `documents` list holds the JSON
     * of every lexicon document in [sources], minified and sorted by NSID. Parse them with
     * [LexiconParser] into a [LexiconRegistry] to check records with [LexiconRecordValidator] at
     * runtime, on platforms that can't read the schema files themselves.
     */
    public fun generateSchemaIndex(
        sources: List<String>,
        packageName: String,
        objectName: String,
    ): String {
        val documents =
            sources
                .map { source -> Json.parseToJsonElement(source).jsonObject }
                .sortedBy { document -> document["id"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("/** The lexicon documents these models were generated from. */")
            appendLine("public object $objectName {")
            appendLine("    /** Each document's JSON, sorted by its NSID. */")
            appendLine("    public val documents: List<String> =")
            appendLine("        listOf(")
            documents.forEach { document -> appendLine("            ${kotlinStringLiteral(document.toString())},") }
            appendLine("        )")
            appendLine("}")
        }
    }

    private fun kotlinStringLiteral(value: String): String =
        buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(character)
                }
            }
            append('"')
        }

    private class DocumentWriter(
        private val document: LexiconDocument,
        private val layout: LexiconPackageLayout,
    ) {
        private val packageName = layout.packageFor(document.id)

        /** The name to use for [reference]'s class here: simple in this package, qualified otherwise. */
        private fun typeName(reference: LexiconReference): String {
            val targetPackage = layout.packageFor(reference.documentId)
            val name = className(reference.documentId, reference.definitionName)
            return if (targetPackage == packageName) name else "$targetPackage.$name"
        }

        private fun qualifiedTypeName(reference: LexiconReference): String =
            "${layout.packageFor(reference.documentId)}.${className(reference.documentId, reference.definitionName)}"

        private val imports = mutableSetOf<String>()
        private val declarations = mutableListOf<String>()
        private val prefix = document.id.documentPrefix()

        fun write(): String {
            document.definitions.values
                .sortedBy { if (it.name == MAIN_DEFINITION_NAME) "" else it.name }
                .forEach(::writeDefinition)

            return buildString {
                if (OPT_IN_SERIALIZATION in imports) {
                    appendLine("@file:OptIn(ExperimentalSerializationApi::class)")
                    appendLine()
                }
                appendLine("package $packageName")
                appendLine()
                imports.sorted().forEach { appendLine("import $it") }
                if (imports.isNotEmpty()) appendLine()
                appendLine("public object ${prefix}Lexicon {")
                appendLine("    public const val ID: String = \"${document.id}\"")
                appendLine("}")
                declarations.forEach { declaration ->
                    appendLine()
                    append(declaration)
                }
            }
        }

        private fun writeDefinition(definition: LexiconDefinition) {
            when (definition.type) {
                LexiconType.OBJECT, LexiconType.PARAMS -> {
                    writeClass(className(document.id, definition.name), definition.description, definition.properties)
                }

                LexiconType.RECORD -> {
                    definition.record?.let { record -> writeRecord(definition, record) }
                }

                LexiconType.SPACE -> {
                    definition.space?.let { space -> writeSpace(definition, space) }
                }

                LexiconType.PERMISSION_SET -> {
                    writePermissionSet(definition)
                }

                in aliasedTypes -> {
                    writeAlias(definition)
                }

                LexiconType.QUERY, LexiconType.PROCEDURE -> {
                    definition.parameters?.let { writeClass("${prefix}Params", it.description, it.properties) }
                    definition.input?.schema?.takeIf { it.type.isObjectLike() }?.let {
                        writeClass("${prefix}Input", it.description, it.properties)
                    }
                    definition.output?.schema?.takeIf { it.type.isObjectLike() }?.let {
                        writeClass("${prefix}Output", it.description, it.properties)
                    }
                }

                else -> {
                    Unit
                }
            }
        }

        private fun writeClass(
            name: String,
            description: String?,
            properties: Map<String, LexiconField>,
        ) {
            imports += SERIALIZABLE
            val lines = propertyLines(name, properties).ifEmpty { listOf("    val unused: Boolean = true,") }
            declarations +=
                buildString {
                    appendKdoc(description, indent = "")
                    appendLine("@Serializable")
                    appendLine("public data class $name(")
                    lines.forEach(::appendLine)
                    appendLine(")")
                }
        }

        // A named string, array or other non-object definition, which other fields refer to by name.
        private fun writeAlias(definition: LexiconDefinition) {
            val name = className(document.id, definition.name)
            val field =
                LexiconField(
                    type = definition.type,
                    items = definition.items,
                    reference = definition.reference,
                    required = true,
                )
            val type = kotlinType(field, unionName = name)
            declarations +=
                buildString {
                    appendKdoc(definition.description, indent = "")
                    appendLine("public typealias $name = $type")
                }
        }

        private fun writeRecord(
            definition: LexiconDefinition,
            record: LexiconField,
        ) {
            imports += listOf(SERIALIZABLE, SERIAL_NAME, ENCODE_DEFAULT, OPT_IN_SERIALIZATION, RECORD_KEY)
            val name = className(document.id, definition.name)
            val recordKey = requireNotNull(definition.recordKey) { "Record ${document.id} has no valid key rule" }
            declarations +=
                buildString {
                    appendKdoc(definition.description ?: record.description, indent = "")
                    appendLine("@Serializable")
                    appendLine("public data class $name(")
                    propertyLines(name, record.properties).forEach(::appendLine)
                    appendLine("    @SerialName(\"\\\$type\")")
                    appendLine("    @EncodeDefault")
                    appendLine("    val lexiconType: String = TYPE,")
                    appendLine(") {")
                    appendLine("    public companion object {")
                    appendLine("        public const val TYPE: String = \"${document.id}\"")
                    appendLine("        public val KEY: LexiconRecordKey = ${recordKey.kotlin()}")
                    appendLine("    }")
                    appendLine("}")
                }
        }

        // A space type has no data of its own: apps need its NSID to address spaces, and its key
        // rule and collections to know what to expect in one.
        private fun writeSpace(
            definition: LexiconDefinition,
            space: LexiconSpace,
        ) {
            imports += RECORD_KEY
            val name = className(document.id, definition.name)
            val key = requireNotNull(space.key) { "Space ${document.id} has no valid key rule" }
            val collections = space.collections.orEmpty()
            val collectionList =
                if (collections.isEmpty()) "emptyList()" else "listOf(${collections.joinToString { it.asKotlinString() }})"
            declarations +=
                buildString {
                    appendKdoc(definition.description, indent = "")
                    appendLine("public object $name {")
                    appendLine("    /** The space type, the `{spaceType}` part of a space URI. */")
                    appendLine("    public const val TYPE: String = \"${document.id}\"")
                    space.name?.let { spaceName ->
                        appendLine()
                        appendLine("    /** What people see this kind of space called when an app asks for access to it. */")
                        appendLine("    public const val NAME: String = ${spaceName.asKotlinString()}")
                    }
                    appendLine()
                    appendLine("    /** The rule for the keys of spaces of this type. */")
                    appendLine("    public val KEY: LexiconRecordKey = ${key.kotlin()}")
                    appendLine()
                    appendLine("    /** The record collections apps should expect in a space of this type. */")
                    appendLine("    public val COLLECTIONS: List<String> = $collectionList")
                    appendLine("}")
                }
        }

        private fun writePermissionSet(definition: LexiconDefinition) {
            val name = className(document.id, definition.name)
            declarations +=
                buildString {
                    appendKdoc(definition.permissionSet?.detail ?: definition.description, indent = "")
                    appendLine("public object $name {")
                    appendLine("    /** The OAuth scope an app asks for to be granted this permission set. */")
                    appendLine("    public const val SCOPE: String = \"include:${document.id}\"")
                    appendLine("}")
                }
        }

        private fun propertyLines(
            owner: String,
            properties: Map<String, LexiconField>,
        ): List<String> =
            properties.entries
                .sortedBy { it.key }
                .flatMap { (name, field) ->
                    val type = kotlinType(field, unionName = owner + name.asPascalCase())
                    val nullable = if (!field.required || field.nullable) "?" else ""
                    val default = if (!field.required) " = null" else ""
                    val declaration = "    val ${name.asIdentifier()}: $type$nullable$default,"
                    buildList {
                        field.description?.let { add(kdoc(it, indent = "    ")) }
                        add(declaration)
                    }
                }

        private fun kotlinType(
            field: LexiconField,
            unionName: String,
        ): String =
            when (field.type) {
                LexiconType.STRING, LexiconType.TOKEN -> {
                    "String"
                }

                LexiconType.BOOLEAN -> {
                    "Boolean"
                }

                LexiconType.INTEGER -> {
                    "Long"
                }

                LexiconType.ARRAY -> {
                    val items = requireNotNull(field.items)
                    val itemType = kotlinType(items, unionName = "${unionName}Item")
                    "List<$itemType${if (items.nullable) "?" else ""}>"
                }

                LexiconType.BLOB -> {
                    runtimeType(BLOB)
                }

                LexiconType.CID_LINK -> {
                    runtimeType(CID_LINK)
                }

                LexiconType.BYTES -> {
                    runtimeType(BYTES)
                }

                LexiconType.REF -> {
                    typeName(requireNotNull(field.reference))
                }

                LexiconType.UNION -> {
                    writeUnion(unionName, field)
                }

                else -> {
                    imports += JSON_ELEMENT
                    "JsonElement"
                }
            }

        private fun runtimeType(qualifiedName: String): String {
            imports += qualifiedName
            return qualifiedName.substringAfterLast('.')
        }

        private fun writeUnion(
            name: String,
            field: LexiconField,
        ): String {
            imports +=
                listOf(SERIALIZABLE, K_SERIALIZER, SERIAL_DESCRIPTOR, ENCODER, DECODER, LEXICON_UNIONS) +
                if (field.closed) emptyList() else listOf(JSON_OBJECT)
            val members = unionMembers(field.refs)
            declarations +=
                buildString {
                    appendUnionInterface(name, field, members)
                    appendLine()
                    appendUnionSerializer(name, unionId = "${document.id}#$name", closed = field.closed, members = members)
                }
            return name
        }

        private fun StringBuilder.appendUnionInterface(
            name: String,
            field: LexiconField,
            members: List<UnionMember>,
        ) {
            appendKdoc(field.description, indent = "")
            appendLine("@Serializable(with = ${name}Serializer::class)")
            appendLine("public sealed interface $name {")
            // The value type is qualified: a member can share its name with the class it wraps.
            members.forEachIndexed { index, member ->
                if (index > 0) appendLine()
                appendLine("    public data class ${member.name}(")
                appendLine("        val value: ${member.qualifiedValueType},")
                appendLine("    ) : $name")
            }
            if (!field.closed) {
                if (members.isNotEmpty()) appendLine()
                appendLine("    /** A member this version of the schema doesn't define, kept as it was read. */")
                appendLine("    public data class Unknown(")
                appendLine("        val type: String,")
                appendLine("        val value: JsonObject,")
                appendLine("    ) : $name")
            }
            appendLine("}")
        }

        private fun StringBuilder.appendUnionSerializer(
            name: String,
            unionId: String,
            closed: Boolean,
            members: List<UnionMember>,
        ) {
            appendLine("public object ${name}Serializer : KSerializer<$name> {")
            appendLine("    override val descriptor: SerialDescriptor = LexiconUnions.descriptor(\"$unionId\")")
            appendLine()
            appendLine("    override fun serialize(")
            appendLine("        encoder: Encoder,")
            appendLine("        value: $name,")
            appendLine("    ) {")
            appendLine("        when (value) {")
            members.forEach { member ->
                appendLine("            is $name.${member.name} ->")
                appendLine("                LexiconUnions.encodeMember(")
                appendLine("                    encoder,")
                appendLine("                    \"${member.type}\",")
                appendLine("                    ${member.valueType}.serializer(),")
                appendLine("                    value.value,")
                appendLine("                )")
            }
            if (!closed) appendLine("            is $name.Unknown -> LexiconUnions.encodeUnknown(encoder, value.type, value.value)")
            appendLine("        }")
            appendLine("    }")
            appendLine()
            appendLine("    override fun deserialize(decoder: Decoder): $name {")
            appendLine("        val member = LexiconUnions.decodeMember(decoder)")
            appendLine("        return when (member.type) {")
            members.forEach { member ->
                appendLine("            \"${member.type}\" ->")
                appendLine("                $name.${member.name}(member.decode(${member.valueType}.serializer()))")
            }
            val fallback =
                if (closed) "throw LexiconUnions.notAllowed(\"$unionId\", member.type)" else "$name.Unknown(member.type, member.value)"
            appendLine("            else -> $fallback")
            appendLine("        }")
            appendLine("    }")
            appendLine("}")
        }

        private fun unionMembers(refs: List<LexiconReference>): List<UnionMember> {
            val shortNames = refs.map { it.memberName(qualified = false) }
            return refs.map { reference ->
                val shortName = reference.memberName(qualified = false)
                UnionMember(
                    name = if (shortNames.count { it == shortName } > 1) reference.memberName(qualified = true) else shortName,
                    type = reference.unionTypeId,
                    valueType = typeName(reference),
                    qualifiedValueType = qualifiedTypeName(reference),
                )
            }
        }

        private fun StringBuilder.appendKdoc(
            description: String?,
            indent: String,
        ) {
            description?.let { appendLine(kdoc(it, indent)) }
        }

        private fun kdoc(
            description: String,
            indent: String,
        ): String {
            val words =
                description
                    .replace("*/", "*&#47;")
                    .replace("/*", "&#47;*")
                    .split(Regex("\\s+"))
                    .filter(String::isNotEmpty)
            val single = "$indent/** ${words.joinToString(" ")} */"
            if (single.length <= KDOC_WIDTH) return single
            val lines = mutableListOf<String>()
            var line = StringBuilder()
            words.forEach { word ->
                if (line.isNotEmpty() && indent.length + 3 + line.length + 1 + word.length > KDOC_WIDTH) {
                    lines += line.toString()
                    line = StringBuilder()
                }
                if (line.isNotEmpty()) line.append(' ')
                line.append(word)
            }
            if (line.isNotEmpty()) lines += line.toString()
            return buildString {
                append("$indent/**")
                lines.forEach { append("\n$indent * $it") }
                append("\n$indent */")
            }
        }
    }

    private data class UnionMember(
        val name: String,
        val type: String,
        val valueType: String,
        val qualifiedValueType: String,
    )

    private fun className(
        documentId: Nsid,
        definitionName: String,
    ): String {
        val documentPrefix = documentId.documentPrefix()
        return when (definitionName) {
            MAIN_DEFINITION_NAME -> documentPrefix
            else -> documentPrefix + definitionName.asPascalCase()
        }
    }

    private fun LexiconReference.memberName(qualified: Boolean): String =
        when {
            definitionName == MAIN_DEFINITION_NAME || qualified -> className(documentId, definitionName)
            else -> definitionName.asPascalCase()
        }

    private fun LexiconRecordKey.kotlin(): String =
        when (this) {
            LexiconRecordKey.Tid -> "LexiconRecordKey.Tid"
            LexiconRecordKey.Nsid -> "LexiconRecordKey.Nsid"
            LexiconRecordKey.Any -> "LexiconRecordKey.Any"
            is LexiconRecordKey.Literal -> "LexiconRecordKey.Literal(\"$value\")"
        }

    private fun Nsid.documentPrefix(): String =
        toString()
            .substringAfterLast('.')
            .asPascalCase()

    private fun LexiconType.isObjectLike(): Boolean = this == LexiconType.OBJECT || this == LexiconType.PARAMS

    private fun String.asPascalCase(): String =
        split('.', '-', '_')
            .filter(String::isNotBlank)
            .joinToString(separator = "") { token ->
                token.replaceFirstChar { char -> char.uppercase() }
            }

    private fun String.asIdentifier(): String = if (this in kotlinKeywords) "`$this`" else this

    private fun String.asKotlinString(): String =
        buildString {
            append('"')
            this@asKotlinString.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

    private val aliasedTypes =
        setOf(
            LexiconType.STRING,
            LexiconType.INTEGER,
            LexiconType.BOOLEAN,
            LexiconType.BYTES,
            LexiconType.CID_LINK,
            LexiconType.BLOB,
            LexiconType.ARRAY,
            LexiconType.UNKNOWN,
        )

    private const val KDOC_WIDTH = 120
    private const val SERIALIZABLE = "kotlinx.serialization.Serializable"
    private const val SERIAL_NAME = "kotlinx.serialization.SerialName"
    private const val ENCODE_DEFAULT = "kotlinx.serialization.EncodeDefault"
    private const val OPT_IN_SERIALIZATION = "kotlinx.serialization.ExperimentalSerializationApi"
    private const val K_SERIALIZER = "kotlinx.serialization.KSerializer"
    private const val SERIAL_DESCRIPTOR = "kotlinx.serialization.descriptors.SerialDescriptor"
    private const val ENCODER = "kotlinx.serialization.encoding.Encoder"
    private const val DECODER = "kotlinx.serialization.encoding.Decoder"
    private const val JSON_ELEMENT = "kotlinx.serialization.json.JsonElement"
    private const val JSON_OBJECT = "kotlinx.serialization.json.JsonObject"
    private const val RECORD_KEY = "studio.hypertext.atproto.lexicon.LexiconRecordKey"
    private const val LEXICON_UNIONS = "studio.hypertext.atproto.lexicon.data.LexiconUnions"
    private const val BLOB = "studio.hypertext.atproto.lexicon.data.Blob"
    private const val CID_LINK = "studio.hypertext.atproto.lexicon.data.CidLink"
    private const val BYTES = "studio.hypertext.atproto.lexicon.data.Bytes"
}
