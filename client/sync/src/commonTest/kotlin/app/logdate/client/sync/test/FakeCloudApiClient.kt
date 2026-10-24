package app.logdate.client.sync.test

import app.logdate.client.sync.cloud.AssociationChangesResponse
import app.logdate.client.sync.cloud.AssociationDeleteRequest
import app.logdate.client.sync.cloud.AssociationUploadRequest
import app.logdate.client.sync.cloud.AssociationUploadResponse
import app.logdate.client.sync.cloud.BackupDownloadResponse
import app.logdate.client.sync.cloud.BackupInfoResponse
import app.logdate.client.sync.cloud.BackupListResponse
import app.logdate.client.sync.cloud.BackupUploadRequest
import app.logdate.client.sync.cloud.BackupUploadResponse
import app.logdate.client.sync.cloud.CheckUsernameAvailabilityResponse
import app.logdate.client.sync.cloud.CloudApiClient
import app.logdate.client.sync.cloud.ConditionalCreateResult
import app.logdate.client.sync.cloud.ContentChangesResponse
import app.logdate.client.sync.cloud.ContentState
import app.logdate.client.sync.cloud.ContentUpdateRequest
import app.logdate.client.sync.cloud.ContentUpdateResponse
import app.logdate.client.sync.cloud.ContentUploadRequest
import app.logdate.client.sync.cloud.ContentUploadResponse
import app.logdate.client.sync.cloud.DraftChangesResponse
import app.logdate.client.sync.cloud.DraftUploadRequest
import app.logdate.client.sync.cloud.DraftUploadResponse
import app.logdate.client.sync.cloud.JournalChangesResponse
import app.logdate.client.sync.cloud.JournalUpdateRequest
import app.logdate.client.sync.cloud.JournalUpdateResponse
import app.logdate.client.sync.cloud.JournalUploadRequest
import app.logdate.client.sync.cloud.JournalUploadResponse
import app.logdate.client.sync.cloud.MediaDownloadResponse
import app.logdate.client.sync.cloud.MediaUpload
import app.logdate.client.sync.cloud.MediaUploadRequest
import app.logdate.client.sync.cloud.MediaUploadResponse
import app.logdate.shared.model.BeginAccountCreationRequest
import app.logdate.shared.model.BeginAccountCreationResponse
import app.logdate.shared.model.CompleteAccountCreationRequest
import app.logdate.shared.model.CompleteAccountCreationResponse
import app.logdate.shared.model.LogDateAccount
import kotlin.time.Clock

/**
 * Fake CloudApiClient for sync testing.
 * All responses are successful by default; configure failures via [configureContentSyncFailure].
 */
open class FakeCloudApiClient : CloudApiClient {
    var uploadContentResponse: Result<ContentUploadResponse> =
        Result.success(ContentUploadResponse("test-id", 1, Clock.System.now().toEpochMilliseconds()))

    var updateContentResponse: Result<ContentUpdateResponse> =
        Result.success(ContentUpdateResponse("test-id", 1, Clock.System.now().toEpochMilliseconds()))

    var deleteContentResponse: Result<Unit> = Result.success(Unit)

    var getContentChangesResponse: Result<ContentChangesResponse> =
        Result.success(ContentChangesResponse(emptyList(), emptyList(), Clock.System.now().toEpochMilliseconds()))

    var uploadJournalResponse: Result<JournalUploadResponse> =
        Result.success(JournalUploadResponse("test-id", 1, Clock.System.now().toEpochMilliseconds()))

    var updateJournalResponse: Result<JournalUpdateResponse> =
        Result.success(JournalUpdateResponse("test-id", 1, Clock.System.now().toEpochMilliseconds()))

    var deleteJournalResponse: Result<Unit> = Result.success(Unit)

    var getJournalChangesResponse: Result<JournalChangesResponse> =
        Result.success(JournalChangesResponse(emptyList(), emptyList(), Clock.System.now().toEpochMilliseconds()))

    var uploadAssociationsResponse: Result<AssociationUploadResponse> =
        Result.success(AssociationUploadResponse(0, Clock.System.now().toEpochMilliseconds()))

    var getAssociationChangesResponse: Result<AssociationChangesResponse> =
        Result.success(AssociationChangesResponse(emptyList(), emptyList(), Clock.System.now().toEpochMilliseconds()))

    var deleteAssociationsResponse: Result<Unit> = Result.success(Unit)

    var createContentIfAbsentResponse: Result<ConditionalCreateResult> =
        Result.success(ConditionalCreateResult.Created(1, Clock.System.now()))

    var createJournalIfAbsentResponse: Result<ConditionalCreateResult> =
        Result.success(ConditionalCreateResult.Created(1, Clock.System.now()))

    var uploadAssociationsIfAbsentResponse: Result<AssociationUploadResponse> =
        Result.success(AssociationUploadResponse(0, Clock.System.now().toEpochMilliseconds()))

    val createContentIfAbsentCalls = mutableListOf<ContentUploadRequest>()
    var contentStateResponse: Result<ContentState> = Result.success(ContentState.Absent)

    val contentStateCalls = mutableListOf<String>()

    var uploadMediaResponse: Result<MediaUploadResponse> =
        Result.success(MediaUploadResponse("content-id", "media-id", "https://example.com/media", Clock.System.now().toEpochMilliseconds()))

    var downloadMediaResponse: Result<MediaDownloadResponse> =
        Result.success(MediaDownloadResponse("content-id", "test.jpg", "image/jpeg", 1024, byteArrayOf(), "https://example.com/test.jpg"))

    var uploadDraftResponse: Result<DraftUploadResponse>? = null

    var getDraftChangesResponse: Result<DraftChangesResponse> =
        Result.success(DraftChangesResponse(drafts = emptyList()))

    var deleteDraftResponse: Result<Unit> = Result.success(Unit)

    var uploadBackupResponse: Result<BackupUploadResponse> =
        Result.success(BackupUploadResponse("backup-id", 1L, 0L))
    var listBackupsResponse: Result<BackupListResponse> = Result.success(BackupListResponse(emptyList()))
    var downloadBackupResponse: Result<BackupDownloadResponse> =
        Result.success(
            BackupDownloadResponse(
                BackupInfoResponse("backup-id", "device-id", "{}", 1L, 0L, ""),
                byteArrayOf(),
            ),
        )
    var deleteBackupResponse: Result<Unit> = Result.success(Unit)

    val methodCalls = mutableListOf<String>()
    val uploadContentCalls = mutableListOf<Pair<String, ContentUploadRequest>>()
    val updateContentCalls = mutableListOf<Triple<String, String, ContentUpdateRequest>>()
    val deleteContentCalls = mutableListOf<Pair<String, String>>()
    val getContentChangesCalls = mutableListOf<Pair<String, Long>>()
    val uploadMediaCalls = mutableListOf<Pair<String, MediaUploadRequest>>()
    val downloadMediaCalls = mutableListOf<Pair<String, String>>()
    val uploadDraftCalls = mutableListOf<Pair<String, DraftUploadRequest>>()
    val deleteDraftCalls = mutableListOf<Pair<String, String>>()

    // Account methods - stubs that throw for sync-only testing
    override suspend fun checkUsernameAvailability(username: String): Result<CheckUsernameAvailabilityResponse> {
        methodCalls.add("checkUsernameAvailability")
        return Result.failure(NotImplementedError("Use for sync testing only"))
    }

    override suspend fun beginAccountCreation(request: BeginAccountCreationRequest): Result<BeginAccountCreationResponse> {
        methodCalls.add("beginAccountCreation")
        return Result.failure(NotImplementedError("Use for sync testing only"))
    }

    override suspend fun completeAccountCreation(request: CompleteAccountCreationRequest): Result<CompleteAccountCreationResponse> {
        methodCalls.add("completeAccountCreation")
        return Result.failure(NotImplementedError("Use for sync testing only"))
    }

    override suspend fun refreshAccessToken(refreshToken: String): Result<String> {
        methodCalls.add("refreshAccessToken")
        return Result.success("new-access-token")
    }

    override suspend fun getAccountInfo(accessToken: String): Result<LogDateAccount> {
        methodCalls.add("getAccountInfo")
        return Result.failure(NotImplementedError("Use for sync testing only"))
    }

    // Content sync methods
    override suspend fun uploadContent(
        accessToken: String,
        content: ContentUploadRequest,
    ): Result<ContentUploadResponse> {
        methodCalls.add("uploadContent")
        uploadContentCalls.add(accessToken to content)
        return uploadContentResponse
    }

    override suspend fun updateContent(
        accessToken: String,
        contentId: String,
        content: ContentUpdateRequest,
    ): Result<ContentUpdateResponse> {
        methodCalls.add("updateContent")
        updateContentCalls.add(Triple(accessToken, contentId, content))
        return updateContentResponse
    }

    override suspend fun deleteContent(
        accessToken: String,
        contentId: String,
    ): Result<Unit> {
        methodCalls.add("deleteContent")
        deleteContentCalls.add(accessToken to contentId)
        return deleteContentResponse
    }

    override suspend fun getContentChanges(
        accessToken: String,
        since: Long,
        limit: Int?,
    ): Result<ContentChangesResponse> {
        methodCalls.add("getContentChanges")
        getContentChangesCalls.add(accessToken to since)
        return getContentChangesResponse
    }

    // Journal sync methods
    override suspend fun uploadJournal(
        accessToken: String,
        journal: JournalUploadRequest,
    ): Result<JournalUploadResponse> {
        methodCalls.add("uploadJournal")
        return uploadJournalResponse
    }

    override suspend fun updateJournal(
        accessToken: String,
        journalId: String,
        journal: JournalUpdateRequest,
    ): Result<JournalUpdateResponse> {
        methodCalls.add("updateJournal")
        return updateJournalResponse
    }

    override suspend fun deleteJournal(
        accessToken: String,
        journalId: String,
    ): Result<Unit> {
        methodCalls.add("deleteJournal")
        return deleteJournalResponse
    }

    override suspend fun getJournalChanges(
        accessToken: String,
        since: Long,
        limit: Int?,
    ): Result<JournalChangesResponse> {
        methodCalls.add("getJournalChanges")
        return getJournalChangesResponse
    }

    // Association sync methods
    override suspend fun uploadAssociations(
        accessToken: String,
        associations: AssociationUploadRequest,
    ): Result<AssociationUploadResponse> {
        methodCalls.add("uploadAssociations")
        return uploadAssociationsResponse
    }

    override suspend fun getAssociationChanges(
        accessToken: String,
        since: Long,
        limit: Int?,
    ): Result<AssociationChangesResponse> {
        methodCalls.add("getAssociationChanges")
        return getAssociationChangesResponse
    }

    override suspend fun deleteAssociations(
        accessToken: String,
        associations: AssociationDeleteRequest,
    ): Result<Unit> {
        methodCalls.add("deleteAssociations")
        return deleteAssociationsResponse
    }

    override suspend fun createContentIfAbsent(
        accessToken: String,
        content: ContentUploadRequest,
    ): Result<ConditionalCreateResult> {
        methodCalls.add("createContentIfAbsent")
        createContentIfAbsentCalls.add(content)
        return createContentIfAbsentResponse
    }

    override suspend fun contentState(
        accessToken: String,
        contentId: String,
    ): Result<ContentState> {
        methodCalls.add("contentState")
        contentStateCalls.add(contentId)
        return contentStateResponse
    }

    override suspend fun createJournalIfAbsent(
        accessToken: String,
        journal: JournalUploadRequest,
    ): Result<ConditionalCreateResult> {
        methodCalls.add("createJournalIfAbsent")
        return createJournalIfAbsentResponse
    }

    override suspend fun uploadAssociationsIfAbsent(
        accessToken: String,
        associations: AssociationUploadRequest,
    ): Result<AssociationUploadResponse> {
        methodCalls.add("uploadAssociationsIfAbsent")
        return uploadAssociationsIfAbsentResponse
    }

    // Draft sync methods
    override suspend fun uploadDraft(
        accessToken: String,
        draft: DraftUploadRequest,
    ): Result<DraftUploadResponse> {
        methodCalls.add("uploadDraft")
        uploadDraftCalls.add(accessToken to draft)
        return uploadDraftResponse
            ?: Result.success(
                DraftUploadResponse(
                    id = draft.id,
                    serverVersion = 1L,
                    uploadedAt = draft.lastUpdated,
                ),
            )
    }

    override suspend fun getDraftChanges(
        accessToken: String,
        since: Long,
        limit: Int?,
    ): Result<DraftChangesResponse> {
        methodCalls.add("getDraftChanges")
        return getDraftChangesResponse
    }

    override suspend fun deleteDraft(
        accessToken: String,
        draftId: String,
    ): Result<Unit> {
        methodCalls.add("deleteDraft")
        deleteDraftCalls.add(accessToken to draftId)
        return deleteDraftResponse
    }

    // Media sync methods
    override suspend fun uploadMedia(
        accessToken: String,
        media: MediaUpload,
    ): Result<MediaUploadResponse> {
        methodCalls.add("uploadMedia")
        uploadMediaCalls.add(accessToken to media.readRequest())
        return uploadMediaResponse
    }

    override suspend fun downloadMedia(
        accessToken: String,
        mediaId: String,
    ): Result<MediaDownloadResponse> {
        methodCalls.add("downloadMedia")
        downloadMediaCalls.add(accessToken to mediaId)
        return downloadMediaResponse
    }

    override suspend fun uploadBackup(
        accessToken: String,
        backup: BackupUploadRequest,
    ): Result<BackupUploadResponse> {
        methodCalls.add("uploadBackup")
        return uploadBackupResponse
    }

    override suspend fun listBackups(accessToken: String): Result<BackupListResponse> {
        methodCalls.add("listBackups")
        return listBackupsResponse
    }

    override suspend fun downloadBackup(
        accessToken: String,
        backupId: String,
    ): Result<BackupDownloadResponse> {
        methodCalls.add("downloadBackup")
        return downloadBackupResponse
    }

    override suspend fun deleteBackup(
        accessToken: String,
        backupId: String,
    ): Result<Unit> {
        methodCalls.add("deleteBackup")
        return deleteBackupResponse
    }

    fun reset() {
        methodCalls.clear()
        uploadContentCalls.clear()
        updateContentCalls.clear()
        deleteContentCalls.clear()
        getContentChangesCalls.clear()
        uploadMediaCalls.clear()
        downloadMediaCalls.clear()
        uploadDraftCalls.clear()
        deleteDraftCalls.clear()
    }

    fun wasMethodCalled(methodName: String): Boolean = methodCalls.contains(methodName)

    fun getMethodCallCount(methodName: String): Int = methodCalls.count { it == methodName }

    fun configureContentSyncFailure(error: Exception = Exception("Content sync failed")) {
        uploadContentResponse = Result.failure(error)
        getContentChangesResponse = Result.failure(error)
        updateContentResponse = Result.failure(error)
        deleteContentResponse = Result.failure(error)
    }

    fun configureJournalSyncFailure(error: Exception = Exception("Journal sync failed")) {
        uploadJournalResponse = Result.failure(error)
        getJournalChangesResponse = Result.failure(error)
        updateJournalResponse = Result.failure(error)
        deleteJournalResponse = Result.failure(error)
    }
}

/*
 * Fake CloudAccountRepository for testing authenticated vs unauthenticated states.
 */
