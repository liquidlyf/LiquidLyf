package io.github.liquidlyf.core.domain

/**
 * A Bangladeshi mobile number as people read it (L10N-01, the maintainer's choice): "+88 01700-000000",
 * the +88 kept and the local 11 digits grouped 5–6. Bangla screens get Bangla digits. Calling,
 * copying and saving always use the full stored number ("+8801700000000"); this is display only.
 * A no-break hyphen keeps the 11 digits together; the only place a line may break is after "+88"
 * (D's part 2, Low 2: it broke inside the digits).
 */
object PhoneDisplay {
    private val BD = Regex("""^\+880(\d{10})$""")

    fun format(e164: String, banglaDigits: Boolean = false): String {
        val local = BD.matchEntire(e164)?.groupValues?.get(1)?.let { "0$it" } ?: return e164
        val shown = "+88 ${local.take(5)}\u2011${local.drop(5)}"
        return if (banglaDigits) shown.map { c -> if (c in '0'..'9') '০' + (c - '0') else c }.joinToString("") else shown
    }

    /**
     * What TalkBack says for the number (D's Low 2: read it digit by digit): "+8 8, 0 1 7 0 0, 0 0 0 0 0 0",
     * so a voice says "plus eight eight, zero one seven zero zero, …" and never "one thousand seven
     * hundred". The commas pause between the groups people see. ASCII digits on Bangla screens too:
     * a Bangla voice reads them in Bangla, and an English voice (no Bangla voice installed) still can.
     */
    fun spoken(e164: String): String {
        val local = BD.matchEntire(e164)?.groupValues?.get(1)?.let { "0$it" }
        val groups = if (local != null) listOf("+88", local.take(5), local.drop(5)) else listOf(e164)
        return groups.joinToString(", ") { g ->
            val digits = g.filter { it in '0'..'9' }.toList().joinToString(" ")
            if (g.startsWith("+")) "+$digits" else digits
        }
    }
}
