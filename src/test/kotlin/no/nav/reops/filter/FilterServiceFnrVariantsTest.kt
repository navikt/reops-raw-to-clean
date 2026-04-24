package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FilterServiceFnrVariantsTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redact(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
    }

    @Test
    fun `redacts fnr basic`() {
        assertEquals("SSN: [PROXY-FNR]", redact("SSN: 12345678901"))
    }

    @Test
    fun `redacts fnr after underscore delimiter`() {
        assertEquals("my_fnr_[PROXY-FNR]", redact("my_fnr_12345678901"))
    }

    @Test
    fun `redacts fnr after colon delimiter`() {
        assertEquals("my-fnr:[PROXY-FNR] it's nice", redact("my-fnr:12345678901 it's nice"))
    }

    @Test
    fun `redacts fnr after hyphen delimiter`() {
        assertEquals("my-fnr-[PROXY-FNR]", redact("my-fnr-12345678901"))
    }

    @Test
    fun `redacts fnr after dot delimiter`() {
        assertEquals("fnr.[PROXY-FNR]", redact("fnr.12345678901"))
    }

    @Test
    fun `redacts fnr after slash delimiter`() {
        assertEquals("fnr/[PROXY-FNR]", redact("fnr/12345678901"))
    }

    @Test
    fun `redacts fnr after space delimiter`() {
        assertEquals("fnr [PROXY-FNR]", redact("fnr 12345678901"))
    }

    @Test
    fun `redacts fnr after pipe delimiter`() {
        assertEquals("fnr|[PROXY-FNR]", redact("fnr|12345678901"))
    }

    @Test
    fun `redacts fnr after plus delimiter`() {
        assertEquals("fnr+[PROXY-FNR]", redact("fnr+12345678901"))
    }

    @Test
    fun `redacts fnr after hash delimiter`() {
        assertEquals("fnr#[PROXY-FNR]", redact("fnr#12345678901"))
    }

    @Test
    fun `redacts fnr after at delimiter`() {
        assertEquals("fnr@[PROXY-FNR]", redact("fnr@12345678901"))
    }

    @Test
    fun `redacts fnr after parenthesis`() {
        assertEquals("(fnr)[PROXY-FNR]", redact("(fnr)12345678901"))
    }

    @Test
    fun `redacts fnr after square brackets`() {
        assertEquals("[fnr][PROXY-FNR]", redact("[fnr]12345678901"))
    }

    @Test
    fun `redacts fnr after curly braces`() {
        assertEquals("{fnr}[PROXY-FNR]", redact("{fnr}12345678901"))
    }

    @Test
    fun `redacts uuid that contains fnr-like suffix`() {
        val input = "AD748BD6-484B-416C-B444-a12345678901"
        assertEquals("[PROXY-UUID]", redact(input))
    }

    @Test
    fun `redacts fnr inside url in free text`() {
        val input = "See https://nav.no/person/12345678901/details for info"
        assertEquals("See https://nav.no/person/[PROXY-FNR]/details for info", redact(input))
    }

    @Test
    fun `redacts fnr inside domain path url in free text`() {
        val input = "Link: example.com/user/12345678901/profile"
        assertEquals("Link: example.com/user/[PROXY-FNR]/profile", redact(input))
    }

    @Test
    fun `redacts numeric fnr value in json data`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(mapOf("kandidatnr" to 12345678901L))
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
            type = "event", payload = base.payload.copy(id = "user-12345678901-session")
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
}
