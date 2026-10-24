package studio.hypertext.atproto.lexicon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import studio.hypertext.atproto.syntax.Did
import studio.hypertext.atproto.syntax.LanguageTag
import studio.hypertext.atproto.syntax.Nsid
import studio.hypertext.atproto.syntax.RecordKey

/**
 * Validation result for a parsed lexicon document.
 */
public data class LexiconValidationResult(
    val issues: List<LexiconValidationIssue>,
) {
    /**
     * Whether the document passed validation with no issues.
     */
    public val isValid: Boolean
        get() = issues.isEmpty()
}

/**
 * Single lexicon validation issue.
 */
public data class LexiconValidationIssue(
    val path: String,
    val message: String,
)

/**
 * Validates parsed lexicon documents against the runtime's supported rules.
 */
public object LexiconValidator {
    /**
     * Validates [document] with optional cross-document [registry] lookups.
     */
    public fun validate(
        document: LexiconDocument,
        registry: LexiconRegistry = LexiconRegistry(),
    ): LexiconValidationResult {
        val issues = mutableListOf<LexiconValidationIssue>()
        if (document.lexicon != 1) {
            issues += LexiconValidationIssue(path = document.id.toString(), message = "Only lexicon version 1 is supported")
        }
        if (document.definitions.isEmpty()) {
            issues += LexiconValidationIssue(path = "${document.id}#defs", message = "Lexicon must define at least one definition")
        }

        val checker = Checker(document, registry, issues)
        document.definitions.values.forEach(checker::checkDefinition)
        return LexiconValidationResult(issues = issues)
    }

    private class Checker(
        private val owner: LexiconDocument,
        private val registry: LexiconRegistry,
        private val issues: MutableList<LexiconValidationIssue>,
    ) {
        fun checkDefinition(definition: LexiconDefinition) {
            val path = "${owner.id}#${definition.name}"
            if (definition.type.isPrimary && definition.name != MAIN_DEFINITION_NAME) {
                report(path, "${definition.type.lexiconName()} definitions must be named main")
            }
            if (definition.type == LexiconType.RECORD) checkRecord(path, definition)
            definition.space?.let { checkSpace(path, it) }
            definition.permissionSet?.let { checkPermissionSet(path, it) }
            checkField(path, definition.asField, requiredNames = definition.required)
            definition.parameters?.let { checkField("$path.parameters", it) }
            definition.input?.schema?.let { checkField("$path.input", it) }
            definition.output?.schema?.let { checkField("$path.output", it) }
        }

        private fun checkRecord(
            path: String,
            definition: LexiconDefinition,
        ) {
            val keyRule = definition.keyRule
            when {
                keyRule == null -> report(path, "Records must declare a key")
                definition.recordKey == null -> report(path, "Unknown record key rule '$keyRule'")
            }
            val record = definition.record
            if (record?.type != LexiconType.OBJECT) {
                report(path, "A record's record must be an object")
                return
            }
            checkField(path, record, requiredNames = record.properties.filterValues { it.required }.keys)
        }

        // Follows the space type declaration in the AT Protocol Spaces proposal.
        private fun checkSpace(
            path: String,
            space: LexiconSpace,
        ) {
            val name = space.name
            when {
                name.isNullOrEmpty() -> report(path, "A space declaration needs a name")
                name.length > MAX_SPACE_NAME_LENGTH -> report(path, "The name is longer than $MAX_SPACE_NAME_LENGTH characters")
            }
            checkLanguages(path, "name:lang", space.nameLang)
            val keyRule = space.keyRule
            when {
                keyRule == null -> report(path, "A space declaration needs a key rule")
                !isKeyRule(keyRule) -> report(path, "'$keyRule' isn't a key rule")
            }
            val collections = space.collections ?: return report(path, "A space declaration needs a collections list")
            collections.forEach { collection ->
                when {
                    collection == WILDCARD -> report(path, "Collections can't include the * wildcard")
                    !Nsid.isValid(collection) -> report(path, "Collection '$collection' isn't a valid NSID")
                }
            }
            collections.duplicates().forEach { report(path, "Collection $it is listed twice") }
        }

        // Follows https://atproto.com/specs/permission, plus the space resource from the Spaces proposal.
        private fun checkPermissionSet(
            path: String,
            set: LexiconPermissionSet,
        ) {
            checkLanguages(path, "title:lang", set.titleLang)
            checkLanguages(path, "detail:lang", set.detailLang)
            val permissions = set.permissions ?: return report(path, "A permission set needs a permissions list")
            val group = owner.id.toString().substringBeforeLast('.')
            permissions.forEachIndexed { index, permission ->
                val at = "$path.permissions[$index]"
                if (permission.type != PERMISSION) return@forEachIndexed report(at, "Every entry needs \"type\": \"permission\"")
                val resource = permission.resource
                val shapes = PARAMETER_SHAPES[resource]
                val wellFormed = shapes?.let { checkShapes(at, resource, permission, it) }.orEmpty()
                when (resource) {
                    "repo" -> {
                        checkRepoPermission(at, permission, group, wellFormed)
                    }

                    "rpc" -> {
                        checkRpcPermission(at, permission, group, wellFormed)
                    }

                    "space" -> {
                        checkSpacePermission(at, permission, group, wellFormed)
                    }

                    "blob", "account", "identity" -> {
                        report(at, "$resource permissions can't be in a permission set; apps ask for them directly")
                    }

                    else -> {
                        report(at, "'$resource' isn't a resource a permission set can grant")
                    }
                }
            }
        }

        // An authorization server silently ignores an entry it can't read, such as one with a
        // misspelled parameter or a single value where a list belongs, so each is reported here.
        // Returns the parameters that are present and well formed.
        private fun checkShapes(
            path: String,
            resource: String?,
            permission: LexiconPermission,
            shapes: Map<String, ParameterShape>,
        ): Set<String> =
            permission.parameters
                .filter { (name, value) ->
                    val shape = shapes[name]
                    val problem =
                        if (shape == null) {
                            "'$name' isn't a parameter of $resource permissions"
                        } else {
                            shapeProblem(name, shape, value)
                        }
                    problem?.let { report(path, it) }
                    problem == null
                }.keys

        private fun shapeProblem(
            name: String,
            shape: ParameterShape,
            value: JsonElement,
        ): String? =
            when (shape) {
                ParameterShape.LIST -> {
                    when {
                        value !is JsonArray -> "$name must be a list, even for one value"
                        value.isEmpty() -> "$name can't be an empty list"
                        value.any { (it as? JsonPrimitive)?.isString != true } -> "$name must list only strings"
                        else -> null
                    }
                }

                ParameterShape.STRING -> {
                    when {
                        value is JsonArray -> "$name must be a single value, not a list"
                        (value as? JsonPrimitive)?.isString != true -> "$name must be a string"
                        else -> null
                    }
                }

                ParameterShape.BOOLEAN -> {
                    if (value.asBoolean() == null) "$name must be true or false" else null
                }
            }

        private fun JsonElement.asBoolean(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull

        private fun checkRepoPermission(
            path: String,
            permission: LexiconPermission,
            group: String,
            wellFormed: Set<String>,
        ) {
            if (!permission.has("collection")) report(path, "A repo permission needs at least one collection")
            if ("collection" in wellFormed) permission.values("collection").forEach { checkOwnNsid(path, it, "collection", group) }
            if ("action" in wellFormed) checkAllowedValues(path, permission, "action", RECORD_ACTIONS)
        }

        private fun checkRpcPermission(
            path: String,
            permission: LexiconPermission,
            group: String,
            wellFormed: Set<String>,
        ) {
            if (!permission.has("lxm")) report(path, "An rpc permission needs at least one lxm")
            if ("lxm" in wellFormed) permission.values("lxm").forEach { checkOwnNsid(path, it, "lxm", group) }
            val inherits = permission.boolean("inheritAud") == true
            val hasAudience = permission.has("aud")
            when {
                inherits && hasAudience -> report(path, "An rpc permission can't set aud and inheritAud together")
                !inherits && !hasAudience -> report(path, "An rpc permission needs aud: *, or inheritAud: true")
            }
            // A set can't name a service: the audience comes from the app's include scope instead.
            val audience = permission.string("aud")
            if ("aud" in wellFormed && audience != WILDCARD) {
                report(path, "aud '$audience' can't name a service in a permission set; use inheritAud: true")
            }
        }

        private fun checkSpacePermission(
            path: String,
            permission: LexiconPermission,
            group: String,
            wellFormed: Set<String>,
        ) {
            val spaceType = permission.string("spaceType")
            when {
                !permission.has("spaceType") -> report(path, "A space permission needs a spaceType")
                spaceType == WILDCARD -> report(path, "A permission set must name a space type, not *")
                spaceType != null && "spaceType" in wellFormed -> checkOwnNsid(path, spaceType, "spaceType", group)
            }
            val authority = permission.string("authority")
            val validAuthority = authority == "self" || authority == WILDCARD || Did.isValid(authority.orEmpty())
            if ("authority" in wellFormed && !validAuthority) report(path, "authority '$authority' isn't self, * or a DID")
            val skey = permission.string("skey")
            if ("skey" in wellFormed && skey != WILDCARD && !RecordKey.isValid(skey.orEmpty())) {
                report(path, "skey '$skey' isn't * or a valid space key")
            }
            if ("collection" in wellFormed) {
                permission
                    .values("collection")
                    .filterNot { it == WILDCARD || Nsid.isValid(it) }
                    .forEach { report(path, "collection '$it' isn't * or a valid NSID") }
            }
            if ("action" in wellFormed) checkAllowedValues(path, permission, "action", SPACE_ACTIONS)
            if ("manage" in wellFormed) checkAllowedValues(path, permission, "manage", RECORD_ACTIONS)
        }

        // A set may only grant within its own NSID group, or the groups below it.
        private fun checkOwnNsid(
            path: String,
            nsid: String,
            parameter: String,
            group: String,
        ) {
            when {
                nsid == WILDCARD -> report(path, "$parameter can't be * in a permission set")
                !Nsid.isValid(nsid) -> report(path, "$parameter '$nsid' isn't a valid NSID")
                !nsid.startsWith("$group.") -> report(path, "$parameter $nsid is outside this set's namespace, $group.*")
            }
        }

        private fun checkAllowedValues(
            path: String,
            permission: LexiconPermission,
            parameter: String,
            allowed: List<String>,
        ) {
            val values = permission.values(parameter)
            values.filterNot(allowed::contains).forEach { value ->
                report(path, "$parameter '$value' isn't one of ${allowed.joinToString()}")
            }
            values.duplicates().forEach { report(path, "$parameter $it is listed twice") }
        }

        private fun checkLanguages(
            path: String,
            field: String,
            translations: Map<String, String>,
        ) {
            translations.keys.filterNot(LanguageTag::isValid).forEach { language ->
                report(path, "$field key '$language' isn't a language tag")
            }
        }

        private fun checkField(
            path: String,
            field: LexiconField,
            requiredNames: Set<String> = emptySet(),
        ) {
            when (field.type) {
                LexiconType.REF -> {
                    checkRef(path, field.reference)
                }

                LexiconType.UNION -> {
                    checkUnion(path, field)
                }

                LexiconType.BLOB -> {
                    checkBlob(path, field)
                }

                LexiconType.BYTES -> {
                    checkLengths(path, field)
                }

                LexiconType.UNSUPPORTED -> {
                    report(path, "Unsupported lexicon type '${field.unsupportedType}'")
                }

                LexiconType.ARRAY -> {
                    field.items?.let { checkField("$path[]", it) }
                        ?: report(path, "array definitions must declare items")
                }

                LexiconType.OBJECT, LexiconType.PARAMS -> {
                    requiredNames.filterNot(field.properties::containsKey).forEach { name ->
                        report(path, "Required field '$name' is missing from properties")
                    }
                    field.properties.forEach { (name, property) -> checkField("$path.$name", property) }
                }

                else -> {
                    Unit
                }
            }
        }

        private fun checkRef(
            path: String,
            reference: LexiconReference?,
        ) {
            if (reference == null) {
                report(path, "ref definitions must include a ref target")
                return
            }
            if (resolve(reference) == null) report(path, "Unresolved ref target ${reference.label}")
        }

        private fun checkUnion(
            path: String,
            field: LexiconField,
        ) {
            if (field.closed && field.refs.isEmpty()) report(path, "A closed union must list at least one ref")
            field.refs.forEach { reference ->
                val target = resolve(reference)
                when {
                    target == null -> {
                        report(path, "Unresolved ref target ${reference.label}")
                    }

                    target.type != LexiconType.OBJECT && target.type != LexiconType.RECORD -> {
                        report(path, "Union member ${reference.label} must be an object or a record")
                    }
                }
            }
        }

        private fun checkBlob(
            path: String,
            field: LexiconField,
        ) {
            field.accept.filterNot(::isAcceptableMimePattern).forEach { entry ->
                report(path, "Blob accept entry '$entry' must be a MIME type, type/* or */*")
            }
            if ((field.maxSize ?: 0) < 0) report(path, "maxSize must not be negative")
        }

        private fun checkLengths(
            path: String,
            field: LexiconField,
        ) {
            val minLength = field.minLength
            val maxLength = field.maxLength
            if ((minLength ?: 0) < 0) report(path, "minLength must not be negative")
            if ((maxLength ?: 0) < 0) report(path, "maxLength must not be negative")
            if (minLength != null && maxLength != null && minLength > maxLength) {
                report(path, "minLength $minLength is greater than maxLength $maxLength")
            }
        }

        private fun resolve(reference: LexiconReference): LexiconDefinition? =
            when (reference.documentId) {
                owner.id -> owner.definitions[reference.definitionName]
                else -> registry.resolve(reference)
            }

        private fun report(
            path: String,
            message: String,
        ) {
            issues += LexiconValidationIssue(path = path, message = message)
        }
    }

    private fun LexiconType.lexiconName(): String = name.lowercase().replace('_', '-')

    private fun isKeyRule(rule: String): Boolean =
        when (val key = LexiconRecordKey.parse(rule)) {
            null -> false
            is LexiconRecordKey.Literal -> RecordKey.isValid(key.value)
            else -> true
        }

    private fun List<String>.duplicates(): List<String> =
        groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
            .toList()

    private const val WILDCARD = "*"
    private const val PERMISSION = "permission"
    private const val MAX_SPACE_NAME_LENGTH = 64
    private val RECORD_ACTIONS = listOf("create", "update", "delete")
    private val SPACE_ACTIONS = listOf("read_self", "read", "create", "update", "delete")

    private enum class ParameterShape { LIST, STRING, BOOLEAN }

    // The parameters each resource takes in a permission set, and their shapes.
    private val PARAMETER_SHAPES =
        mapOf(
            "repo" to mapOf("collection" to ParameterShape.LIST, "action" to ParameterShape.LIST),
            "rpc" to mapOf("lxm" to ParameterShape.LIST, "aud" to ParameterShape.STRING, "inheritAud" to ParameterShape.BOOLEAN),
            "space" to
                mapOf(
                    "spaceType" to ParameterShape.STRING,
                    "authority" to ParameterShape.STRING,
                    "skey" to ParameterShape.STRING,
                    "collection" to ParameterShape.LIST,
                    "action" to ParameterShape.LIST,
                    "manage" to ParameterShape.LIST,
                ),
        )

    // A blob's accept list holds MIME types, globs over a whole subtype, or the any-type glob.
    private fun isAcceptableMimePattern(entry: String): Boolean = entry == "*/*" || mimePattern.matches(entry)

    private val mimePattern = Regex("^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*/([A-Za-z0-9][A-Za-z0-9!#$&^_.+-]*|\\*)$")
}
