package io.github.liquidlyf.core.domain

/**
 * A Bangladeshi mobile number as people read it (L10N-01, the maintainer's choice): "+88 01700-000000",
 * the +88 kept and the local 11 digits grouped 5–6. Bangla screens get Bangla digits. Calling,
 * copying and saving always use the full stored number ("+8801700000000"); this is display only.
 * A no-break space and a no-break hyphen keep it on one line (D's part 2, Low 2: it broke after "+৮৮").
 */
object PhoneDisplay {
    private val BD = Regex("""^\+880(\d{10})$""")

    fun format(e164: String, banglaDigits: Boolean = false): String {
        val local = BD.matchEntire(e164)?.groupValues?.get(1)?.let { "0$it" } ?: return e164
        val shown = "+88\u00A0${local.take(5)}\u2011${local.drop(5)}"
        return if (banglaDigits) shown.map { c -> if (c in '0'..'9') '০' + (c - '0') else c }.joinToString("") else shown
    }
}
