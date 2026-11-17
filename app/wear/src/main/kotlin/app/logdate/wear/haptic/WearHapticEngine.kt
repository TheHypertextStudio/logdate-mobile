package app.logdate.wear.haptic

import android.os.VibrationEffect
import android.os.Vibrator

class WearHapticEngine(
    private val vibrator: Vibrator,
) {
    fun startRecording() {
        vibrate {
            VibrationEffect.createOneShot(40, 200)
        }
    }

    fun stopRecording() {
        vibrate {
            VibrationEffect.createWaveform(
                longArrayOf(0, 25, 80, 25),
                intArrayOf(0, 180, 0, 180),
                -1,
            )
        }
    }

    fun pause() {
        vibrate {
            VibrationEffect.createOneShot(10, 60)
        }
    }

    fun resume() {
        vibrate {
            VibrationEffect.createWaveform(
                longArrayOf(0, 10, 50, 10),
                intArrayOf(0, 60, 0, 60),
                -1,
            )
        }
    }

    fun rejection() {
        vibrate {
            // Ramp down from amplitude 40 over 100ms, approximated in three steps
            VibrationEffect.createWaveform(
                longArrayOf(0, 33, 33, 34),
                intArrayOf(0, 40, 25, 10),
                -1,
            )
        }
    }

    fun success() {
        vibrate {
            VibrationEffect.createWaveform(
                longArrayOf(0, 30, 60, 15),
                intArrayOf(0, 200, 0, 120),
                -1,
            )
        }
    }

    fun warning() {
        vibrate {
            // Three short pulses: (15ms on, 40ms off) x3
            VibrationEffect.createWaveform(
                longArrayOf(0, 15, 40, 15, 40, 15),
                intArrayOf(0, 150, 0, 150, 0, 150),
                -1,
            )
        }
    }

    private inline fun vibrate(effect: () -> VibrationEffect) {
        vibrator.vibrate(effect())
    }
}
