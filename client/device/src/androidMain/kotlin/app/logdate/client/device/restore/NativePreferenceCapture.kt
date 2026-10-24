package app.logdate.client.device.restore

import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.Base64

@Serializable
internal data class NativePreferenceSnapshot(
    val generation: String,
    val state: String,
    val source: String? = null,
)

internal interface NativePreferenceSnapshotStore {
    fun read(): NativePreferenceSnapshot?

    fun write(snapshot: NativePreferenceSnapshot)
}

internal class NativePreferenceCapture(
    private val generation: String,
    private val store: NativePreferenceSnapshotStore,
    private val deliveredSource: () -> ByteArray,
    private val deliveryComplete: () -> Boolean,
    private val deliveredDecision: () -> Boolean? = { null },
    private val repairDestination: (ByteArray) -> Unit = { throw IOException("Native preference replay is unavailable") },
) {
    fun restore(
        incoming: () -> ByteArray,
        copy: (ByteArray) -> Unit,
        duplicate: () -> Unit,
    ) {
        if (snapshot()?.state in setOf("prepared", "delivered", "captured")) {
            duplicate()
            repairPrepared()
            return
        }
        observe()
        val bytes = incoming()
        require(bytes.size <= 1_048_576)
        val prepared = NativePreferenceSnapshot(generation, "prepared", Base64.getEncoder().encodeToString(bytes))
        store.write(prepared)
        copy(bytes)
        store.write(prepared.copy(state = "delivered"))
    }

    fun deliveryDecision(): Boolean {
        repairPrepared()
        return when (snapshot()?.state) {
            "captured", "delivered" -> true
            "pending", "omitted", null -> false
            else -> throw IOException("Native preference delivery has an unconfirmed copy")
        }
    }

    fun begin() = store.write(NativePreferenceSnapshot(generation, "pending"))

    fun observe() {
        if (snapshot()?.state in setOf("delivered", "captured")) return
        store.write(NativePreferenceSnapshot(generation, "observed"))
    }

    fun capture() {
        repairPrepared()
        if (snapshot()?.state == "captured") return
        val bytes = snapshot()?.source?.let { Base64.getDecoder().decode(it) } ?: deliveredSource()
        require(bytes.size <= 1_048_576)
        store.write(NativePreferenceSnapshot(generation, "captured", Base64.getEncoder().encodeToString(bytes)))
    }

    fun finish(delivered: Boolean) {
        val previous = snapshot()
        if (previous?.state == "captured") return
        if (delivered) capture() else store.write(NativePreferenceSnapshot(generation, "omitted"))
    }

    fun read(): ByteArray? {
        val snapshot = snapshot()
        return when (snapshot?.state) {
            "captured" -> {
                Base64.getDecoder().decode(requireNotNull(snapshot.source)).also { require(it.size <= 1_048_576) }
            }

            "omitted" -> {
                null
            }

            "prepared" -> {
                if (!deliveryComplete()) throw IOException("Native delivery is still in progress")
                capture()
                read()
            }

            "delivered" -> {
                if (!deliveryComplete()) throw IOException("Native delivery is still in progress")
                capture()
                read()
            }

            "observed" -> {
                if (!deliveryComplete()) throw IOException("Native delivery is still in progress")
                if (deliveredDecision() == false) finish(false) else capture()
                read()
            }

            "pending", null -> {
                if (!deliveryComplete()) throw IOException("Native delivery is still in progress")
                val delivered = deliveredDecision() ?: throw IOException("Restored preference delivery is still pending")
                finish(delivered)
                read()
            }

            else -> {
                throw IOException("Restored preference capture is still pending")
            }
        }
    }

    private fun repairPrepared() {
        val prepared = snapshot()?.takeIf { it.state == "prepared" } ?: return
        val bytes = Base64.getDecoder().decode(requireNotNull(prepared.source))
        require(bytes.size <= 1_048_576)
        repairDestination(bytes)
        store.write(prepared.copy(state = "delivered"))
    }

    private fun snapshot(): NativePreferenceSnapshot? =
        store.read().also {
            if (it != null && it.generation != generation) throw IOException("Restored preferences belong to a previous delivery")
        }
}
