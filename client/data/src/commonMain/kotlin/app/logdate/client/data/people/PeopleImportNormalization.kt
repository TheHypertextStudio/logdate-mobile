package app.logdate.client.data.people

import app.logdate.shared.model.PersonOrigin

internal fun normalizePersonName(name: String): String = name.trim()

internal fun normalizePersonAliases(
    aliases: Iterable<String>,
    primaryName: String? = null,
): List<String> {
    val normalizedPrimaryName = primaryName?.trim()?.lowercase()
    val seenAliases = linkedSetOf<String>()

    return buildList {
        aliases.forEach { alias ->
            val normalizedAlias = alias.trim()
            val foldedAlias = normalizedAlias.lowercase()

            if (normalizedAlias.isBlank()) {
                return@forEach
            }
            if (foldedAlias == normalizedPrimaryName) {
                return@forEach
            }
            if (!seenAliases.add(foldedAlias)) {
                return@forEach
            }

            add(normalizedAlias)
        }
    }
}

internal fun normalizeOptionalText(value: String?): String? = value?.trim()?.ifEmpty { null }

internal fun normalizePersonKey(name: String): String =
    name
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

internal fun mergeImportedPersonOrigin(
    existingOriginName: String,
    importedOrigin: PersonOrigin,
): PersonOrigin {
    val existingOrigin =
        runCatching { PersonOrigin.valueOf(existingOriginName) }
            .getOrDefault(PersonOrigin.MANUAL)

    return when {
        existingOrigin == PersonOrigin.CONTACT_FULL || importedOrigin == PersonOrigin.CONTACT_FULL -> {
            PersonOrigin.CONTACT_FULL
        }

        existingOrigin == PersonOrigin.CONTACT_SELECTED || importedOrigin == PersonOrigin.CONTACT_SELECTED -> {
            PersonOrigin.CONTACT_SELECTED
        }

        else -> {
            PersonOrigin.MANUAL
        }
    }
}
