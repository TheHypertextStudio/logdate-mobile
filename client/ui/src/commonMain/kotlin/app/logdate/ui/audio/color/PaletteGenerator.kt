package app.logdate.ui.audio.color

import app.logdate.client.awareness.daylight.DaylightPeriod
import app.logdate.ui.audio.model.AudioPalette

/**
 * Generates contextual color palettes based on the time of day.
 *
 * Each palette is designed to evoke the mood and lighting conditions
 * of the time when the audio was recorded, creating a visual identity
 * that connects the memory to its temporal context.
 */
class PaletteGenerator {
    /**
     * Generates a color palette for the given daylight period.
     */
    fun generate(daylightPeriod: DaylightPeriod): AudioPalette = getBasePalette(daylightPeriod)

    private fun getBasePalette(period: DaylightPeriod): AudioPalette =
        when (period) {
            DaylightPeriod.DAWN -> {
                AudioPalette(
                    waveformGradientStart = 0xFF1A1A2E,
                    waveformGradientEnd = 0xFFFF9F7F,
                    playedFillColor = 0xFFFFB366,
                    accentColor = 0xFFFF7F50,
                    immersiveBackground = 0xFF0D0D1A,
                    contentColor = 0xFFFFD4C2,
                )
            }

            DaylightPeriod.MORNING -> {
                AudioPalette(
                    waveformGradientStart = 0xFF2D2D44,
                    waveformGradientEnd = 0xFFFFD699,
                    playedFillColor = 0xFFFFB84D,
                    accentColor = 0xFFFF9F40,
                    immersiveBackground = 0xFF1A1A2E,
                    contentColor = 0xFFFFF3E0,
                )
            }

            DaylightPeriod.MIDDAY -> {
                AudioPalette(
                    waveformGradientStart = 0xFF3D5A80,
                    waveformGradientEnd = 0xFFF5F5F5,
                    playedFillColor = 0xFF5C8DC7,
                    accentColor = 0xFF2E86AB,
                    immersiveBackground = 0xFF1A2A40,
                    contentColor = 0xFFE8F4FD,
                )
            }

            DaylightPeriod.AFTERNOON -> {
                AudioPalette(
                    waveformGradientStart = 0xFF4A5568,
                    waveformGradientEnd = 0xFFFFE4B5,
                    playedFillColor = 0xFFDEB887,
                    accentColor = 0xFFCD853F,
                    immersiveBackground = 0xFF2D2D2D,
                    contentColor = 0xFFFFF8F0,
                )
            }

            DaylightPeriod.GOLDEN_HOUR -> {
                AudioPalette(
                    waveformGradientStart = 0xFF4A3728,
                    waveformGradientEnd = 0xFFFFD700,
                    playedFillColor = 0xFFFFA500,
                    accentColor = 0xFFFF8C00,
                    immersiveBackground = 0xFF1A1208,
                    contentColor = 0xFFFFF9E6,
                )
            }

            DaylightPeriod.EVENING -> {
                AudioPalette(
                    waveformGradientStart = 0xFF1A1A2E,
                    waveformGradientEnd = 0xFF9B59B6,
                    playedFillColor = 0xFF8E44AD,
                    accentColor = 0xFF6C3483,
                    immersiveBackground = 0xFF0D0D1A,
                    contentColor = 0xFFE8D5F0,
                )
            }

            DaylightPeriod.NIGHT -> {
                AudioPalette(
                    waveformGradientStart = 0xFF0D0D1A,
                    waveformGradientEnd = 0xFF2C3E50,
                    playedFillColor = 0xFF34495E,
                    accentColor = 0xFF5DADE2,
                    immersiveBackground = 0xFF050508,
                    contentColor = 0xFFCDD8E3,
                )
            }
        }
}
