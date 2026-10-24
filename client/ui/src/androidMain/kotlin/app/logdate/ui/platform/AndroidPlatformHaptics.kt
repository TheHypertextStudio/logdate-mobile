package app.logdate.ui.platform

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService

/**
 * Real Android implementation of [PlatformHapticsController]. Three-tier capability strategy:
 *
 * - **API 30 (R) and up + composition support:** uses [VibrationEffect.startComposition] for
 *   accurate primitives ([VibrationEffect.Composition.PRIMITIVE_LOW_TICK], `PRIMITIVE_TICK`,
 *   `PRIMITIVE_CLICK`).
 * - **API 29 (Q):** falls back to [VibrationEffect.createPredefined] with the system's
 *   built-in [VibrationEffect.EFFECT_TICK] / `EFFECT_CLICK` / `EFFECT_DOUBLE_CLICK` /
 *   `EFFECT_HEAVY_CLICK` patterns.
 * - **API 28 and below:** legacy `vibrate(durationMs)` with hand-tuned short durations.
 *
 * All vibrations route via `USAGE_TOUCH` audio/vibration attributes so the OS honors per-app
 * haptic intensity and the system "Haptic feedback" toggle automatically.
 */
internal class AndroidPlatformHaptics(
    private val context: Context,
) : PlatformHapticsController {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()
        }

    private val supportsComposition: Boolean by lazy {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator != null &&
            vibrator.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
    }

    private val touchAttributesQ: AudioAttributes by lazy {
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }

    @get:RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val touchAttributesT: VibrationAttributes by lazy {
        VibrationAttributes
            .Builder()
            .setUsage(VibrationAttributes.USAGE_TOUCH)
            .build()
    }

    override fun impact(strength: HapticImpactStrength) {
        if (supportsComposition) {
            vibrateComposition {
                when (strength) {
                    HapticImpactStrength.Soft -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.6f)
                    }

                    HapticImpactStrength.Light -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                    }

                    HapticImpactStrength.Medium -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                    }

                    HapticImpactStrength.Heavy -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    }

                    HapticImpactStrength.Rigid -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f)
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect =
                when (strength) {
                    HapticImpactStrength.Soft, HapticImpactStrength.Light -> VibrationEffect.EFFECT_TICK
                    HapticImpactStrength.Medium, HapticImpactStrength.Rigid -> VibrationEffect.EFFECT_CLICK
                    HapticImpactStrength.Heavy -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
            vibratePredefined(effect)
        } else {
            val durationMs =
                when (strength) {
                    HapticImpactStrength.Soft -> 10L
                    HapticImpactStrength.Light -> 15L
                    HapticImpactStrength.Medium, HapticImpactStrength.Rigid -> 25L
                    HapticImpactStrength.Heavy -> 40L
                }
            vibrateLegacy(durationMs)
        }
    }

    override fun notification(type: HapticNotificationType) {
        if (supportsComposition) {
            vibrateComposition {
                when (type) {
                    HapticNotificationType.Success -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f)
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 80)
                    }

                    HapticNotificationType.Warning -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 100)
                    }

                    HapticNotificationType.Error -> {
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f)
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 80)
                        addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.9f, 80)
                    }
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effect =
                when (type) {
                    HapticNotificationType.Success -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    HapticNotificationType.Warning -> VibrationEffect.EFFECT_HEAVY_CLICK
                    HapticNotificationType.Error -> VibrationEffect.EFFECT_HEAVY_CLICK
                }
            vibratePredefined(effect)
        } else {
            // Legacy: short patterned waveforms via a single duration.
            val durationMs =
                when (type) {
                    HapticNotificationType.Success -> 50L
                    HapticNotificationType.Warning -> 60L
                    HapticNotificationType.Error -> 80L
                }
            vibrateLegacy(durationMs)
        }
    }

    override fun tick() {
        if (supportsComposition) {
            vibrateComposition {
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.4f)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibratePredefined(VibrationEffect.EFFECT_TICK)
        } else {
            vibrateLegacy(8)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private inline fun vibrateComposition(block: VibrationEffect.Composition.() -> Unit) {
        val v = vibrator ?: return
        val effect = VibrationEffect.startComposition().apply(block).compose()
        playEffect(v, effect)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun vibratePredefined(effectId: Int) {
        val v = vibrator ?: return
        playEffect(v, VibrationEffect.createPredefined(effectId))
    }

    @Suppress("DEPRECATION")
    private fun vibrateLegacy(durationMs: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            v.vibrate(durationMs)
        }
    }

    private fun playEffect(
        v: Vibrator,
        effect: VibrationEffect,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, touchAttributesT)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, touchAttributesQ)
        }
    }

    /**
     * Quick check whether the user has globally disabled touch haptics in system settings. This
     * is consulted by the [LogDateHaptics] reduceMotion bridge — the controller itself does not
     * gate on it (so rare safety-critical signals still vibrate even when touch is off).
     */
    internal fun isSystemHapticFeedbackDisabled(): Boolean = isHapticDisabled(context)
}
