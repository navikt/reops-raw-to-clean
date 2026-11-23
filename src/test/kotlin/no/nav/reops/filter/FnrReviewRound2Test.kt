package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FnrReviewRound2Test {
    private val service = FilterService(SimpleMeterRegistry())

    private fun text(input: String): String =
        service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()

    private fun data(map: Map<String, Any?>): Map<String, String> {
        val base = TestEventFactory.minimalEvent()
        val out = service.filterEvent(
            base.copy(payload = base.payload.copy(data = TestEventFactory.jsonNode(map)))
        ).payload.data!!
        return out.properties().associate { (k, v) -> k to (if (v.isString) v.asString() else v.toString()) }
    }

    private fun url(raw: String): String {
        val base = TestEventFactory.minimalEvent()
        return service.filterEvent(base.copy(payload = base.payload.copy(url = raw))).payload.url!!
    }

    // 1: up to three separators between every group
    @Test
    fun `masks padded dotted-date fnr without context`() {
        assertEquals("[PROXY-FNR]", text("15  81  90 10029"))
        assertEquals("id [PROXY-FNR] ok", text("id 15   81 90   10029 ok"))
    }

    // 2: ssn is a context word
    @Test
    fun `masks mistyped number after ssn`() {
        assertEquals("SSN: [PROXY-FNR]", text("SSN: 158190 10028"))
    }

    // 3: encoded paths in URL fields
    @Test
    fun `masks fnr in url path after encoded slash`() {
        assertEquals(
            "https://kake.no/person%2F[PROXY-FNR]/details",
            url("https://kake.no/person%2F158190-10029/details")
        )
    }

    // 4: NBSP and quotes between word and number
    @Test
    fun `masks mistyped number after nbsp and quotes`() {
        assertEquals("fnr:\u00A0[PROXY-FNR]", text("fnr:\u00A0158190 10028"))
        assertEquals("{\"fnr\":\"[PROXY-FNR]\"}", text("{\"fnr\":\"158190 10028\"}"))
    }

    // 5: colon in a path
    @Test
    fun `masks mistyped number after word and colon in url`() {
        assertEquals("Se https://nav.no/fnr:[PROXY-FNR] nå", text("Se https://nav.no/fnr:158190-10028 nå"))
    }

    // 6: encoded separators, context words and trailing letters in preserved URLs
    @Test
    fun `masks fnr with encoded space in url`() {
        assertEquals("se https://nav.no/x/[PROXY-FNR] nå", text("se https://nav.no/x/158190%2010029 nå"))
    }

    @Test
    fun `masks mistyped number after utf8 encoded context word in url`() {
        assertEquals(
            "se https://nav.no/f%C3%B8dselsnummer/[PROXY-FNR] nå",
            text("se https://nav.no/f%C3%B8dselsnummer/158190-10028 nå")
        )
    }

    @Test
    fun `masks mistyped number after encoded colon and space`() {
        assertEquals("se https://nav.no/fnr%3A%20[PROXY-FNR]", text("se https://nav.no/fnr%3A%20158190-10028"))
    }

    @Test
    fun `an encoded trailing letter is still a letter`() {
        assertEquals("se https://nav.no/x/15819010029%41 nå", text("se https://nav.no/x/15819010029%41 nå"))
    }

    @Test
    fun `decoded view maps spans back to the original`() {
        val v = DecodedView.of("a%2Fb%C3%B8c%zz%41")
        assertEquals("a/bøc%zzA", v.text)
        assertEquals(1..3, v.original(1..1))
        assertEquals(5..10, v.original(3..3))
    }

    // 7: JSON key names the fnr
    @Test
    fun `masks every value under an fnr key`() {
        val out = data(
            mapOf(
                "fnr" to 15819010028L, "userFnr" to "158190 10028", "fødselsnummer" to "158190-10028",
                "antall" to 15819010028L
            )
        )
        assertEquals("[PROXY-FNR]", out["fnr"])
        assertEquals("[PROXY-FNR]", out["userFnr"])
        assertEquals("[PROXY-FNR]", out["fødselsnummer"])
        assertEquals("15819010028", out["antall"])
    }

    @Test
    fun `masks numbers in arrays under an fnr key`() {
        assertEquals("[\"[PROXY-FNR]\"]", data(mapOf("fnr" to listOf(15819010028L)))["fnr"])
    }

    @Test
    fun `keys that only contain an fnr word are not context`() {
        val out = data(mapOf("fnrCount" to "3", "kundnummer" to "12345"))
        assertEquals("3", out["fnrCount"])
        assertEquals("12345", out["kundnummer"])
    }

    // 8: query parameters
    @Test
    fun `keeps following numeric query parameters`() {
        assertEquals("?fnr=[PROXY-FNR]&123=456&lang=nb", text("?fnr=15819010029&123=456&lang=nb"))
        assertTrue(text("x?fnr=15819010029#1234").endsWith("[PROXY-FNR]#1234"))
    }
}
