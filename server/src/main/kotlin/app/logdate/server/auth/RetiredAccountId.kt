package app.logdate.server.auth

import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Stable v1 lookup for deleted IDs, without retaining their raw account identifier. */
@OptIn(ExperimentalUuidApi::class)
internal fun retiredAccountIdDigest(accountId: Uuid): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest("logdate:retired-account:v1:$accountId".encodeToByteArray())
        .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
