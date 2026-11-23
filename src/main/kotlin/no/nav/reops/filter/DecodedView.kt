package no.nav.reops.filter

import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * A percent-decoded copy of a text, with a map back to the original. Regexes run on [text] so that
 * "%2F", "%20", "%3A" and UTF-8 sequences like "%C3%B8" count as the characters they stand for,
 * while [original] gives the span to replace in the untouched input.
 * Sequences that are not valid percent escapes or valid UTF-8 are kept as they are.
 */
internal class DecodedView private constructor(
    val text: String,
    private val starts: IntArray?,
    private val ends: IntArray?
) {
    /** Maps a range in [text] to the range it covers in the original input. */
    fun original(range: IntRange): IntRange =
        if (starts == null || ends == null) range else starts[range.first]..(ends[range.last] - 1)

    companion object {
        fun of(input: String): DecodedView {
            if ('%' !in input) return DecodedView(input, null, null)
            val sb = StringBuilder(input.length)
            val starts = IntArray(input.length)
            val ends = IntArray(input.length)
            var i = 0
            fun add(c: Char, from: Int, to: Int) {
                starts[sb.length] = from
                ends[sb.length] = to
                sb.append(c)
            }
            while (i < input.length) {
                val b = byteAt(input, i)
                if (b < 0) {
                    add(input[i], i, i + 1)
                    i++
                    continue
                }
                if (b < 0x80) {
                    add(b.toChar(), i, i + 3)
                    i += 3
                    continue
                }
                val n = when (b) {
                    in 0xC2..0xDF -> 2
                    in 0xE0..0xEF -> 3
                    in 0xF0..0xF4 -> 4
                    else -> 0
                }
                val decoded = if (n == 0) null else decodeUtf8(input, i, n)
                if (decoded == null) {
                    add(input[i], i, i + 1)
                    i++
                } else {
                    for (c in decoded) add(c, i, i + 3 * n)
                    i += 3 * n
                }
            }
            return DecodedView(sb.toString(), starts, ends)
        }

        private fun byteAt(s: String, i: Int): Int {
            if (s[i] != '%' || i + 2 >= s.length) return -1
            val hi = Character.digit(s[i + 1], 16)
            val lo = Character.digit(s[i + 2], 16)
            return if (hi < 0 || lo < 0) -1 else hi * 16 + lo
        }

        private fun decodeUtf8(s: String, from: Int, n: Int): String? {
            val bytes = ByteArray(n)
            for (k in 0 until n) {
                val b = byteAt(s, from + 3 * k)
                if (b < 0 || (k > 0 && b !in 0x80..0xBF)) return null
                bytes[k] = b.toByte()
            }
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }
    }
}
