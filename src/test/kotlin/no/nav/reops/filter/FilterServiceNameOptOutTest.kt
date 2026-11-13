package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.reops.event.OptOutFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FilterServiceNameOptOutTest {
    private val name = "Ola Nordmann"
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `name and uuid opt outs are independent across fields and nested data`() {
        val service = FilterService(SimpleMeterRegistry())
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(
            hostname = name, screen = name, language = name, title = name, name = name,
            data = TestEventFactory.jsonNode(mapOf(
                "text" to name,
                "uuid" to uuid,
                "nested" to mapOf("name" to name, "array" to listOf(name, mapOf("name" to name, "uuid" to uuid)))
            ))
        ))

        val cases = listOf(
            Triple(null, "[PROXY-NAME]", "[PROXY-UUID]"),
            Triple(listOf(OptOutFilter.NAME), name, "[PROXY-UUID]"),
            Triple(listOf(OptOutFilter.UUID), "[PROXY-NAME]", uuid),
            Triple(listOf(OptOutFilter.NAME, OptOutFilter.UUID), name, uuid)
        )
        for ((filters, expectedName, expectedUuid) in cases) {
            val payload = service.filterEvent(event, filters).payload
            assertEquals(expectedName, payload.hostname)
            assertEquals(expectedName, payload.screen)
            assertEquals(expectedName, payload.language)
            assertEquals(expectedName, payload.title)
            assertEquals(expectedName, payload.name)
            val data = payload.data!!
            assertEquals(expectedName, data["text"].asString())
            assertEquals(expectedUuid, data["uuid"].asString())
            assertEquals(expectedName, data["nested"]["name"].asString())
            assertEquals(expectedName, data["nested"]["array"][0].asString())
            assertEquals(expectedName, data["nested"]["array"][1]["name"].asString())
            assertEquals(expectedUuid, data["nested"]["array"][1]["uuid"].asString())
        }
    }

    @Test
    fun `encoded names are preserved only when opted out while encoded email and search are redacted`() {
        val service = FilterService(SimpleMeterRegistry())
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(
            url = "/sok?person=Ola%20Nordmann&email=Ola.Nordmann%40example.com&q=Ola+Nordmann",
            referrer = "https://nav.no/tjeneste?person=Ola+Nordmann&adresse=Fantasigata+15+A%2C+1234+Ingenstedsby"
        ))
        val normal = service.filterEvent(event).payload
        assertEquals("/sok?person=[PROXY-NAME]&email=[PROXY-EMAIL][PROXY-SEARCH]", normal.url)
        assertEquals("https://nav.no/tjeneste?person=[PROXY-NAME]&adresse=[PROXY-ADDRESS]", normal.referrer)

        val optedOut = service.filterEvent(event, listOf(OptOutFilter.NAME)).payload
        assertEquals("/sok?person=Ola Nordmann&email=[PROXY-EMAIL][PROXY-SEARCH]", optedOut.url)
        assertEquals("https://nav.no/tjeneste?person=Ola Nordmann&adresse=[PROXY-ADDRESS]", optedOut.referrer)
    }

    @Test
    fun `name opt out preserves names in URL paths and nested URL fields without disabling UUID redaction`() {
        val service = FilterService(SimpleMeterRegistry())
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(
            url = "/person/Ola.Nordmann/$uuid",
            referrer = "https://nav.no/person/Ola.Nordmann/$uuid",
            data = TestEventFactory.jsonNode(mapOf(
                "nested" to mapOf("href" to "/person/Ola.Nordmann/$uuid?person=Ola+Nordmann")
            ))
        ))

        val normal = service.filterEvent(event).payload
        assertEquals("/person/[PROXY-NAME]/[PROXY-UUID]", normal.url)
        assertEquals("https://nav.no/person/[PROXY-NAME]/[PROXY-UUID]", normal.referrer)

        val optedOut = service.filterEvent(event, listOf(OptOutFilter.NAME)).payload
        assertEquals("/person/Ola.Nordmann/[PROXY-UUID]", optedOut.url)
        assertEquals("https://nav.no/person/Ola.Nordmann/[PROXY-UUID]", optedOut.referrer)
        assertEquals("/person/Ola.Nordmann/[PROXY-UUID]?person=Ola Nordmann", optedOut.data!!["nested"]["href"].asString())

        val both = service.filterEvent(event, listOf(OptOutFilter.NAME, OptOutFilter.UUID)).payload
        assertEquals(event.payload.url, both.url)
        assertEquals(event.payload.referrer, both.referrer)
        assertEquals("/person/Ola.Nordmann/$uuid?person=Ola Nordmann", both.data!!["nested"]["href"].asString())
    }

    @Test
    fun `name opt out does not shield overlapping email address or search matches`() {
        val service = FilterService(SimpleMeterRegistry())
        val samples = listOf(
            "Ola.Nordmann@example.com" to "[PROXY-EMAIL]",
            "Ola Nordmanns gate 15" to "[PROXY-ADDRESS]",
            "/sok?q=Ola Nordmann" to "/sok[PROXY-SEARCH]"
        )
        for ((input, expected) in samples) {
            assertTrue(FilterPatterns.NAME_REGEX.containsMatchIn(input), input)
            for (filters in listOf(listOf(OptOutFilter.NAME), listOf(OptOutFilter.NAME, OptOutFilter.UUID))) {
                val out = service.filterEvent(TestEventFactory.eventWithData(input), filters)
                assertEquals(expected, out.payload.data!!["text"].asString(), "$input with $filters")
            }
        }
    }

    @Test
    fun `other rules and structural policies remain active when name is opted out`() {
        val service = FilterService(SimpleMeterRegistry())
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(data = TestEventFactory.jsonNode(mapOf(
            "person" to name, "fnr" to "12345678910", "phone" to "98765432",
            "ip_address" to "192.168.1.1", "ip" to "10.0.0.1", "idfa" to uuid
        ))))
        val data = service.filterEvent(event, listOf(OptOutFilter.NAME)).payload.data!!
        assertEquals(name, data["person"].asString())
        assertEquals("[PROXY-FNR]", data["fnr"].asString())
        assertEquals("[PROXY-PHONE]", data["phone"].asString())
        assertFalse(data.has("ip_address"))
        assertEquals("\$remote", data["ip"].asString())
        assertEquals("[PROXY-ADVERTISING-ID]", data["idfa"].asString())
    }

    @Test
    fun `opt out is per event and does not increment name counter`() {
        val registry = SimpleMeterRegistry()
        val service = FilterService(registry)
        val counter = registry.get("redactions_total").tag("rule", "name").counter()
        val event = TestEventFactory.eventWithData(name)
        assertEquals(name, service.filterEvent(event, listOf(OptOutFilter.NAME)).payload.data!!["text"].asString())
        assertEquals(0.0, counter.count())
        assertEquals("[PROXY-NAME]", service.filterEvent(event).payload.data!!["text"].asString())
        assertEquals(1.0, counter.count())
        assertEquals(name, service.filterEvent(event, listOf(OptOutFilter.NAME)).payload.data!!["text"].asString())
        assertEquals(1.0, counter.count())
    }
}
