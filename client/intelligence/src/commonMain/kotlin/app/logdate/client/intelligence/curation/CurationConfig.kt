package app.logdate.client.intelligence.curation

/**
 * Tunables for the curation pipeline. A [CurationConfigProvider] reads user preferences
 * to choose between [Strictness] levels and produces the concrete config.
 */
data class CurationConfig(
    val strictness: Strictness = Strictness.STANDARD,
    val maxItemsPerBeat: Int = 4,
    val maxTotalMedia: Int = 28,
    val burstWindowMs: Long = 30_000L,
    val minSignificanceForFreeAgent: Float = 35f,
    val minResolutionPx: Int = 480,
    val excludeScreenshots: Boolean = true,
    val excludeDocScans: Boolean = true,
) {
    enum class Strictness { LENIENT, STANDARD, STRICT }

    companion object {
        /**
         * Preset matching the user's selected strictness preference. Defaults stay in sync
         * with [CurationConfig]'s field defaults.
         */
        fun forStrictness(strictness: Strictness): CurationConfig =
            when (strictness) {
                Strictness.LENIENT -> {
                    CurationConfig(
                        strictness = strictness,
                        maxItemsPerBeat = 6,
                        minSignificanceForFreeAgent = 25f,
                        excludeScreenshots = false,
                    )
                }

                Strictness.STANDARD -> {
                    CurationConfig(strictness = strictness)
                }

                Strictness.STRICT -> {
                    CurationConfig(
                        strictness = strictness,
                        maxItemsPerBeat = 2,
                        minSignificanceForFreeAgent = 50f,
                    )
                }
            }
    }
}
