package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Address redaction coverage.
 *
 * Norwegian formats (per Posten's addressing guide, posten.no/en/sending/domestic/addressing):
 *  - street + house number, then "postnummer sted"
 *  - house number may carry a letter, spaced or not
 *  - place-name addresses without a street
 *  - P.O. box, optionally including the box facility name
 *  - one-line variants join these with a comma or just spaces.
 *
 * Some international formats overlap, but these cases only specify Norwegian
 * shapes. They do not establish international coverage.
 *
 * All street and place names below are deliberately fictional examples.
 * Exact placeholders describe the desired full-address match. A different
 * rule masking part of an address is still partial success, not a total miss.
 */
class FilterServiceAddressVariantsTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redact(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
    }

    // ── postcode + place (partial form) ──────────────────────────────

    @Test
    fun `redacts postcode and place`() {
        assertEquals("Send til [PROXY-ADDRESS]", redact("Send til 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts postcode and multiword place`() {
        assertEquals("Send til [PROXY-ADDRESS]", redact("Send til 1234 Nordre Fantasisted"))
    }

    // ── street + house number + postcode + place (full common form) ───

    @Test
    fun `redacts street address with comma before postcode`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Ingenstedsgata 12A, 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts street address without comma`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Ingenstedsgata 12A 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts street address with plain house number`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Fantasigata 12, 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts street address with spaced house-number letter`() {
        // Spaced house-number letter, following Posten's example format.
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Fantasigata 15 A, 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts street address with multiword street name`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Fiktive Fantasiers Gate 27, 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts street address with multiword place`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Oppdiktede Fantasiers Vei 3B, 1234 Nordre Fantasisted"))
    }

    @Test
    fun `redacts address written on separate postal lines`() {
        assertEquals("[PROXY-ADDRESS]", redact("Fantasigata 15 A\n1234 Ingenstedsby"))
    }

    @Test
    fun `redacts multiword street with lowercase street type`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Fiktive Fantasiers gate 27"))
    }

    @Test
    fun `unrecognized street form is sufficiently hidden by name masking`() {
        // A street type outside our address heuristic may still be caught by
        // another rule. An isolated house number is an accepted residual here;
        // the street identity must not survive. Do not require a particular label.
        val output = redact("Bor i Fiktive Fantasiers Tun 27")
        assertTrue(
            output in setOf("Bor i [PROXY-ADDRESS]", "Bor i [PROXY-NAME] 27"),
            "Expected the street identity to be hidden, got: $output"
        )
    }

    // ── street + house number alone (partial form) ────────────────────
    //
    // Without the postcode the shape "<Capitalized words> <digits><letter?>"
    // is still distinctive enough — but only with a word that looks like a
    // street name. Restricting to known street-type suffixes (gate/gata/vei/
    // veien/vegen/veg/sti/stien/alle/plass/torget/...) keeps "Modell 12B" and
    // similar product strings out.

    @Test
    fun `redacts street and house number without postcode`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Ingenstedsgata 12A"))
    }

    @Test
    fun `redacts street and house number with spaced letter, no postcode`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Fantasigata 15 A"))
    }

    @Test
    fun `redacts multiword street ending in street-type word, no postcode`() {
        assertEquals("Bor i [PROXY-ADDRESS]", redact("Bor i Fiktive Fantasiers Gate 27"))
    }

    @Test
    fun `redacts lowercase and uppercase compound streets with common suffixes`() {
        val suffixes = listOf(
            "gata", "gaten", "gate", "veien", "vegen", "vei", "veg",
            "stien", "sti", "alléen", "alleen", "allé", "alle",
            "plassen", "plass", "torget", "torg", "bakken", "svingen", "lia", "tunet"
        )
        for (suffix in suffixes) {
            val street = "ingensteds$suffix"
            for (variant in listOf(street, street.uppercase())) {
                assertEquals("bor i [PROXY-ADDRESS]", redact("bor i $variant 12b"), variant)
            }
        }
    }

    @Test
    fun `lowercase compound street matching preserves surrounding prose`() {
        assertEquals("jeg bor i [PROXY-ADDRESS], ved skogen", redact("jeg bor i fantasigata 15 a, ved skogen"))
    }

    @Test
    fun `does not mistake ordinary lowercase prose for a street address`() {
        for (input in listOf(
            "jeg liker 8000 ting, men ikke byen Oslo",
            "jeg liker fantasigata uten nummer",
            "modell 12b ble laget",
            "vi fant 12 ting",
            "fantasigata 12bits"
        )) {
            assertEquals(input, redact(input))
        }
    }

    @Test
    fun `random word and house-shaped number need a street suffix to trigger redaction`() {
        for (word in listOf("flumse", "tromlo", "snurpel", "Flumse", "TROMLO")) {
            for (number in listOf("12", "12a", "12 a")) {
                val input = "$word $number"
                assertEquals(input, redact(input), "No street suffix: $input")
            }
        }
    }

    // ── P.O. box form ─────────────────────────────────────────────────

    @Test
    fun `redacts postboks address with postcode`() {
        assertEquals("Send til [PROXY-ADDRESS]", redact("Send til Postboks 55 Fantasisted, 1234 Ingenstedsby"))
    }

    @Test
    fun `redacts postboks address without box facility name`() {
        assertEquals("Send til [PROXY-ADDRESS]", redact("Send til Postboks 15, 1234 Ingenstedsby"))
    }

    // ── false-positive guards ─────────────────────────────────────────

    @Test
    fun `does not redact postcode alone`() {
        assertEquals("postnummer 1234 her", redact("postnummer 1234 her"))
    }

    @Test
    fun `does not redact product code that looks like a house number`() {
        assertEquals("Modell 12B, 2024 ble laget", redact("Modell 12B, 2024 ble laget"))
    }

    @Test
    fun `does not redact ordinary prose followed by a year`() {
        // Avoid a title-case chain: the name rule may legitimately redact one.
        assertEquals("samling for teamet i 2026", redact("samling for teamet i 2026"))
    }
}
