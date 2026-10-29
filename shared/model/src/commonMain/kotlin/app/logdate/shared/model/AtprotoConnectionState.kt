package app.logdate.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AtprotoConnectionState {
    @SerialName("connected")
    CONNECTED,

    @SerialName("expired")
    EXPIRED,

    @SerialName("revoked")
    REVOKED,

    @SerialName("reconnect_required")
    RECONNECT_REQUIRED,

    @SerialName("disconnected")
    DISCONNECTED,
}
