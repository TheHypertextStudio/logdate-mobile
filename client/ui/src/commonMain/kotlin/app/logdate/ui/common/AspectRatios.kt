package app.logdate.ui.common

/**
 * Standard aspect ratios used throughout the application.
 *
 * This utility class provides standardized aspect ratios to ensure
 * consistent proportions across the app for various UI elements.
 *
 * Aspect ratios are expressed as width/height. For example:
 * - RATIO_9_16: 9/16 means the width is 9 units for every 16 units of height.
 * - RATIO_16_9: 16/9 means the width is 16 units for every 9 units of height.
 *
 * For enforcing aspect ratios while maintaining responsiveness, use these constants with
 * the constrainHeightRatioIn and constrainWidthRatioIn modifiers:
 *
 * ```kotlin
 * // Constrain height based on width and aspect ratio
 * Modifier.constrainHeightRatioIn(
 *     width = maxWidth,
 *     maxRatio = AspectRatios.RATIO_16_9  // Makes sure height doesn't exceed 9/16 of width
 * )
 *
 * // Constrain width based on height and aspect ratio
 * Modifier.constrainWidthRatioIn(
 *     height = maxHeight,
 *     minRatio = AspectRatios.RATIO_4_3   // Ensures width is at least 4/3 of height
 * )
 * ```
 *
 * These modifiers are preferable to fixed aspectRatio() when you want to ensure
 * proportional constraints while still allowing the component to adapt to available space.
 *
 * ## Usage Guidelines
 *
 * This class provides two types of constants:
 *
 * 1. **Raw aspect ratios** (RATIO_*): Always prefer these for general UI components that don't have
 *    a specific semantic meaning. These are clear, explicit, and domain-agnostic.
 *
 * 2. **Semantic aliases** (JOURNAL_COVER, PHOTO, etc.): Only use these when your component
 *    directly relates to the named domain. For example, use JOURNAL_COVER only for actual
 *    journal covers, not for generic UI elements that happen to have a similar ratio.
 *
 * ```kotlin
 * // GOOD: Using raw aspect ratio for a generic card
 * Card(
 *     modifier = Modifier.aspectRatio(AspectRatios.RATIO_3_4)
 * )
 *
 * // GOOD: Using semantic alias for domain-specific component
 * JournalCover(
 *     modifier = Modifier.aspectRatio(AspectRatios.JOURNAL_COVER)
 * )
 *
 * // BAD: Using journal cover ratio for unrelated component
 * GenericMediaCard(
 *     modifier = Modifier.aspectRatio(AspectRatios.JOURNAL_COVER) // Should use RATIO_9_16 instead
 * )
 * ```
 */
object AspectRatios {
    // Base aspect ratios with feature-agnostic names

    /**
     * 1:1 aspect ratio.
     */
    const val RATIO_1_1 = 1f

    /**
     * 3:2 aspect ratio.
     */
    const val RATIO_3_2 = 3f / 2f

    /**
     * 2:3 aspect ratio.
     */
    const val RATIO_2_3 = 2f / 3f

    /**
     * 4:3 aspect ratio.
     */
    const val RATIO_4_3 = 4f / 3f

    /**
     * 3:4 aspect ratio.
     */
    const val RATIO_3_4 = 3f / 4f

    /**
     * 16:9 aspect ratio.
     */
    const val RATIO_16_9 = 16f / 9f

    /**
     * 9:16 aspect ratio.
     */
    const val RATIO_9_16 = 9f / 16f

    /**
     * Golden ratio (approximately 1.618:1).
     */
    const val RATIO_GOLDEN = 1.618f

    /**
     * Inverse golden ratio (approximately 0.618:1).
     */
    const val RATIO_GOLDEN_INVERSE = 0.618f

    // Semantic aliases for specific use cases

    /**
     * Square aspect ratio (1:1), used for profile pictures, icons, etc.
     */
    val SQUARE = RATIO_1_1

    /**
     * Standard portrait aspect ratio for journals and editor surfaces.
     * This ratio (9:16) resembles the proportion of a physical notebook or journal.
     */
    val JOURNAL_COVER = RATIO_9_16

    /**
     * Standard landscape aspect ratio for content display.
     * This ratio (16:9) is commonly used for media and widescreen content.
     */
    val WIDESCREEN = RATIO_16_9

    /**
     * Photo aspect ratio (3:2), a common ratio for photo prints and cards.
     */
    val PHOTO = RATIO_3_2

    /**
     * Traditional display ratio (4:3) before widescreen.
     */
    val TRADITIONAL = RATIO_4_3

    /**
     * Story aspect ratio (9:16), commonly used for full-screen mobile content.
     */
    val STORY = RATIO_9_16
}
