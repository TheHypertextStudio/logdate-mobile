package app.logdate.client.sync.space.migration

import app.logdate.client.sync.metadata.UploadScope
import app.logdate.client.sync.space.SPACE_CHECKPOINT
import app.logdate.client.sync.space.SpaceRecordState
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

private const val SERVER_ATTEMPTS = 4

/**
 * Calls the server's move API with a fresh token for [scope], waiting out a dropped connection, rate
 * limiting or a busy server a few times; any other refusal is thrown as it is.
 */
internal suspend fun <T> SpaceMigrationEnvironment.server(
    scope: UploadScope,
    operation: String,
    block: suspend (String) -> T,
): T {
    var waitFor = 2.seconds
    repeat(SERVER_ATTEMPTS) { attempt ->
        val result =
            tokens.withFreshToken(
                { token ->
                    try {
                        Result.success(block(token))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                },
                operation,
                expectedScope = scope,
            )
        val error = result.exceptionOrNull() ?: return result.getOrThrow()
        if (!error.isTransient() || attempt == SERVER_ATTEMPTS - 1) throw error
        deps.wait(waitFor)
        waitFor *= 2
    }
    error("unreachable")
}

/**
 * Makes this device sync the personal space as the account's own after the switch:
 *
 * - it remembers the version of each record it wrote, as it stood at the switch, so its next change
 *   to an item replaces exactly that version; a record another device wrote since is left unknown, so
 *   this device reads it first;
 * - its change-feed cursor starts at the switch, so it doesn't download its own journal again, and
 *   downloads whatever other devices wrote since;
 * - its media references become the personal-space ones, so editing an item doesn't upload its file
 *   again;
 * - it forgets the versions legacy sync numbered its items with, which the space numbers differently.
 *
 * Every step can run again, so a device that stopped part way finishes on its next sync.
 */
internal suspend fun settleAfterSwitch(
    env: SpaceMigrationEnvironment,
    scope: UploadScope,
    readBack: SpaceReadBack,
    switchedAtSeq: Long,
    checkpoint: SpaceMigrationCheckpoint,
) {
    val swap = env.space.swapStore
    val kept = mutableSetOf<String>()
    for ((sealedRkey, copy) in readBack.live) {
        val collection = copy.collection ?: continue
        val rkey = copy.rkey ?: continue
        if (copy.seq > switchedAtSeq) continue
        swap.put(scope, SpaceRecordState(collection, rkey, sealedRkey, copy.cid, copy.seq))
        kept += sealedRkey
    }
    swap.all(scope).filter { it.sealedRkey !in kept }.forEach { swap.delete(scope, it.collection, it.rkey) }
    env.inbox.stageAll(SPACE_CHECKPOINT, switchedAtSeq, emptyMap())
    MovedMediaRefs(env.deps.checkpoints, env.legacyRefs, scope).adoptSpaceReferences(env.mediaOwners())
    env.refreshAccount()
    env.rebase(scope)
    env.deps.checkpoints.save(scope, checkpoint.copy(stage = SpaceMigrationStage.SWITCHED, switchedAtSeq = switchedAtSeq, settled = true))
}
