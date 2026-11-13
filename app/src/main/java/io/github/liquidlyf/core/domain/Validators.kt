package io.github.liquidlyf.core.domain

import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Input checks that match firestore.rules, so a form never sends a write the rules reject.
 * Every limit here has the same number in the rules (`str(v, min, max)`, `bdPhone`, `isCode`…).
 * Lengths are String.length (UTF-16 units), exactly what size() counts in the rules: 500 Bangla
 * letters pass, an emoji counts as 2 (pinned by rules tests). Don't switch to code points.
 */
object Validators {

    const val NAME_MIN = 2
    const val NAME_MAX = 60
    const val DISPLAY_NAME_MAX = 40
    const val HOSPITAL_NAME_MIN = 2
    const val HOSPITAL_NAME_MAX = 120
    const val HOSPITAL_ADDRESS_MAX = 200
    const val NOTES_MAX = 500
    const val OFFER_MESSAGE_MAX = 300
    const val PATIENT_NAME_MAX = 60
    const val WARD_OR_BED_MAX = 40
    const val CLOSED_REASON_MAX = 200
    const val REPORT_NOTE_MAX = 500
    const val UNITS_MIN = 1
    const val UNITS_MAX = 10
    const val MIN_PROFILE_AGE = 18
    const val MAX_PROFILE_AGE = 100
    const val MAX_HIDDEN_OR_BLOCKED = 200

    /** `neededBy` must be within 30 days of creation, and the request expires 24 h after it. */
    val MAX_NEEDED_BY_AHEAD: Duration = Duration.ofDays(30)
    val EXPIRES_AFTER_NEEDED_BY: Duration = Duration.ofHours(24)

    /**
     * Head room for the rules' `neededBy > request.time`: the server's clock reads the write
     * later than the phone made it (slow network, offline queue, a phone clock a bit behind).
     */
    val NEEDED_BY_MIN_LEAD: Duration = Duration.ofMinutes(15)

    private val BD_MOBILE = Regex("""^\+8801[3-9]\d{8}$""")
    private val CODE = Regex("^[A-Za-z0-9_-]{1,40}$")
    private val ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val SEPARATORS = Regex("""[\s\-().]""")
    private val WHITESPACE = Regex("""\s+""")

    /** Same words, same order as `moneyWords` in firestore.rules. */
    private val MONEY_WORDS = Regex("bkash|bikash|nagad|rocket|taka|payment|টাকা|বিকাশ|নগদ|রকেট")

    /**
     * A Bangladeshi mobile number in E.164 (`+8801XXXXXXXXX`), or null if it isn't one.
     * Accepts what people type: `017…`, `8801…`, `+880 17…`, `00880…`, a missing leading 0,
     * spaces, dashes, brackets and Bangla digits.
     */
    fun normalizePhone(input: String): String? {
        val digits = input.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }
            .joinToString("")
            .replace(SEPARATORS, "")
        val local = when {
            digits.startsWith("+880") -> digits.removePrefix("+880")
            digits.startsWith("00880") -> digits.removePrefix("00880")
            digits.startsWith("880") && digits.length == 13 -> digits.removePrefix("880")
            else -> digits
        }
        val tenDigits = if (local.length == 11 && local.startsWith("0")) local.drop(1) else local
        return "+880$tenDigits".takeIf { BD_MOBILE.matches(it) }
    }

    fun isBdPhone(phone: String): Boolean = BD_MOBILE.matches(phone)

    /** New passwords: Firebase accepts 6, the app asks for 8. */
    const val PASSWORD_MIN = 8

    private val EMAIL = Regex("""^[^@\s]+@[^@\s.]+(\.[^@\s.]+)+$""")

    /** A plausible email; Firebase has the final say. */
    fun isValidEmail(email: String): Boolean = email.trim().length <= 254 && EMAIL.matches(email.trim())

    /** Trims and collapses inner spaces. Store names in this form. */
    fun normalizeName(input: String): String = input.trim().split(WHITESPACE).joinToString(" ")

    fun isValidName(name: String): Boolean = normalizeName(name).length in NAME_MIN..NAME_MAX

    /**
     * The public name: first name and last initial ("Rahim K."), never the full name
     * (doc 07 §2). At most [DISPLAY_NAME_MAX] chars. Leading abbreviations such as "Md.",
     * "Mst." or "মোঃ" are skipped, or half the cards would read "Md. K.".
     */
    fun displayName(fullName: String): String {
        val words = normalizeName(fullName).split(' ').filter { it.isNotEmpty() }
        val withoutPrefixes = words.dropWhile(::isNamePrefix).ifEmpty { words.takeLast(1) }
        val short = shortName(withoutPrefixes)
        // "Md. X" would leave just "X"; the rules need at least 2 chars.
        return if (short.length >= NAME_MIN) short else shortName(words)
    }

    private fun shortName(words: List<String>): String {
        if (words.isEmpty()) return ""
        val first = words.first()
        if (words.size == 1) return first.take(DISPLAY_NAME_MAX)
        val last = words.last()
        val initial = last.substring(0, last.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
        val suffix = " $initial."
        return first.take(DISPLAY_NAME_MAX - suffix.length) + suffix
    }

    private val NAME_PREFIXES = setOf("md", "mst", "mohd", "dr")

    /** "Md.", "MD", "Dr.", "মোঃ", "মো." and any other word ending in '.' or 'ঃ'. */
    private fun isNamePrefix(word: String): Boolean =
        word.endsWith('.') || word.endsWith('ঃ') || word.lowercase(Locale.ROOT) in NAME_PREFIXES

    fun isValidHospitalName(name: String): Boolean = name.trim().length in HOSPITAL_NAME_MIN..HOSPITAL_NAME_MAX

    /** Optional text: empty is fine, up to [max] chars. */
    fun fitsOptional(text: String?, max: Int): Boolean = text == null || text.length <= max

    fun isValidUnits(units: Int): Boolean = units in UNITS_MIN..UNITS_MAX

    /** A request whose notes mention money must carry `flagged = true` (the rules check it). */
    fun hasMoneyWords(text: String?): Boolean =
        text != null && MONEY_WORDS.containsMatchIn(text.lowercase(Locale.ROOT))

    /** Profile age 18–100 from the birth year, in UTC like `request.time.year()` in the rules. */
    fun isValidBirthYear(birthYear: Int, now: Instant): Boolean =
        EligibilityCalculator.ageIn(birthYear, now) in MIN_PROFILE_AGE..MAX_PROFILE_AGE

    /** Needed by: at least [NEEDED_BY_MIN_LEAD] from now and less than 30 days ahead. */
    fun isValidNeededBy(neededBy: Instant, now: Instant): Boolean =
        !neededBy.isBefore(now.plus(NEEDED_BY_MIN_LEAD)) && neededBy.isBefore(now.plus(MAX_NEEDED_BY_AHEAD))

    /** The request's `expiresAt`, which the rules require to be exactly neededBy + 24 h. */
    fun expiresAt(neededBy: Instant): Instant = neededBy.plus(EXPIRES_AFTER_NEEDED_BY)

    /** Area codes (division, district, upazila/thana): they go into FCM topic names. */
    fun isCode(value: String?): Boolean = value != null && CODE.matches(value)

    /** Document IDs (uids, request IDs). */
    fun isId(value: String?): Boolean = value != null && ID.matches(value)
}
