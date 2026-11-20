package no.nav.reops.filter

/**
 * Validation of Norwegian national identity numbers (11 digits: DDMMYY + individual number + two check digits).
 *
 * Accepted variants:
 * - fødselsnummer: real date
 * - d-nummer: day + 40
 * - synthetic Dolly identities: month + 40 (also covers H-nummer, which uses the same encoding)
 * - synthetic Tenor identities (Skatteetaten test data): month + 80
 */
internal object Fnr {
    private const val LABEL = "[PROXY-FNR]"

    private val K1_WEIGHTS = intArrayOf(3, 7, 6, 1, 8, 9, 4, 5, 2)
    private val K2_WEIGHTS = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)
    private val DAYS_IN_MONTH = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    private val MATCH = Regex("${FilterPatterns.FNR_CONTEXT_REGEX.pattern}|${FilterPatterns.FNR_REGEX.pattern}")
    private val URL_ESCAPE = Regex("%[0-9a-fA-F]{2}")

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

    /** Masks fnr after a context word (also invalid ones) and valid fnr elsewhere. */
    fun redact(text: String): String = replaceMatches(text, text) { it }

    fun redactUrl(url: String): String {
        if ('%' !in url) return redact(url)

        // Match against decoded ASCII escapes, while mapping replacements back to the original URL.
        val offsets = IntArray(url.length + 1)
        val decoded = StringBuilder(url.length)
        var index = 0
        for (escape in URL_ESCAPE.findAll(url)) {
            while (index < escape.range.first) {
                offsets[decoded.length] = index
                decoded.append(url[index++])
            }
            offsets[decoded.length] = index
            val byte = escape.value.substring(1).toInt(16)
            // Treat non-ASCII bytes as opaque letters, not as separators inside encoded words.
            decoded.append(if (byte < 128) byte.toChar() else '\u00AA')
            index += 3
        }
        while (index < url.length) {
            offsets[decoded.length] = index
            decoded.append(url[index++])
        }
        offsets[decoded.length] = url.length
        return replaceMatches(url, decoded) { offsets[it] }
    }

    private fun replaceMatches(original: String, view: CharSequence, offset: (Int) -> Int): String = buildString {
        var copied = 0
        for (match in MATCH.findAll(view)) {
            val prefix = match.groups[1]
            if (prefix == null && !isValid(match.value)) continue
            val start = match.range.first + (prefix?.value?.length ?: 0)
            append(original, copied, offset(start))
            append(LABEL)
            copied = offset(match.range.last + 1)
        }
        append(original, copied, original.length)
    }

    private fun hasValidDate(d: IntArray): Boolean {
        var day = d[0] * 10 + d[1]
        var month = d[2] * 10 + d[3]
        val year = d[4] * 10 + d[5]
        if (day > 40) day -= 40
        if (month > 80) month -= 80 else if (month > 40) month -= 40
        if (month == 2 && day == 29) return year % 4 == 0 && (year != 0 || d[6] >= 5)
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
