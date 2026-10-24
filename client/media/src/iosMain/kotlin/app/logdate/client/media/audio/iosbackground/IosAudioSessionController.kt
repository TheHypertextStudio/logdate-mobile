@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package app.logdate.client.media.audio.iosbackground

import io.github.aakira.napier.Napier
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue

/**
 * Helper class to manage iOS audio session for background audio recording.
 *
 * Configures the audio session and surfaces system events (interruptions, route changes) so the
 * recorder can pause/resume cleanly when calls arrive or headphones are unplugged.
 */
class IosAudioSessionController {
    private val audioSession = AVAudioSession.sharedInstance()
    private val _events = MutableSharedFlow<AudioSessionEvent>(extraBufferCapacity = 8)

    /** Stream of system audio-session events the recording manager subscribes to. */
    val events: SharedFlow<AudioSessionEvent> = _events.asSharedFlow()

    init {
        installObservers()
    }

    /**
     * Sets up the audio session for recording in the background.
     *
     * @return True if the setup was successful
     */
    fun setupAudioSessionForRecording(): Boolean {
        Napier.d("Setting up iOS audio session for recording")

        val categoryOptions =
            AVAudioSessionCategoryOptionDefaultToSpeaker or
                AVAudioSessionCategoryOptionAllowBluetooth or
                AVAudioSessionCategoryOptionAllowBluetoothA2DP

        val categoryError =
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                audioSession.setCategory(
                    AVAudioSessionCategoryPlayAndRecord,
                    withOptions = categoryOptions,
                    error = errorPtr.ptr,
                )
                errorPtr.value
            }
        if (categoryError != null) {
            Napier.e("Failed to set audio session category: ${categoryError.localizedDescription}")
            return false
        }

        val modeError =
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                audioSession.setMode(AVAudioSessionModeSpokenAudio, error = errorPtr.ptr)
                errorPtr.value
            }
        if (modeError != null) {
            Napier.e("Failed to set audio session mode: ${modeError.localizedDescription}")
            return false
        }

        val activateError =
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                audioSession.setActive(true, error = errorPtr.ptr)
                errorPtr.value
            }
        if (activateError != null) {
            Napier.e("Failed to activate audio session: ${activateError.localizedDescription}")
            return false
        }

        Napier.d("iOS audio session activated for recording")
        return true
    }

    /**
     * Releases the audio session so other apps' audio resumes promptly.
     */
    fun endAudioSession() {
        val error =
            memScoped {
                val errorPtr = alloc<ObjCObjectVar<NSError?>>()
                audioSession.setActive(
                    active = false,
                    withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
                    error = errorPtr.ptr,
                )
                errorPtr.value
            }
        if (error != null) {
            Napier.w("Failed to deactivate audio session: ${error.localizedDescription}")
        } else {
            Napier.d("Audio session deactivated")
        }
    }

    private fun installObservers() {
        val center = NSNotificationCenter.defaultCenter
        val mainQueue = NSOperationQueue.mainQueue

        AVAudioSessionInterruptionNotification?.let { name ->
            center.addObserverForName(
                name = name,
                `object` = null,
                queue = mainQueue,
                usingBlock = { notification -> handleInterruption(notification) },
            )
        }

        AVAudioSessionRouteChangeNotification?.let { name ->
            center.addObserverForName(
                name = name,
                `object` = null,
                queue = mainQueue,
                usingBlock = { notification -> handleRouteChange(notification) },
            )
        }
    }

    private fun handleInterruption(notification: NSNotification?) {
        val info = notification?.userInfo ?: return
        val typeKey = AVAudioSessionInterruptionTypeKey ?: return
        val rawType = (info[typeKey] as? NSNumber)?.unsignedLongValue ?: return
        when (rawType) {
            AVAudioSessionInterruptionTypeBegan -> {
                Napier.d("Audio session interruption began")
                _events.tryEmit(AudioSessionEvent.InterruptionBegan)
            }

            AVAudioSessionInterruptionTypeEnded -> {
                val optionKey = AVAudioSessionInterruptionOptionKey
                val rawOption =
                    optionKey
                        ?.let { (info[it] as? NSNumber)?.unsignedLongValue }
                        ?: 0u.toULong()
                val shouldResume = (rawOption and SHOULD_RESUME_BIT) == SHOULD_RESUME_BIT
                Napier.d("Audio session interruption ended (shouldResume=$shouldResume)")
                _events.tryEmit(AudioSessionEvent.InterruptionEnded(shouldResume = shouldResume))
            }
        }
    }

    private fun handleRouteChange(notification: NSNotification?) {
        val info = notification?.userInfo ?: return
        val reasonKey = AVAudioSessionRouteChangeReasonKey ?: return
        val rawReason = (info[reasonKey] as? NSNumber)?.unsignedLongValue ?: return
        if (rawReason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) {
            Napier.d("Audio session route removed (eg headphones unplugged)")
            _events.tryEmit(AudioSessionEvent.OutputRouteRemoved)
        }
    }

    companion object {
        // AVAudioSessionInterruptionOptionShouldResume is exposed as a top-level constant; bit 1
        // in the option mask. Using the raw value directly avoids a separate import that is not
        // surfaced consistently across K/N versions.
        private const val SHOULD_RESUME_BIT: ULong = 1uL
    }
}

/** System audio-session events surfaced to the recorder. */
sealed class AudioSessionEvent {
    /** A higher-priority audio source (call, Siri) took over — pause any active recording. */
    object InterruptionBegan : AudioSessionEvent()

    /**
     * The interrupting source has finished. [shouldResume] reflects whether iOS recommends
     * automatically resuming the previous audio activity.
     */
    data class InterruptionEnded(
        val shouldResume: Boolean,
    ) : AudioSessionEvent()

    /** The current output route was removed (headphones unplugged, AirPlay disconnect). */
    object OutputRouteRemoved : AudioSessionEvent()
}
