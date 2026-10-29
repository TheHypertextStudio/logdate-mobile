@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package app.logdate.server.routes

import app.logdate.server.audit.AuditKey
import app.logdate.server.audit.formatAuditLog
import io.github.aakira.napier.Napier
import io.ktor.server.application.ApplicationCall
import kotlin.uuid.Uuid

internal fun ApplicationCall.recordAuthSuccess(
    category: String,
    accountId: Uuid,
    credentialId: String? = null,
) {
    val fields =
        mutableMapOf(
            AuditKey.ACCOUNT_ID to accountId.toString(),
            AuditKey.IP_HASH to hashRemoteIp(),
            AuditKey.USER_AGENT_HASH to hashUserAgent(),
        )
    if (credentialId != null) fields[AuditKey.CREDENTIAL_ID_HASH] = credentialId.sha256()
    Napier.i(formatAuditLog(category, fields))
}
