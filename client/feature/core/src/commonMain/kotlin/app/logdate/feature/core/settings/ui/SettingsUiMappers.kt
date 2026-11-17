package app.logdate.feature.core.settings.ui

import app.logdate.shared.model.CloudStorageQuota
import app.logdate.shared.model.LogDateAccount
import app.logdate.shared.model.user.UserData
import kotlin.time.Clock
import kotlin.uuid.Uuid

fun UserData?.orDefault(): UserData = this ?: UserData()

fun LogDateAccount?.orDefault(): LogDateAccount =
    this ?: LogDateAccount(
        id = Uuid.random(),
        username = "",
        displayName = "",
        bio = null,
        passkeyCredentialIds = emptyList(),
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

fun CloudStorageQuota?.orDefault(): CloudStorageQuota =
    this ?: CloudStorageQuota(
        totalBytes = 100_000_000_000L,
        usedBytes = 0L,
        categories = emptyList(),
    )
