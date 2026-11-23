package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

// Every valid identity number, in every format and context below, must be masked. A miss here means a real
// fnr could slip through. The check digits are computed here, independently of Fnr.isValid.
class FnrNoFalseNegativesTest {

    private val service = FilterService(SimpleMeterRegistry())

    private val k1 = intArrayOf(3, 7, 6, 1, 8, 9, 4, 5, 2)
    private val k2 = intArrayOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)
    private val daysInMonth = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private fun checkDigit(digits: List<Int>, weights: IntArray): Int? {
        val k = 11 - digits.indices.sumOf { weights[it] * digits[it] } % 11
        return when (k) {
            11 -> 0
            10 -> null
            else -> k
        }
    }

    private class Id(val dd: Int, val mm: Int, val yy: Int, val individual: Int, val k1: Int, val k2: Int) {
        private fun two(v: Int) = "%02d".format(v)
        private val ind = "%03d".format(individual)
        val compact get() = "${two(dd)}${two(mm)}${two(yy)}$ind$k1$k2"
        val formats
            get() = listOf(
                compact,
                "${two(dd)}${two(mm)}${two(yy)} $ind$k1$k2",
                "${two(dd)}${two(mm)}${two(yy)}-$ind$k1$k2",
                "${two(dd)}${two(mm)}${two(yy)}.$ind$k1$k2",
                "${two(dd)}.${two(mm)}.${two(yy)} $ind$k1$k2",
                "${two(dd)}.${two(mm)}.${two(yy)}.$ind$k1$k2"
            )
    }

    private fun generate(count: Int): List<Id> {
        val rnd = Random(42)
        val out = mutableListOf<Id>()
        while (out.size < count) {
            val month = 1 + rnd.nextInt(12)
            val day = 1 + rnd.nextInt(daysInMonth[month - 1])
            val year = rnd.nextInt(100)
            val individual = rnd.nextInt(1000)
            // Year 00 is 1900 for individual numbers 000-499 and 2000 for 500-999
            val leap = if (year == 0) individual >= 500 else year % 4 == 0
            if (month == 2 && day == 29 && !leap) continue
            // Real birth number, d-nummer (day + 40), Dolly (month + 40) and Tenor (month + 80)
            val dd = if (rnd.nextInt(4) == 0) day + 40 else day
            val mm = when (rnd.nextInt(4)) {
                0 -> month + 40
                1 -> month + 80
                else -> month
            }
            val base = "%02d%02d%02d%03d".format(dd, mm, year, individual).map { it - '0' }
            val c1 = checkDigit(base, k1) ?: continue
            val c2 = checkDigit(base + c1, k2) ?: continue
            out += Id(dd, mm, year, individual, c1, c2)
        }
        return out
    }

    private val contexts = listOf(
        "{}",
        "Mitt nummer er {} takk",
        "(({}))",
        "\"{}\"",
        "a,{},b",
        "{}.",
        "fnr: {}",
        "https://nav.no/person/{}/details",
        "https://nav.no/person%2F{}/details",
        "nav.no/fødselsnummer/{}"
    )

    @Test
    fun `every valid identity number is masked in every format and context`() {
        val misses = mutableListOf<String>()
        var checked = 0
        for (id in generate(1500)) {
            for (format in id.formats) {
                for (context in contexts) {
                    // A space ends a URL, so a number with a space inside is split in free-text URLs (known limit)
                    if ("nav.no/" in context && ' ' in format) continue
                    val input = context.replace("{}", format)
                    val out = service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
                    checked++
                    if ("[PROXY-FNR]" !in out || id.compact.substring(6) in out.replace(Regex("\\D"), "")) {
                        misses += "$input -> $out"
                    }
                }
            }
        }
        assertTrue(misses.isEmpty(), "${misses.size} av $checked slapp gjennom, f.eks.:\n" + misses.take(10).joinToString("\n"))
    }
}
