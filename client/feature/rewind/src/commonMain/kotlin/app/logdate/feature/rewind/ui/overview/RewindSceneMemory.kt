package app.logdate.feature.rewind.ui.overview

import app.logdate.shared.model.RewindContent

/** Read-only samples of the week's original memories, without generated cover copy. */
sealed interface RewindSceneMemory {
    data class Visual(
        val uri: String,
        val caption: String? = null,
        val isVideo: Boolean = false,
    ) : RewindSceneMemory

    data class Writing(
        val text: String,
    ) : RewindSceneMemory

    data class Recording(
        val durationMs: Long,
        val transcript: String?,
    ) : RewindSceneMemory
}

internal fun List<RewindContent>.toSceneMemories(): List<RewindSceneMemory> {
    val originals =
        filter {
            it is RewindContent.Image ||
                it is RewindContent.Video ||
                it is RewindContent.TextNote ||
                it is RewindContent.AudioNote
        }.distinctBy { it.sourceId }
    val visual = originals.filter { it is RewindContent.Image || it is RewindContent.Video }.maxByOrNull { it.significanceScore ?: 0f }
    val writing = originals.filterIsInstance<RewindContent.TextNote>().firstOrNull { it.content.isNotBlank() }
    val recording = originals.filterIsInstance<RewindContent.AudioNote>().firstOrNull()
    return (listOfNotNull(visual, writing, recording) + originals)
        .distinctBy { it.sourceId }
        .take(3)
        .map {
            when (it) {
                is RewindContent.Image -> RewindSceneMemory.Visual(it.uri, it.caption)
                is RewindContent.Video -> RewindSceneMemory.Visual(it.uri, it.caption, isVideo = true)
                is RewindContent.TextNote -> RewindSceneMemory.Writing(it.content.take(600) + if (it.content.length > 600) "…" else "")
                is RewindContent.AudioNote -> RewindSceneMemory.Recording(it.durationMs, it.transcriptionText?.take(180))
                else -> error("Only original memories enter a weekly scene")
            }
        }
}
