package app.logdate.client.domain.places

import kotlin.uuid.Uuid

/**
 * Stable identifiers for [PlaceResolutionResult] values.
 *
 * The on-device pipelines (event inference, ambient prompts) need a deterministic key to
 * look up familiarity records and dedupe places across runs. This helper centralizes the
 * key derivation so two callers can't drift on the encoding — a silent drift would mean a
 * "familiar" place suddenly becomes unfamiliar from one feature's perspective.
 *
 * Returns `null` for [PlaceResolutionResult.CoarseLocation] and
 * [PlaceResolutionResult.UnknownLocation] — both indicate the resolver had no semantic
 * anchor for the location, so callers should treat the stop as unattributable rather than
 * persist a key that means nothing.
 */
fun PlaceResolutionResult.toPlaceKey(): String? =
    when (this) {
        is PlaceResolutionResult.UserDefinedPlace -> {
            "user:${place.uid}"
        }

        is PlaceResolutionResult.ExternalSuggestion -> {
            suggestion.externalId?.let { externalId -> "external:$externalId" }
                ?: "external:${suggestion.name.lowercase()}:${suggestion.latitude}:${suggestion.longitude}"
        }

        is PlaceResolutionResult.CoarseLocation, is PlaceResolutionResult.UnknownLocation -> {
            null
        }
    }

/**
 * Display name for the resolved place, suitable for showing to the user verbatim.
 *
 * Falls back to the reverse-geocoded locality (or any other non-null address component)
 * for [PlaceResolutionResult.CoarseLocation], so a stop in a city we don't have a place
 * for still gets a recognizable label like "Austin" instead of an empty fallback. Returns
 * `null` only when the resolver gave up entirely.
 */
fun PlaceResolutionResult.toDisplayName(): String? =
    when (this) {
        is PlaceResolutionResult.UserDefinedPlace -> {
            place.name
        }

        is PlaceResolutionResult.ExternalSuggestion -> {
            suggestion.name
        }

        is PlaceResolutionResult.CoarseLocation -> {
            address.locality
                ?: address.subLocality
                ?: address.thoroughfare
                ?: address.adminArea
        }

        is PlaceResolutionResult.UnknownLocation -> {
            null
        }
    }

/**
 * The UID of the underlying user-defined [app.logdate.shared.model.Place], if the
 * resolution landed on one. Used by features that need to persist a foreign key to a
 * user place (e.g. event creation), and `null` for every other branch so callers can
 * keep the link optional.
 */
fun PlaceResolutionResult.toUserPlaceId(): Uuid? = (this as? PlaceResolutionResult.UserDefinedPlace)?.place?.uid
