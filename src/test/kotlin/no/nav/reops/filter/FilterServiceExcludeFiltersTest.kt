package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class FilterServiceExcludeFiltersTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    @Test
    fun `filterEvent redacts all data keys with no exclusions`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(
                    mapOf(
                        "komponent" to "header",
                        "lenketekst" to "some link text",
                        "skjemanavn" to "Sykepenger - Journalnotat fra lege - 12345678901",
                        "valg" to "Oppfølging - Sak FNR:12345678901"
                    )
                )
            )
        )

        val out = service.filterEvent(event)
        assertEquals("header", out.payload.data!!.get("komponent").asString())
        assertEquals("some link text", out.payload.data.get("lenketekst").asString())
        assertEquals(
            "Sykepenger - Journalnotat fra lege - [PROXY-FNR]",
            out.payload.data.get("skjemanavn").asString()
        )
        assertEquals(
            "Oppfølging - Sak FNR:[PROXY-FNR]",
            out.payload.data.get("valg").asString()
        )
    }

    @Test
    fun `filterEvent redacts FNR in nested object under any key`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(
                data = TestEventFactory.jsonNode(
                    mapOf(
                        "komponent" to mapOf(
                            "inner" to "contains 12345678901 fnr"
                        )
                    )
                )
            )
        )

        val out = service.filterEvent(event)
        assertEquals(
            "contains [PROXY-FNR] fnr",
            out.payload.data!!.get("komponent").get("inner").asString()
        )
    }

    @Test
    fun `filterEvent redacts url`() {
        val service = filterService()

        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event", payload = base.payload.copy(url = "/search?email=user@example.com&phone=98765432")
        )

        val out = service.filterEvent(event)
        assertNotNull(out.payload.url)
    }
}