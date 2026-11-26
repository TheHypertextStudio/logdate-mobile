package app.logdate.client.sync.datalayer.v2

/**
 * Capability names, schema numbers and limits of the second watch and phone protocol.
 *
 * Version 2 runs beside today's protocol. A device uses it only when its Labs switch is on, the other
 * device advertises the matching capability, and that device lists the feature in its state document
 * ([WearProtocolGate]). Adding a value to any enum in this package needs a schema bump: a device that
 * meets a value it does not know reports the message as malformed.
 */
object WearProtocol {
    /** Added by the phone with `CapabilityClient.addLocalCapability` while its Labs switch is on. */
    const val PHONE_CAPABILITY_V2 = "logdate_phone_sync_v2"

    /** Added by the watch while the switch the phone sent it is on. */
    const val WATCH_CAPABILITY_V2 = "logdate_watch_sync_v2"

    const val ENVELOPE_SCHEMA = 1
    const val STATE_SCHEMA = 1
    const val RECEIPTS_SCHEMA = 1
    const val LINK_SCHEMA = 1
    const val LIVE_SCHEMA = 1
    const val WINDOW_SCHEMA = 2

    /** Current peer protocol generation spoken by this build. */
    const val PROTOCOL_VERSION = 2

    /** Oldest peer protocol generation this build still interoperates with. */
    const val MIN_COMPATIBLE_PROTOCOL_VERSION = 2

    /** A state document is one small data item; anything larger belongs in its own path. */
    const val MAX_STATE_BYTES = 8 * 1024

    /** A record body larger than this goes over a channel and is referenced by its SHA-256. */
    const val MAX_INLINE_BODY_BYTES = 90 * 1024

    const val LINK_OFFER_TTL_MS = 60_000L

    /** How long the single-use code in a link bundle can be redeemed. */
    const val LINK_CODE_TTL_MS = 120_000L
}

/** The names a device lists in its state document for each version 2 feature it supports. */
object WearFeatures {
    const val RECEIPTS = "receipts.v1"
    const val OPERATION_RECEIPTS = "receipts.operations.v1"
    const val WINDOW = "window.v1"
    const val MEDIA_RANGE = "media.range.v1"
    const val MOMENTS = "moments.v1"
    const val LIVE = "live.v1"
    const val REMINDERS = "reminders.v1"
    const val REMINDER_SUPPLEMENTS = "reminders.supplements.v1"

    /** The device can read the reminder triggers for a walk or trip that just ended. */
    const val ACTIVITY_REMINDERS = "reminders.activity.v1"
    const val LINK_CLOUD = "link.cloud.v1"
    const val PHONE_CAMERA = "phone.camera.v1"
    const val PHONE_PLAYBACK = "phone.playback.v1"
    const val PHONE_SETTINGS_LINK = "phone.settingsLink.v1"
    const val PREFS = "prefs.v1"

    val ALL: Set<String> =
        setOf(
            RECEIPTS,
            OPERATION_RECEIPTS,
            WINDOW,
            MEDIA_RANGE,
            MOMENTS,
            LIVE,
            REMINDERS,
            REMINDER_SUPPLEMENTS,
            ACTIVITY_REMINDERS,
            LINK_CLOUD,
            PHONE_CAMERA,
            PHONE_PLAYBACK,
            PHONE_SETTINGS_LINK,
            PREFS,
        )
}
