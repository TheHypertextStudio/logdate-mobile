package app.logdate.client.data.rewind

import app.logdate.client.database.dao.rewind.ReflectionPromptResponseDao
import app.logdate.client.database.entities.rewind.ReflectionPromptResponseEntity
import app.logdate.client.repository.rewind.ReflectionPromptResponseRepository
import app.logdate.client.util.platformDataDispatcher
import app.logdate.shared.model.ReflectionPrompt
import app.logdate.shared.model.ReflectionPromptKey
import app.logdate.shared.model.ReflectionPromptResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * On-device implementation of [ReflectionPromptResponseRepository] backed by Room.
 *
 * Reads stream off the DAO directly so the rewind detail screen reflects edits in
 * real time. Writes go through the IO dispatcher and call upsert so editing an
 * existing reply replaces it without a separate update path.
 */
class OfflineFirstReflectionPromptResponseRepository(
    private val dao: ReflectionPromptResponseDao,
    private val ioDispatcher: CoroutineDispatcher = platformDataDispatcher,
    private val clock: Clock = Clock.System,
) : ReflectionPromptResponseRepository {
    override fun observe(rewindId: Uuid): Flow<Map<ReflectionPromptKey, ReflectionPromptResponse>> =
        dao
            .observeForRewind(rewindId)
            .map { entities ->
                entities.associate { entity ->
                    val key = ReflectionPromptKey(entity.promptKey)
                    key to entity.toDomainModel()
                }
            }

    override suspend fun save(
        rewindId: Uuid,
        prompt: ReflectionPrompt,
        responseText: String,
    ): Unit =
        withContext(ioDispatcher) {
            val key = ReflectionPromptKey.of(prompt)
            val now = clock.now()
            val existing = dao.get(rewindId, key.value)
            val entity =
                ReflectionPromptResponseEntity(
                    rewindId = rewindId,
                    promptKey = key.value,
                    observation = prompt.observation,
                    invitation = prompt.invitation,
                    responseText = responseText,
                    created = existing?.created ?: now,
                    lastUpdated = now,
                )
            dao.upsert(entity)
        }

    override suspend fun delete(
        rewindId: Uuid,
        prompt: ReflectionPrompt,
    ): Unit =
        withContext(ioDispatcher) {
            dao.delete(rewindId, ReflectionPromptKey.of(prompt).value)
        }

    private fun ReflectionPromptResponseEntity.toDomainModel(): ReflectionPromptResponse =
        ReflectionPromptResponse(
            rewindId = rewindId,
            key = ReflectionPromptKey(promptKey),
            observation = observation,
            invitation = invitation,
            responseText = responseText,
            created = created,
            lastUpdated = lastUpdated,
        )
}
