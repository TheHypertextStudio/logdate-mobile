package app.logdate.server.logdate

import app.logdate.server.identity.adoption.IdentityWriteExpectation
import studio.hypertext.atproto.identity.AtprotoDid

internal enum class ObjectUploadKind { HOSTED_REPO, MEDIA, BACKUP, SPACE }

internal data class ObjectUpload(
    val id: String,
    val origin: IdentityWriteExpectation,
    val repo: AtprotoDid?,
    val path: String,
    val kind: ObjectUploadKind = ObjectUploadKind.HOSTED_REPO,
    val space: String? = null,
    val legacyMigrationId: String? = null,
    val legacyMigrationVersion: Long? = null,
)

/** No provider work takes place inside these short admission/finalization transactions. */
internal interface ObjectUploads {
    fun begin(
        origin: IdentityWriteExpectation,
        repo: AtprotoDid?,
        path: String,
        kind: ObjectUploadKind = ObjectUploadKind.HOSTED_REPO,
        space: String? = null,
    ): ObjectUpload

    fun beginMigration(
        origin: IdentityWriteExpectation,
        path: String,
        space: String,
        migrationId: String,
        migrationVersion: Long,
    ): ObjectUpload = begin(origin, null, path, ObjectUploadKind.SPACE, space)

    fun stored(upload: ObjectUpload)

    fun <T> attach(
        upload: ObjectUpload,
        publish: () -> T,
        ownsResult: (T) -> Boolean = { true },
    ): T

    fun cleanup(
        upload: ObjectUpload,
        deleteObject: (String) -> Boolean,
    ): Boolean = false

    fun pendingCleanup(after: String? = null): List<ObjectUpload> = emptyList()

    /** Recovery rechecks the settled-write grace under the owner lock. */
    fun recover(
        upload: ObjectUpload,
        deleteObject: (String) -> Boolean,
    ): Boolean = false

    /** Non-database servers do not advertise durable adoption. */
    object Direct : ObjectUploads {
        override fun begin(
            origin: IdentityWriteExpectation,
            repo: AtprotoDid?,
            path: String,
            kind: ObjectUploadKind,
            space: String?,
        ) = ObjectUpload("", origin, repo, path, kind, space)

        override fun stored(upload: ObjectUpload) = Unit

        override fun <T> attach(
            upload: ObjectUpload,
            publish: () -> T,
            ownsResult: (T) -> Boolean,
        ): T = publish()
    }
}
