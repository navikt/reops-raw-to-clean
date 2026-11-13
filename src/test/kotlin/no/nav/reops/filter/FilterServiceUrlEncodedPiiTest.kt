package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that URL-encoded PII in query strings is redacted.
 *
 * Affected rules (characters that get percent-encoded in URLs):
 *  - EMAIL       — '@' → '%40'
 *  - NAME        — space → '%20' or '+'
 *  - ADDRESS     — space → '%20' or '+'
 *  - LICENSE_PLATE — space → '%20' (spaced form; no-space form already works)
 *
 * Unaffected rules (all-alphanumeric / use unreserved chars):
 *  FNR, PHONE, UUID, NAVIDENT, ACCOUNT, ORG_NUMBER, IP, FILEPATH, SECRET_ADDRESS
 */
class FilterServiceUrlEncodedPiiTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    // ─── EMAIL: '%40' for '@' ─────────────────────────────────────────────────

    @Test
    fun `redacts percent-encoded email in url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/kontakt?email=ola.nordmann%40example.com"))

        val out = service.filterEvent(event)

        assertEquals("/kontakt?email=[PROXY-EMAIL]", out.payload.url)
    }

    @Test
    fun `redacts percent-encoded email in referrer query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(referrer = "https://id.nav.no/login?email=ola.nordmann%40example.com"))

        val out = service.filterEvent(event)

        assertEquals("https://id.nav.no/login?email=[PROXY-EMAIL]", out.payload.referrer)
    }

    @Test
    fun `redacts percent-encoded email mid-query, preserves other params`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/melding?to=ola%40nav.no&lang=nb"))

        val out = service.filterEvent(event)

        assertEquals("/melding?to=[PROXY-EMAIL]&lang=nb", out.payload.url)
    }

    // ─── NAME: space → '%20' or '+' ──────────────────────────────────────────

    @Test
    fun `redacts percent-20 encoded name in url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/sok?name=Ola%20Nordmann"))

        val out = service.filterEvent(event)

        assertEquals("/sok?name=[PROXY-NAME]", out.payload.url)
    }

    @Test
    fun `redacts plus-encoded name in url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/sok?name=Ola+Nordmann"))

        val out = service.filterEvent(event)

        assertEquals("/sok?name=[PROXY-NAME]", out.payload.url)
    }

    @Test
    fun `redacts percent-20 encoded name in referrer query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(referrer = "https://nav.no/tjeneste?bruker=Kari%20Normann"))

        val out = service.filterEvent(event)

        assertEquals("https://nav.no/tjeneste?bruker=[PROXY-NAME]", out.payload.referrer)
    }

    @Test
    fun `redacts separated identifiers and complete fictional address after query decoding`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val cases = listOf(
            "/finn?person=158190%2010029" to "/finn?person=[PROXY-FNR]",
            "/finn?telefon=987%2065%20432" to "/finn?telefon=[PROXY-PHONE]",
            "/finn?telefon=%2B47+987+65+432" to "/finn?telefon=[PROXY-PHONE]",
            "/finn?adresse=Fantasigata+15+A%2C+1234+Ingenstedsby" to "/finn?adresse=[PROXY-ADDRESS]"
        )

        for ((url, expected) in cases) {
            val event = base.copy(payload = base.payload.copy(url = url))
            assertEquals(expected, service.filterEvent(event).payload.url, "Input: $url")
        }
    }

    // ─── ADDRESS: space → '%20' or '+' ───────────────────────────────────────

    @Test
    fun `redacts percent-20 encoded address in url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/finn?adresse=1234%20Oslo"))

        val out = service.filterEvent(event)

        assertEquals("/finn?adresse=[PROXY-ADDRESS]", out.payload.url)
    }

    @Test
    fun `redacts plus-encoded address in url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/finn?adresse=1234+Oslo"))

        val out = service.filterEvent(event)

        assertEquals("/finn?adresse=[PROXY-ADDRESS]", out.payload.url)
    }

    // ─── LICENSE PLATE: space → '%20' (spaced form) ──────────────────────────

    @Test
    fun `redacts percent-20 encoded license plate in url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(payload = base.payload.copy(url = "/sok?plate=AB%2012345"))

        val out = service.filterEvent(event)

        assertEquals("/sok?plate=[PROXY-LICENSE-PLATE]", out.payload.url)
    }

    // ─── Combined: multiple encoded PII in one query ──────────────────────────

    @Test
    fun `redacts multiple encoded PII types in same url query`() {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            payload = base.payload.copy(
                url = "/skjema?email=ola%40nav.no&navn=Ola%20Nordmann&lang=nb"
            )
        )

        val out = service.filterEvent(event)

        assertEquals("/skjema?email=[PROXY-EMAIL]&navn=[PROXY-NAME]&lang=nb", out.payload.url)
    }
}
