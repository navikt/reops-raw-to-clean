package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FilterServiceNameRedactionTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redact(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
    }

    @Test
    fun `redacts two-word name`() {
        assertEquals("[PROXY-NAME]", redact("Ola Nordmann"))
    }

    @Test
    fun `redacts three-word name`() {
        assertEquals("[PROXY-NAME]", redact("Ola Per Nordmann"))
    }

    // ── long chains of capitalized words ─────────────────────────────
    //
    // Names have no standard shape — any number of words, from any culture.
    // Today's pattern stops at 3 words, leaking the tail of longer names
    // ("Ola Nordmann Hansen Berg" -> "[PROXY-NAME] Berg"). A long chain of
    // capitalized words is unlikely to be useful analytics content anyway,
    // so once two capitalized words have matched, slurp the rest of the chain.
    // Accepted collateral: strings like book titles in title-case get masked
    // too — safe direction.

    @Test
    fun `redacts four-word name fully`() {
        assertEquals("[PROXY-NAME]", redact("Ola Nordmann Hansen Berg"))
    }

    @Test
    fun `redacts five-word name fully`() {
        assertEquals("[PROXY-NAME]", redact("Anne Marie Kristin Lise Beate"))
    }

    @Test
    fun `redacts name chain embedded in prose`() {
        assertEquals(
            "snakket med [PROXY-NAME] i gaar",
            redact("snakket med Ola Nordmann Hansen Berg i gaar")
        )
    }

    @Test
    fun `Hele Norge is not redacted because Norge is excluded`() {
        val result = redact("Hele Norge")
        assertEquals("Hele Norge", result)
    }

    @Test
    fun `Bank Norge is not redacted because Norge is excluded`() {
        val result = redact("Bank Norge")
        assertEquals("Bank Norge", result)
    }

    @Test
    fun `Norge Bank is not redacted because Norge is excluded`() {
        val result = redact("Norge Bank")
        assertEquals("Norge Bank", result)
    }

    @Test
    fun `standalone Norge is not redacted`() {
        assertEquals("Norge", redact("Norge"))
    }

    @Test
    fun `Velkommen til Norge - til Norge matches as name in Kotlin`() {
        // In Rust, "Norge" is excluded from name matching.
        // In Kotlin there is no "Norge" exclusion, so "til Norge" is not a match
        // because "til" starts with lowercase. Only capitalized word pairs match.
        assertEquals("Velkommen til Norge", redact("Velkommen til Norge"))
    }

    @Test
    fun `Stor Norge Bank is not redacted because Norge is excluded`() {
        val result = redact("Stor Norge Bank")
        assertEquals("Stor Norge Bank", result)
    }

    @Test
    fun `Hele Vakre Norge is not fully redacted because Norge is excluded`() {
        val result = redact("Hele Vakre Norge")
        // "Hele Vakre" still matches as a two-word name, but "Norge" is preserved
        assertEquals("[PROXY-NAME] Norge", result)
    }

    @Test
    fun `Bosatt Norge is not redacted because both words are excluded`() {
        val result = redact("Bosatt Norge")
        assertEquals("Bosatt Norge", result)
    }
}

