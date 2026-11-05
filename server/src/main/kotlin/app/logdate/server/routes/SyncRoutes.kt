package app.logdate.server.routes

import app.logdate.server.auth.AccessTokenVerifier
import app.logdate.server.auth.AccountStorageModes
import app.logdate.server.auth.TokenService
import app.logdate.server.crypto.EncryptionService
import app.logdate.server.devices.DeviceSessionGuard
import app.logdate.server.diagnostics.DiagnosticReportService
import app.logdate.server.entitlements.EntitlementEnforcer
import app.logdate.server.logdate.LegacyObjectUploads
import app.logdate.server.logdate.LogDateBackupRepository
import app.logdate.server.logdate.LogDateBlobStorage
import app.logdate.server.logdate.LogDateCollectionsRepository
import app.logdate.server.logdate.LogDateMediaBlobRepository
import app.logdate.server.logdate.LogDateMomentsRepository
import app.logdate.server.ratelimit.SlidingWindowRateLimiter
import app.logdate.server.routes.sync.SyncRouteConfig
import app.logdate.server.routes.sync.diagnosticReportRoutes
import app.logdate.server.routes.sync.syncBackupRoutes
import app.logdate.server.routes.sync.syncCollectionRoutes
import app.logdate.server.routes.sync.syncMaintenanceRoutes
import app.logdate.server.routes.sync.syncMediaRoutes
import app.logdate.server.routes.sync.syncMomentRoutes
import app.logdate.server.routes.sync.syncStatusRoutes
import app.logdate.server.sync.MediaAccessPolicy
import app.logdate.server.sync.SyncMetricsRegistry
import app.logdate.server.transfer.TransferSlots
import io.ktor.server.routing.Route

/**
 * Sync routes with JWT authentication. All endpoints require a valid Bearer token and scope data
 * by user ID.
 *
 * The actual route handlers live in sibling files under `routes/sync/`:
 *
 *  - [syncStatusRoutes] — `/ops/sync/status`, `/ops/sync/metrics`, Prometheus export.
 *  - [syncCollectionRoutes] — `/contents`, `/journals`, `/associations`, `/drafts`.
 *  - [syncMomentRoutes] — `/moments`.
 *  - [syncMediaRoutes] — `/media` CRUD + binary download.
 *  - [syncBackupRoutes] — `/backups` CRUD + binary download.
 *  - [syncMaintenanceRoutes] — `/ops/sync/tombstones:purge`, `/ops/backups:purge`.
 *
 * The data routes (status, collections, moments, media) accept a linked device token through
 * [accessTokenVerifier]; every other route needs a full-access token. Without an explicit verifier,
 * linked device tokens are refused everywhere.
 *
 * This function is the single entry point so `Application.module` doesn't need to know about the
 * split and existing callers (including `testApplication` harnesses) stay untouched. Each
 * sub-route takes only the collaborators it uses as individual parameters; the one shared bundle
 * is [SyncRouteConfig], which carries passive policy (currently just [MediaAccessPolicy]).
 */
fun Route.syncRoutes(
    tokenService: TokenService? = null,
    mediaStorage: LogDateBlobStorage? = null,
    metrics: SyncMetricsRegistry,
    mediaAccessPolicy: MediaAccessPolicy = MediaAccessPolicy.fromEnvironment(),
    encryptionService: EncryptionService = EncryptionService.fromEnvironment(),
    collectionsRepository: LogDateCollectionsRepository,
    mediaBlobRepository: LogDateMediaBlobRepository,
    backupRepository: LogDateBackupRepository,
    entitlementEnforcer: EntitlementEnforcer? = null,
    rateLimiter: SlidingWindowRateLimiter? = SlidingWindowRateLimiter(),
    diagnosticReportService: DiagnosticReportService? = null,
    accessTokenVerifier: AccessTokenVerifier? = tokenService?.let { AccessTokenVerifier(it, DeviceSessionGuard.Disabled) },
    momentsRepository: LogDateMomentsRepository? = null,
    storageModes: AccountStorageModes? = null,
    transfers: TransferSlots = TransferSlots(),
    objectUploads: LegacyObjectUploads = LegacyObjectUploads.Direct,
    backupAccountAuth: LogDateAccountAuth? = null,
) {
    val config = SyncRouteConfig(mediaAccessPolicy = mediaAccessPolicy)
    syncStatusRoutes(tokenService, accessTokenVerifier, metrics, collectionsRepository)
    syncCollectionRoutes(accessTokenVerifier, storageModes, metrics, collectionsRepository)
    if (momentsRepository != null) syncMomentRoutes(accessTokenVerifier, storageModes, metrics, momentsRepository)
    syncMediaRoutes(
        accessTokenVerifier = accessTokenVerifier,
        storageModes = storageModes,
        mediaStorage = mediaStorage,
        metrics = metrics,
        encryptionService = encryptionService,
        mediaBlobRepository = mediaBlobRepository,
        entitlementEnforcer = entitlementEnforcer,
        rateLimiter = rateLimiter,
        config = config,
        objectUploads = objectUploads,
    )
    syncBackupRoutes(
        tokenService = tokenService,
        mediaStorage = mediaStorage,
        metrics = metrics,
        encryptionService = encryptionService,
        backupRepository = backupRepository,
        entitlementEnforcer = entitlementEnforcer,
        rateLimiter = rateLimiter,
        config = config,
        transfers = transfers,
        objectUploads = objectUploads,
        accountAuth = backupAccountAuth,
    )
    syncMaintenanceRoutes(tokenService, mediaStorage, metrics, collectionsRepository, backupRepository)
    diagnosticReportRoutes(tokenService, diagnosticReportService)
}
