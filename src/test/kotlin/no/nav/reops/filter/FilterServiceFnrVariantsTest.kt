package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class FilterServiceFnrVariantsTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redact(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
    }

    @Test
    fun `redacts fnr basic`() {
        assertEquals("SSN: [PROXY-FNR]", redact("SSN: 15819010029"))
    }

    @Test
    fun `redacts fnr after underscore delimiter`() {
        assertEquals("my_fnr_[PROXY-FNR]", redact("my_fnr_15819010029"))
    }

    @Test
    fun `redacts fnr after colon delimiter`() {
        assertEquals("my-fnr:[PROXY-FNR] it's nice", redact("my-fnr:15819010029 it's nice"))
    }

    @Test
    fun `redacts fnr after hyphen delimiter`() {
        assertEquals("my-fnr-[PROXY-FNR]", redact("my-fnr-15819010029"))
    }

    @Test
    fun `redacts fnr after dot delimiter`() {
        assertEquals("fnr.[PROXY-FNR]", redact("fnr.15819010029"))
    }

    // ── separated fnr variants ───────────────────────────────────────
    //
    // People routinely write fødselsnummer with a space, dash or dot between the
    // birth-date part and the person-number part ("158190 10029"), or as a dotted
    // date ("15.81.90 10029"). Wider patterns would false-positive on dates,
    // measurements and other innocent digit clusters; outside a context word,
    // a match is only masked if the date and check digits are valid.

    @Test
    fun `redacts fnr with space separator`() {
        assertEquals("SSN: [PROXY-FNR]", redact("SSN: 158190 10029"))
    }

    @Test
    fun `redacts fnr with dash separator`() {
        assertEquals("SSN: [PROXY-FNR]", redact("SSN: 158190-10029"))
    }

    @Test
    fun `does not redact two unrelated short digit groups`() {
        // 3+3 digits is not an fnr shape; must stay untouched
        assertEquals("vekt 123 456 gram", redact("vekt 123 456 gram"))
    }

    @Test
    fun `does not redact date-like digit groups`() {
        // ISO-ish date fragments are not fnr
        assertEquals("2026 10 14", redact("2026 10 14"))
    }

    @Test
    fun `redacts fnr after slash delimiter`() {
        assertEquals("fnr/[PROXY-FNR]", redact("fnr/15819010029"))
    }

    @Test
    fun `redacts fnr after space delimiter`() {
        assertEquals("fnr [PROXY-FNR]", redact("fnr 15819010029"))
    }

    @Test
    fun `redacts fnr after pipe delimiter`() {
        assertEquals("fnr|[PROXY-FNR]", redact("fnr|15819010029"))
    }

    @Test
    fun `redacts fnr after plus delimiter`() {
        assertEquals("fnr+[PROXY-FNR]", redact("fnr+15819010029"))
    }

    @Test
    fun `redacts fnr after hash delimiter`() {
        assertEquals("fnr#[PROXY-FNR]", redact("fnr#15819010029"))
    }

    @Test
    fun `redacts fnr after at delimiter`() {
        assertEquals("fnr@[PROXY-FNR]", redact("fnr@15819010029"))
    }

    @Test
    fun `redacts fnr after parenthesis`() {
        assertEquals("(fnr)[PROXY-FNR]", redact("(fnr)15819010029"))
    }

    @Test
    fun `redacts fnr after square brackets`() {
        assertEquals("[fnr][PROXY-FNR]", redact("[fnr]15819010029"))
    }

    @Test
    fun `redacts fnr after curly braces`() {
        assertEquals("{fnr}[PROXY-FNR]", redact("{fnr}15819010029"))
    }

    @Test
    fun `redacts uuid that contains fnr-like suffix`() {
        val input = "AD748BD6-484B-416C-B444-a12345678901"
        assertEquals("[PROXY-UUID]", redact(input))
    }

    @Test
    fun `redacts fnr inside url in free text`() {
        val input = "See https://nav.no/person/15819010029/details for info"
        assertEquals("See https://nav.no/person/[PROXY-FNR]/details for info", redact(input))
    }

    @Test
    fun `redacts fnr inside domain path url in free text`() {
        val input = "Link: example.com/user/15819010029/profile"
        assertEquals("Link: example.com/user/[PROXY-FNR]/profile", redact(input))
    }

    @Test
    fun `redacts numeric fnr value in json data`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(mapOf("kandidatnr" to 15819010029L))
            )
        )
        val out = service.filterEvent(event)
        assertEquals("[PROXY-FNR]", out.payload.data!!.get("kandidatnr").asString())
    }

    @Test
    fun `preserves non-fnr numeric values`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(mapOf("count" to 42, "big" to 123456789012L))
            )
        )
        val out = service.filterEvent(event)
        assertEquals(42, out.payload.data!!.get("count").asInt())
        // 12-digit number should not be redacted
        assertEquals(123456789012L, out.payload.data.get("big").asLong())
    }

    @Test
    fun `redacts fnr in payload id field but preserves other content`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(id = "user-15819010029-session")
        )
        val out = service.filterEvent(event)
        assertEquals("user-[PROXY-FNR]-session", out.payload.id)
    }

    @Test
    fun `preserves uuid in payload id field`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val event = base.copy(
            type = "event", payload = base.payload.copy(id = uuid)
        )
        val out = service.filterEvent(event)
        assertEquals(uuid, out.payload.id)
    }

    // ── validation ───────────────────────────────────────────────────

    @Test
    fun `redacts d-nummer and Dolly identities`() {
        assertEquals("[PROXY-FNR] og [PROXY-FNR]", redact("41819010186 og 23489010089"))
    }

    @Test
    fun `redacts dotted fnr formats`() {
        assertEquals(
            "[PROXY-FNR], [PROXY-FNR], [PROXY-FNR]",
            redact("158190.10029, 15.81.90 10029, 15.81.90.10029")
        )
    }

    @Test
    fun `invalid 11-digit number is left for later rules`() {
        // Not an fnr (wrong check digit), but still matches the account pattern
        assertEquals("ordre [PROXY-ACCOUNT]", redact("ordre 15819010028"))
    }

    @Test
    fun `invalid separated number is not redacted as fnr`() {
        assertEquals("ordre 158190 10028", redact("ordre 158190 10028"))
    }

    @Test
    fun `valid fnr glued to letters is not redacted as fnr`() {
        assertFalse(redact("abc15819010029").contains("[PROXY-FNR]"))
        assertFalse(redact("15819010029abc").contains("[PROXY-FNR]"))
    }

    @Test
    fun `context word masks fnr even with invalid check digits`() {
        assertEquals("fnr: [PROXY-FNR]", redact("fnr: 12345678901"))
        assertEquals("Fødselsnummer [PROXY-FNR]", redact("Fødselsnummer 158190 10028"))
        assertEquals("d-nr=[PROXY-FNR]", redact("d-nr=12345678901"))
        assertEquals("personnummer:[PROXY-FNR]", redact("personnummer:12345678901"))
    }

    @Test
    fun `context word masks fnr glued to the word`() {
        assertEquals("fnr[PROXY-FNR]", redact("fnr12345678901"))
    }

    @Test
    fun `context word inside another word does not count`() {
        // "kundnr" is not a context word, and the number is invalid, so only account applies
        assertEquals("kundnr [PROXY-ACCOUNT]", redact("kundnr 12345678901"))
    }

    @Test
    fun `context word does not mask longer digit runs`() {
        assertEquals("fnr 123456789012", redact("fnr 123456789012"))
    }

    @Test
    fun `invalid number in free-text url is not redacted as fnr`() {
        val out = redact("Se https://nav.no/sak/15819010028/info")
        assertFalse(out.contains("[PROXY-FNR]"), out)
    }

    @Test
    fun `invalid number in payload id is kept`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(type = "event", payload = base.payload.copy(id = "ordre-15819010028"))
        assertEquals("ordre-15819010028", service.filterEvent(event).payload.id)
    }

    @Test
    fun `redacts 10-digit json number that lost its leading zero`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(mapOf("kandidatnr" to 3859510034L))
            )
        )
        assertEquals("[PROXY-FNR]", service.filterEvent(event).payload.data!!.get("kandidatnr").asString())
    }

    @Test
    fun `keeps json numbers that are not valid fnr`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(
                    mapOf("ordre" to 15819010028L, "epochSeconds" to 1700000000L, "epochMs" to 1700000000000L)
                )
            )
        )
        val data = service.filterEvent(event).payload.data!!
        assertEquals(15819010028L, data.get("ordre").asLong())
        assertEquals(1700000000L, data.get("epochSeconds").asLong())
        assertEquals(1700000000000L, data.get("epochMs").asLong())
    }

    @Test
    fun `fnr counter only counts valid matches`() {
        val registry = SimpleMeterRegistry()
        val service = FilterService(registry)
        val base = TestEventFactory.minimalEvent()
        fun event(text: String) = base.copy(
            payload = base.payload.copy(
                screen = "1920x1080", url = "https://kake.no/", data = TestEventFactory.jsonNode(mapOf("text" to text))
            )
        )

        service.filterEvent(event("ordre 15819010028"))
        assertEquals(0.0, fnrCount(registry, "fnr"))

        service.filterEvent(event("15819010029"))
        assertEquals(1.0, fnrCount(registry, "fnr"))

        service.filterEvent(event("fnr: 12345678901"))
        assertEquals(1.0, fnrCount(registry, "fnr_context"))
        assertEquals(1.0, fnrCount(registry, "fnr"))
    }

    private fun fnrCount(registry: SimpleMeterRegistry, rule: String): Double =
        registry.find("redactions_total").tag("rule", rule).counter()?.count() ?: 0.0
}
