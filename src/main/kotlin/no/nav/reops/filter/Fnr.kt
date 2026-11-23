package no.nav.reops.filter

/**
 * Validation of Norwegian national identity numbers (11 digits: DDMMYY + individual number + two check digits).
 *
 * Accepted variants:
 * - fødselsnummer: real date
 * - d-nummer: day + 40
 * - synthetic Dolly identities: month + 40 (also covers H-nummer, which uses the same encoding)
 * - synthetic Tenor identities (Skatteetaten test data): month + 80
 *
 * Sources:
 * - Check digit weights, d-nummer and synthetic (month + 80) numbers: Skatteetaten's validator,
 *   https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/dokumenter/foedselsEllerDNummerValidator.js
 * - Check digit calculation: https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/nytt-fodselsnummer-fra-2032/
 * - Tenor (+80): https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/test-for-konsumenter/
 * - Dolly (month + 40) and H-nummer: not confirmed against a published source; Skatteetaten's validator
 *   only accepts +80 for synthetic numbers. Kept on purpose so that these numbers are masked too.
 */
internal object Fnr {
    private const val LABEL = "[PROXY-FNR]"

    private val K1_WEIGHTS = intArrayOf(3, 7, 6, 1, 8, 9, 4, 5, 2)
    private val K2_WEIGHTS = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)
    private val DAYS_IN_MONTH = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    /** [candidate] may contain separators (space, dash, dot); only the digits are validated. */
    fun isValid(candidate: String): Boolean {
        val d = IntArray(11)
        var n = 0
        for (c in candidate) {
            if (c !in '0'..'9') continue
            if (n == 11) return false
            d[n++] = c - '0'
        }
        return n == 11 && hasValidDate(d) && checkDigit(d, K1_WEIGHTS) == d[9] && checkDigit(d, K2_WEIGHTS) == d[10]
    }

    /**
     * Masks fnr after a context word (also invalid ones) and valid fnr elsewhere.
     * Matching runs on a percent-decoded view, so "%2F", "%20" and "%C3%B8" count as the characters
     * they stand for; only the matched span is replaced, so the encoding around it is unchanged.
     */
    fun redact(text: String): String =
        redactSpans(redactSpans(text, FilterPatterns.FNR_CONTEXT_REGEX) { m ->
            m.groups["num"]!!.range
        }, FilterPatterns.FNR_REGEX) { m -> if (isValid(m.value)) m.range else null }

    private fun redactSpans(text: String, regex: Regex, span: (MatchResult) -> IntRange?): String {
        val view = DecodedView.of(text)
        val out = StringBuilder()
        var last = 0
        for (m in regex.findAll(view.text)) {
            val range = view.original(span(m) ?: continue)
            out.append(text, last, range.first).append(LABEL)
            last = range.last + 1
        }
        return if (last == 0) text else out.append(text, last, text.length).toString()
    }

    private fun hasValidDate(d: IntArray): Boolean {
        var day = d[0] * 10 + d[1]
        var month = d[2] * 10 + d[3]
        // Year 00 counts as the leap year 2000, as in Skatteetaten's validator: from 2032 the century
        // can no longer be read from the individual number, so a missed identity is worse than a false match.
        val year = d[4] * 10 + d[5]
        if (day > 40) day -= 40
        if (month > 80) month -= 80 else if (month > 40) month -= 40
        if (month == 2 && day == 29) return year % 4 == 0
        return month in 1..12 && day in 1..DAYS_IN_MONTH[month - 1]
    }

    // Returns -1 when the remainder gives check digit 10, which is never issued.
    // TODO before 2032: numbers issued from 1.1.2032 accept remainder 0-3 for K1
    // (https://skatteetaten.github.io/folkeregisteret-api-dokumentasjon/nytt-fodselsnummer-fra-2032/).
    private fun checkDigit(d: IntArray, weights: IntArray): Int {
        var sum = 0
        for (i in weights.indices) sum += weights[i] * d[i]
        val k = 11 - sum % 11
        return when (k) {
            11 -> 0
            10 -> -1
            else -> k
        }
    }
}
