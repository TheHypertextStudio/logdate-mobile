package app.logdate.client.intelligence.rewind

import app.logdate.shared.model.ActivityType

/**
 * Generates contextual, witty messages to accompany Rewind displays.
 *
 * Messages are tailored based on content patterns, activities, and user behavior
 * to create delightful, personalized moments.
 */
class WittyRewindMessageGenerator : RewindMessageGenerator {
    private companion object {
        /**
         * General messages for available rewinds.
         */
        private val GENERAL_MESSAGES =
            listOf(
                "Quite the adventurous one, are you?",
                "What a week it's been",
                "Time flies when you're journaling",
                "Moments worth remembering",
                "Plot twist: it was a good week",
                "This one's a keeper",
            )

        /**
         * Messages for photo-heavy weeks.
         */
        private val PHOTO_HEAVY_MESSAGES =
            listOf(
                "Quite the photographer this week 📸",
                "A picture is worth a thousand words, and you've got plenty",
                "Say cheese! Actually, you already did",
                "Camera roll's doing the heavy lifting this week",
                "Evidence you had a great time: extensive",
            )

        /**
         * Messages for text-heavy weeks.
         */
        private val TEXT_HEAVY_MESSAGES =
            listOf(
                "The thoughts were flowing this week",
                "Quite the writer, aren't you?",
                "Words, words, wonderful words",
                "Your mind's been busy",
            )

        /**
         * Messages for late-night entries.
         */
        private val NIGHT_OWL_MESSAGES =
            listOf(
                "Burning the midnight oil, I see 🌙",
                "The night owl chronicles",
                "Some of your best thoughts happen after dark",
                "Who needs sleep when you have thoughts to capture?",
            )

        /**
         * Messages for quiet weeks.
         */
        private val QUIET_WEEK_MESSAGES =
            listOf(
                "Sometimes simple weeks matter too ✨",
                "Quality over quantity",
                "A quiet week, but yours nonetheless",
                "Not every week needs to be eventful",
            )

        /**
         * Messages for social weeks.
         */
        private val SOCIAL_WEEK_MESSAGES =
            listOf(
                "Quite the social butterfly 🦋",
                "All the good vibes with good people",
                "Connections that matter",
                "Your week was full of familiar faces",
            )

        /**
         * Messages for travel weeks.
         */
        private val TRAVEL_MESSAGES =
            listOf(
                "Wanderlust in action ✈️",
                "Adventure awaits, and you answered",
                "Exploring new horizons",
                "The journey matters",
            )

        /**
         * Messages for milestone weeks.
         */
        private val MILESTONE_MESSAGES =
            listOf(
                "Big things happened this week 🎉",
                "Milestones and memories",
                "You're making progress",
                "Look at you go!",
            )

        /**
         * Messages for focused work weeks.
         */
        private val FOCUSED_WORK_MESSAGES =
            listOf(
                "Head down, making it happen 💼",
                "In the zone this week",
                "Productivity mode: activated",
                "The grind was real",
            )

        /**
         * Messages for weeks with lots of food mentions.
         */
        private val FOODIE_MESSAGES =
            listOf(
                "Living your best foodie life 🍽️",
                "Someone's been eating well",
                "Food is love, and you've got plenty",
                "The culinary adventures continue",
            )

        /**
         * Messages for when rewind is generating.
         */
        private val UNAVAILABLE_MESSAGES =
            listOf(
                "Your entries are being woven into a story. Check back soon.",
                "Putting the pieces together. Almost there.",
                "Your week is taking shape behind the scenes.",
                "Brewing your memories... be right back.",
            )

        /**
         * Messages for streak milestones.
         */
        private val STREAK_MESSAGES =
            mapOf(
                7 to "A full week of memories! 🌟",
                14 to "Two weeks strong! 💪",
                30 to "30 days of memories preserved 🎉",
                60 to "60 days! You're on fire! 🔥",
                100 to "Triple digits! What a milestone! 🏆",
            )
    }

    override suspend fun generateMessage(rewindAvailable: Boolean): String = selectMessage(rewindAvailable)

    override suspend fun generateContextualMessage(
        rewindAvailable: Boolean,
        photoCount: Int,
        textCount: Int,
        peopleCount: Int,
        themes: List<String>,
    ): String =
        generateDetailedContextualMessage(
            rewindAvailable = rewindAvailable,
            themes = themes,
            photoCount = photoCount,
            textCount = textCount,
            peopleCount = peopleCount,
        )

    /**
     * Internal implementation with full parameter set for contextual message generation.
     */
    private fun generateDetailedContextualMessage(
        rewindAvailable: Boolean,
        themes: List<String> = emptyList(),
        photoCount: Int = 0,
        videoCount: Int = 0,
        textCount: Int = 0,
        peopleCount: Int = 0,
        activity: ActivityType? = null,
        hasLateNightEntries: Boolean = false,
        foodMentions: Int = 0,
        streakDays: Int = 0,
    ): String {
        if (!rewindAvailable) {
            return UNAVAILABLE_MESSAGES.random()
        }

        // Check for streak milestones first (highest priority)
        STREAK_MESSAGES[streakDays]?.let { return it }

        // Check for theme-based messages (AI-driven narrative themes take precedence)
        if (themes.isNotEmpty()) {
            val themeMessages = findThemeMessages(themes)
            if (themeMessages.isNotEmpty()) {
                return themeMessages.random()
            }
        }

        // Check for activity-specific messages
        activity?.let { activityType ->
            val activityMessages =
                when (activityType) {
                    ActivityType.TRAVEL -> TRAVEL_MESSAGES
                    ActivityType.SOCIAL -> SOCIAL_WEEK_MESSAGES
                    ActivityType.FOCUSED_WORK -> FOCUSED_WORK_MESSAGES
                    ActivityType.QUIET -> QUIET_WEEK_MESSAGES
                    ActivityType.MILESTONE -> MILESTONE_MESSAGES
                    ActivityType.MIXED -> null
                }
            activityMessages?.let { return it.random() }
        }

        // Check for pattern-based messages
        val totalMedia = photoCount + videoCount
        val totalContent = totalMedia + textCount

        return when {
            // Late night pattern (high priority for delight)
            hasLateNightEntries -> {
                NIGHT_OWL_MESSAGES.random()
            }

            // Food lover pattern
            foodMentions >= 3 -> {
                FOODIE_MESSAGES.random()
            }

            // Photo-heavy week (60%+ media)
            totalContent > 0 && (totalMedia.toFloat() / totalContent) > 0.6f -> {
                PHOTO_HEAVY_MESSAGES.random()
            }

            // Text-heavy week (70%+ text)
            totalContent > 0 && (textCount.toFloat() / totalContent) > 0.7f -> {
                TEXT_HEAVY_MESSAGES.random()
            }

            // Social week (4+ people)
            peopleCount >= 4 -> {
                SOCIAL_WEEK_MESSAGES.random()
            }

            // Quiet week (less than 5 items)
            totalContent < 5 -> {
                QUIET_WEEK_MESSAGES.random()
            }

            // Default: General message
            else -> {
                GENERAL_MESSAGES.random()
            }
        }
    }

    /**
     * Maps narrative themes to appropriate message categories.
     *
     * Prioritizes messages based on AI-generated themes like "vacation",
     * "work stress", "celebration", "relationships", etc.
     */
    private fun findThemeMessages(themes: List<String>): List<String> {
        val messages = mutableListOf<String>()

        themes.forEach { theme ->
            when {
                theme.contains("travel", ignoreCase = true) ||
                    theme.contains("vacation", ignoreCase = true) ||
                    theme.contains("trip", ignoreCase = true) -> {
                    messages.addAll(TRAVEL_MESSAGES)
                }

                theme.contains("work", ignoreCase = true) ||
                    theme.contains("project", ignoreCase = true) ||
                    theme.contains("deadline", ignoreCase = true) -> {
                    messages.addAll(FOCUSED_WORK_MESSAGES)
                }

                theme.contains("social", ignoreCase = true) ||
                    theme.contains("relationship", ignoreCase = true) ||
                    theme.contains("connection", ignoreCase = true) ||
                    theme.contains("friend", ignoreCase = true) ||
                    theme.contains("family", ignoreCase = true) -> {
                    messages.addAll(SOCIAL_WEEK_MESSAGES)
                }

                theme.contains("celebration", ignoreCase = true) ||
                    theme.contains("milestone", ignoreCase = true) ||
                    theme.contains("achievement", ignoreCase = true) ||
                    theme.contains("success", ignoreCase = true) -> {
                    messages.addAll(MILESTONE_MESSAGES)
                }

                theme.contains("food", ignoreCase = true) ||
                    theme.contains("culinary", ignoreCase = true) ||
                    theme.contains("dining", ignoreCase = true) -> {
                    messages.addAll(FOODIE_MESSAGES)
                }

                theme.contains("quiet", ignoreCase = true) ||
                    theme.contains("rest", ignoreCase = true) ||
                    theme.contains("peaceful", ignoreCase = true) ||
                    theme.contains("calm", ignoreCase = true) -> {
                    messages.addAll(QUIET_WEEK_MESSAGES)
                }
            }
        }

        return messages
    }

    private fun selectMessage(rewindAvailable: Boolean): String {
        val messages = if (rewindAvailable) GENERAL_MESSAGES else UNAVAILABLE_MESSAGES
        return messages.random()
    }
}
