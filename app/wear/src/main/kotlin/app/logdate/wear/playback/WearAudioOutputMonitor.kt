package app.logdate.wear.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Describes the available audio output devices on the watch.
 */
sealed interface AudioOutputState {
    /** Both built-in speaker and Bluetooth are available. */
    data object SpeakerAndBluetooth : AudioOutputState

    /** Only the built-in speaker is available. */
    data object SpeakerOnly : AudioOutputState

    /** Only Bluetooth audio devices are connected (no speaker). */
    data object BluetoothOnly : AudioOutputState

    /** No audio output available at all. */
    data object Unavailable : AudioOutputState
}

/** What playback needs to know about where audio can go. */
interface WearAudioOutputs {
    val outputState: StateFlow<AudioOutputState>
}

/**
 * Monitors audio output device availability on Wear OS.
 *
 * Detects the built-in speaker and Bluetooth audio devices, exposing a reactive
 * [outputState] that updates when devices connect or disconnect.
 */
class WearAudioOutputMonitor(
    private val context: Context,
) : WearAudioOutputs {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _outputState = MutableStateFlow(deriveCurrentState())
    override val outputState: StateFlow<AudioOutputState> = _outputState

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshState()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshState()
            }
        }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
    }

    /**
     * Unregisters the audio device callback. Call when the monitor is no longer needed.
     */
    fun unregister() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
    }

    private fun refreshState() {
        val newState = deriveCurrentState()
        Napier.d { "Audio output state changed: $newState" }
        _outputState.value = newState
    }

    private fun deriveCurrentState(): AudioOutputState {
        val deviceTypes =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .map { it.type }
        return deriveOutputState(deviceTypes)
    }

    companion object {
        /**
         * Pure function for deriving audio output state from a list of device type constants.
         * Accepts [AudioDeviceInfo] type integers for testability without framework mocking.
         */
        internal fun deriveOutputState(deviceTypes: List<Int>): AudioOutputState {
            val hasSpeaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in deviceTypes
            val hasBluetooth = deviceTypes.any { it in BLUETOOTH_AUDIO_TYPES }
            return when {
                hasSpeaker && hasBluetooth -> AudioOutputState.SpeakerAndBluetooth
                hasSpeaker -> AudioOutputState.SpeakerOnly
                hasBluetooth -> AudioOutputState.BluetoothOnly
                else -> AudioOutputState.Unavailable
            }
        }

        private val BLUETOOTH_AUDIO_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
            )
    }
}
