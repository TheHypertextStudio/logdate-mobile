package app.logdate.client.sync.peer.state

import app.logdate.client.sync.datalayer.v2.Decoded
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** The latest readable state document each peer node wrote, for as long as this process runs. */
class PeerStateStore {
    private val documents = MutableStateFlow<Map<String, StateDocument>>(emptyMap())

    /**
     * Stores [decoded] as [nodeId]'s document when it is readable and written by [expectedRole], and
     * returns it. Returns null, with the reason logged, for anything else.
     */
    fun accept(
        nodeId: String,
        decoded: Decoded<StateDocument>,
        expectedRole: PeerRole,
    ): StateDocument? {
        val document =
            when (decoded) {
                is Decoded.Supported -> {
                    decoded.value
                }

                is Decoded.Unsupported -> {
                    Napier.w("A $expectedRole state document uses schema ${decoded.schema}; it is ignored until this device updates")
                    return null
                }

                is Decoded.Malformed -> {
                    Napier.e("A $expectedRole state document could not be read: ${decoded.reason}")
                    return null
                }
            }
        if (document.role != expectedRole) {
            Napier.w("Ignoring a ${document.role} state document where a $expectedRole one was expected")
            return null
        }
        documents.update { it + (nodeId to document) }
        return document
    }

    fun clear() {
        documents.value = emptyMap()
    }

    fun latest(role: PeerRole): Flow<StateDocument?> =
        documents
            .map { all -> all.values.filter { it.role == role }.maxByOrNull { it.writtenAt } }
            .distinctUntilChanged()

    /** The latest readable document of each node that writes as [role]. */
    fun documents(role: PeerRole): Flow<Map<String, StateDocument>> =
        documents.map { all -> all.filterValues { it.role == role } }.distinctUntilChanged()

    /** The features the latest [role] document lists; empty until one arrives. */
    fun features(role: PeerRole): Flow<Set<String>> =
        latest(role)
            .map { document ->
                when (role) {
                    PeerRole.PHONE -> document?.phone?.features
                    PeerRole.WATCH -> document?.watch?.features
                }.orEmpty()
            }.distinctUntilChanged()
}
