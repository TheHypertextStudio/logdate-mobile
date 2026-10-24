package app.logdate.client.media.device

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.logdate.client.text.UiText
import app.logdate.client.text.toUiText
import io.github.aakira.napier.Napier
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import logdate.client.media.generated.resources.Res
import logdate.client.media.generated.resources.media_device_route_audio_output
import logdate.client.media.generated.resources.media_device_route_bluetooth_audio
import logdate.client.media.generated.resources.media_device_route_bluetooth_broadcast
import logdate.client.media.generated.resources.media_device_route_bluetooth_headset
import logdate.client.media.generated.resources.media_device_route_bluetooth_le_headset
import logdate.client.media.generated.resources.media_device_route_bluetooth_le_speaker
import logdate.client.media.generated.resources.media_device_route_built_in_microphone
import logdate.client.media.generated.resources.media_device_route_external_microphone
import logdate.client.media.generated.resources.media_device_route_headset_microphone
import logdate.client.media.generated.resources.media_device_route_this_phone
import logdate.client.media.generated.resources.media_device_route_usb_audio_device
import logdate.client.media.generated.resources.media_device_route_usb_headset
import logdate.client.media.generated.resources.media_device_route_usb_microphone
import logdate.client.media.generated.resources.media_device_route_wired_headphones
import logdate.client.media.generated.resources.media_device_route_wired_headset

class AndroidAudioRouteRepository(
    context: Context,
) : AudioRouteRepository {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _inputDevices = MutableStateFlow(buildInputState())
    private val _outputDevices = MutableStateFlow(buildOutputState())
    private var requestedInputId = _inputDevices.value.selectedDeviceId
    private val inputRequests =
        MutableSharedFlow<String?>(replay = 1, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST).apply {
            tryEmit(requestedInputId)
        }
    private val routeStateLock = Any()
    private var recordingActive = false
    private var routedInputId: String? = null
    private var inputRoutingError: UiText? = null

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshDevices()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshDevices()
            }
        }

    override val inputDevices: StateFlow<MediaDeviceSelectionUiState> = _inputDevices
    override val outputDevices: StateFlow<MediaDeviceSelectionUiState> = _outputDevices
    override val requestedInputDeviceIds: SharedFlow<String?> = inputRequests

    override fun updateRecordingInputDevice(
        deviceId: String?,
        isRecording: Boolean,
        error: UiText?,
    ) {
        synchronized(routeStateLock) {
            recordingActive = isRecording
            routedInputId = deviceId
            inputRoutingError = error
            refreshDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        refreshDevices()
    }

    override fun selectInputDevice(deviceId: String) {
        val selectedDevice =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull {
                    it.isSource &&
                        isMicrophoneInputType(it.type) &&
                        AndroidAudioRouteDevices.deviceKey(it, MediaDeviceKind.AUDIO_INPUT) == deviceId
                }

        if (selectedDevice == null) {
            Napier.w("Ignored unavailable microphone route selection: $deviceId")
            return
        }

        AndroidAudioRouteDevices.setPreferredInputDeviceId(appContext, deviceId)
        refreshDevices(forceInputRequest = true)
    }

    override fun selectOutputDevice(deviceId: String) {
        val selectedDevice =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { AndroidAudioRouteDevices.deviceKey(it, MediaDeviceKind.AUDIO_OUTPUT) == deviceId }

        if (selectedDevice == null) {
            Napier.w("Ignored unavailable output route selection: $deviceId")
            return
        }

        AndroidAudioRouteDevices.setPreferredOutputDeviceId(appContext, deviceId)
        refreshDevices()
    }

    private fun refreshDevices(forceInputRequest: Boolean = false) {
        synchronized(routeStateLock) {
            val requested = buildInputState()
            if (forceInputRequest || requestedInputId != requested.selectedDeviceId) {
                requestedInputId = requested.selectedDeviceId
                inputRequests.tryEmit(requestedInputId)
            }
            _inputDevices.value =
                if (recordingActive) {
                    MediaDeviceSelectionResolver.resolveRecordingAudioInput(requested, routedInputId, inputRoutingError)
                } else {
                    requested
                }
            _outputDevices.value = buildOutputState()
        }
    }

    private fun buildInputState(): MediaDeviceSelectionUiState {
        val devices =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSource && isMicrophoneInputType(it.type) }
                .map { it.toMediaDeviceUiState(MediaDeviceKind.AUDIO_INPUT) }

        return MediaDeviceSelectionResolver.resolveAudioInput(
            devices = devices,
            preferredDeviceId = AndroidAudioRouteDevices.getPreferredInputDeviceId(appContext),
        )
    }

    private fun buildOutputState(): MediaDeviceSelectionUiState {
        val devices =
            audioManager
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.isSink && it.isSelectableMediaOutput() }
                .map { it.toMediaDeviceUiState(MediaDeviceKind.AUDIO_OUTPUT) }

        return MediaDeviceSelectionResolver.resolveAudioOutput(
            devices = devices,
            preferredDeviceId = AndroidAudioRouteDevices.getPreferredOutputDeviceId(appContext),
        )
    }
}

object AndroidAudioRouteDevices {
    private const val PREFS_NAME = "logdate_media_routes"
    private const val KEY_INPUT_DEVICE_ID = "preferred_input_device_id"
    private const val KEY_OUTPUT_DEVICE_ID = "preferred_output_device_id"

    /** The shared preferences the route selection is persisted in. */
    fun routePreferences(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPreferredInputDeviceId(context: Context): String? =
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_INPUT_DEVICE_ID, null)

    fun setPreferredInputDeviceId(
        context: Context,
        deviceId: String,
    ) {
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_INPUT_DEVICE_ID, deviceId)
            .apply()
    }

    fun getPreferredOutputDeviceId(context: Context): String? =
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OUTPUT_DEVICE_ID, null)

    fun setPreferredOutputDeviceId(
        context: Context,
        deviceId: String,
    ) {
        context
            .applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OUTPUT_DEVICE_ID, deviceId)
            .apply()
    }

    fun findPreferredInputDevice(
        context: Context,
        preferredDeviceId: String?,
    ): AudioDeviceInfo? {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceId = preferredDeviceId ?: getPreferredInputDeviceId(context)
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull {
                it.isSource && isMicrophoneInputType(it.type) && deviceKey(it, MediaDeviceKind.AUDIO_INPUT) == deviceId
            }
    }

    fun findPreferredOutputDevice(
        context: Context,
        preferredDeviceId: String? = null,
    ): AudioDeviceInfo? {
        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceId = preferredDeviceId ?: getPreferredOutputDeviceId(context) ?: return null
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.isSink && deviceKey(it, MediaDeviceKind.AUDIO_OUTPUT) == deviceId }
    }

    fun deviceKey(
        device: AudioDeviceInfo,
        kind: MediaDeviceKind,
    ): String = "${kind.name.lowercase()}-${device.type}-${device.id}"
}

/**
 * True for outputs a person would actually choose for listening.
 *
 * The platform reports the earpiece and telephony routes as playback sinks, but neither is a
 * sensible destination for a voice note, and listing them only pads the picker.
 */
private fun AudioDeviceInfo.isSelectableMediaOutput(): Boolean =
    type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && type != AudioDeviceInfo.TYPE_TELEPHONY

/** Source ports also include system capture streams, which cannot serve as microphone routes. */
internal fun isMicrophoneInputType(type: Int): Boolean =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> true

        else -> false
    }

/**
 * Groups the profiles of one physical device together.
 *
 * Bluetooth hardware is reported once per profile — A2DP, hands-free and LE Audio — all sharing
 * one address, so the address is the identity. Anything without an address is its own device.
 */
private fun AudioDeviceInfo.routeGroupId(): String? = address.takeIf { it.isNotBlank() }

/** Which profile wins when one device is reported several times. Higher is better. */
private fun AudioDeviceInfo.routeQualityRank(): Int =
    when (type) {
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 3
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 1
        else -> 0
    }

private fun AudioDeviceInfo.toMediaDeviceUiState(kind: MediaDeviceKind): MediaDeviceUiState =
    MediaDeviceUiState(
        id = AndroidAudioRouteDevices.deviceKey(this, kind),
        label = labelFor(kind),
        kind = kind,
        category = categoryForType(type),
        isExternal = isExternalType(type),
        groupId = routeGroupId(),
        qualityRank = routeQualityRank(),
    )

private fun AudioDeviceInfo.labelFor(kind: MediaDeviceKind): UiText {
    if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return Res.string.media_device_route_built_in_microphone.toUiText()
    if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) return Res.string.media_device_route_this_phone.toUiText()
    val productName = productName?.toString()?.trim().orEmpty()
    if (productName.isNotBlank()) return productName.toUiText()

    val isInput = kind == MediaDeviceKind.AUDIO_INPUT
    val label =
        when (type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> {
                if (isInput) Res.string.media_device_route_headset_microphone else Res.string.media_device_route_wired_headset
            }

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> {
                Res.string.media_device_route_wired_headphones
            }

            AudioDeviceInfo.TYPE_USB_DEVICE -> {
                Res.string.media_device_route_usb_audio_device
            }

            AudioDeviceInfo.TYPE_USB_HEADSET -> {
                if (isInput) Res.string.media_device_route_usb_microphone else Res.string.media_device_route_usb_headset
            }

            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                Res.string.media_device_route_bluetooth_headset
            }

            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                Res.string.media_device_route_bluetooth_audio
            }

            AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                Res.string.media_device_route_bluetooth_le_headset
            }

            AudioDeviceInfo.TYPE_BLE_SPEAKER -> {
                Res.string.media_device_route_bluetooth_le_speaker
            }

            AudioDeviceInfo.TYPE_BLE_BROADCAST -> {
                Res.string.media_device_route_bluetooth_broadcast
            }

            else -> {
                if (isInput) Res.string.media_device_route_external_microphone else Res.string.media_device_route_audio_output
            }
        }
    return label.toUiText()
}

private fun categoryForType(type: Int): MediaDeviceCategory =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        -> MediaDeviceCategory.BUILT_IN

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> MediaDeviceCategory.USB

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        -> MediaDeviceCategory.BLUETOOTH

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        -> MediaDeviceCategory.WIRED

        else -> MediaDeviceCategory.EXTERNAL
    }

private fun isExternalType(type: Int): Boolean =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        -> false

        else -> true
    }
