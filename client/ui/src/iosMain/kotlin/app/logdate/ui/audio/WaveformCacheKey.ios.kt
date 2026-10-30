package app.logdate.ui.audio

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256

private const val SHA256_BYTES = 32

@OptIn(ExperimentalForeignApi::class, ExperimentalUnsignedTypes::class)
internal actual fun waveformCacheKey(audioUri: String): String {
    val data = audioUri.encodeToByteArray()
    val digest = UByteArray(SHA256_BYTES)
    digest.usePinned { digestPinned ->
        if (data.isEmpty()) {
            CC_SHA256(null, 0u, digestPinned.addressOf(0))
        } else {
            data.usePinned { dataPinned -> CC_SHA256(dataPinned.addressOf(0), data.size.toUInt(), digestPinned.addressOf(0)) }
        }
    }
    return digest.joinToString("") { byte -> byte.toString(16).padStart(2, '0') }
}
