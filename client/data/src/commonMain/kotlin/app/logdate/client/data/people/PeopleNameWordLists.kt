package app.logdate.client.data.people

/** Whether [word] can never be part of a suggested name, in any letter case. */
internal fun isIgnoredNameWord(word: String): Boolean = word.lowercase() in ignoredNameWords

/**
 * Whether [word] is a month or weekday. Such a word names a person only right after a
 * [person cue][isPersonCue], as in "lunch with April".
 */
internal fun isCalendarWord(word: String): Boolean = word.lowercase() in calendarWords

/** Whether [word] is a month that is also a common given name. */
internal fun isMonthUsedAsName(word: String): Boolean = word.lowercase() in monthsUsedAsNames

/**
 * Whether [word] is a given name that is also an ordinary word, such as "Will" or "Grace". At the
 * start of a sentence it is capitalized whatever it means, so it does not count as a name there.
 */
internal fun isOrdinaryWordName(word: String): Boolean = word.lowercase() in ordinaryWordNames

/** Whether [word] is one that is usually followed by a person, such as "with" or "met". */
internal fun isPersonCue(word: String): Boolean = word.lowercase() in personCues

internal val titlesBeforeNames = setOf("dr", "mr", "mrs", "ms", "mx", "prof")

private val months =
    wordSet(
        """
        january february march april may june july august september october november december
        feb mar apr jun jul aug sep sept oct nov dec
        """.trimIndent(),
    )

private val weekdays =
    wordSet(
        """
        monday tuesday wednesday thursday friday saturday sunday
        mon tue tues wed thu thur thurs fri sat sun
        """.trimIndent(),
    )

private val calendarWords = months + weekdays

private val monthsUsedAsNames = wordSet("april may june august")

private val personCues = wordSet("with saw see seeing met meet called texted told asked visited")

private val pronouns =
    wordSet(
        """
        i me my mine myself we us our ours ourselves you your yours yourself yourselves
        he him his himself she her hers herself it its itself they them their theirs themselves
        this that these those who whom whose what which
        someone somebody everyone everybody anyone anybody nobody
        something everything anything nothing
        """.trimIndent(),
    )

private val sentenceOpeners =
    wordSet(
        """
        a an the and but or nor so yet for if then than when while where why how
        after before because since until unless although though however therefore meanwhile
        also still just even only maybe perhaps today tonight tomorrow yesterday now later soon
        once again finally suddenly eventually anyway actually honestly luckily sadly hopefully
        apparently basically overall yes no not ok okay well oh hey hi hello thanks thank please
        sorry dear here there at in on to of by as from with without about into over under
        during between through is are was were am be been being do does did have has had
        would could should might must let got went woke made met saw spent started felt feeling
        going getting all some any each every both most many much more few another other one
        first last next
        """.trimIndent(),
    )

private val holidays =
    wordSet(
        """
        christmas xmas thanksgiving easter halloween hanukkah chanukah passover ramadan eid diwali
        kwanzaa lent
        """.trimIndent(),
    )

private val nationalitiesAndLanguages =
    wordSet(
        """
        african american arabic asian australian brazilian british canadian chinese dutch english
        european french german greek indian irish italian japanese korean mexican polish
        portuguese russian scottish spanish swedish thai turkish vietnamese welsh
        """.trimIndent(),
    )

private val ordinaryWordNames =
    wordSet(
        """
        will grace mark hope joy rose bill pat art dawn faith sue rob jack drew chase hunter
        summer autumn dean frank grant miles max nick guy don ray rich josh bob carol penny
        holly ivy lily daisy olive violet iris ruby pearl amber robin heather angel mason carter
        cooper parker taylor piper harper
        """.trimIndent(),
    )

private val ignoredNameWords =
    months + weekdays + pronouns + sentenceOpeners + titlesBeforeNames + holidays + nationalitiesAndLanguages +
        wordSet("miss sir logdate wi-fi")

private fun wordSet(words: String): Set<String> = words.split(' ', '\n').filter(String::isNotEmpty).toSet()
