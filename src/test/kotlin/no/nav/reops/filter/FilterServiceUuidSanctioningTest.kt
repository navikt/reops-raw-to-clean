package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.reops.event.OptOutFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * UUID sanctioning: when the UUID rule is opted out, UUID-shaped spans must still be
 * protected from all other redaction rules. No partial match of any rule should fire
 * inside a UUID span.
 *
 * Each test uses `optOutFilters = listOf(OptOutFilter.UUID)` to disable the UUID rule,
 * then asserts the full UUID string passes through unchanged.
 *
 * The UUIDs below are constructed so that a specific other rule WOULD fire inside the
 * UUID span if sanctioning is not implemented (verified against current regex patterns).
 */
class FilterServiceUuidSanctioningTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redactWithUuidOptOut(input: String): String {
        val service = filterService()
        val event = TestEventFactory.eventWithData(input)
        return service.filterEvent(event, optOutFilters = listOf(OptOutFilter.UUID))
            .payload.data!!.get("text").asString()
    }

    private fun redact(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input))
            .payload.data!!.get("text").asString()
    }

    // -------------------------------------------------------------------------
    // NAVIDENT: `(?<![a-zA-Z0-9])[a-zA-Z]\d{6}(?!\d)`
    // "d156936" matches at position 0 (start = no alphanum before, 'd' + 156936, lookahead 'a')
    // Without sanctioning: "[PROXY-NAVIDENT]a-10c2-4597-9c10-55c124f42516"
    // -------------------------------------------------------------------------

    @Test
    fun `navident does not match inside uuid when uuid is opted out`() {
        val uuid = "d156936a-10c2-4597-9c10-55c124f42516"
        assertEquals(uuid, redactWithUuidOptOut(uuid))
    }

    @Test
    fun `navident in first segment does not match inside uuid when uuid is opted out`() {
        val uuid = "a123456b-0000-0000-0000-000000000000"
        assertEquals(uuid, redactWithUuidOptOut(uuid))
    }

    // -------------------------------------------------------------------------
    // PHONE: `(?<!\d)[2-9]\d{7}(?!\d)`
    // "23456789" matches: starts at position 0 (no preceding digit), '2' + 3456789, lookahead '-'
    // Without sanctioning: "[PROXY-PHONE]-0000-0000-0000-000000000000"
    // -------------------------------------------------------------------------

    @Test
    fun `phone number does not match inside uuid when uuid is opted out`() {
        val uuid = "23456789-0000-0000-0000-000000000000"
        assertEquals(uuid, redactWithUuidOptOut(uuid))
    }

    // -------------------------------------------------------------------------
    // FNR: `(?<!\d)\d{11}(?!\d)`
    // Last segment "a12345678901": '12345678901' (11 digits), preceded by 'a' (not digit),
    // followed by end-of-string (not digit)
    // Without sanctioning: "00000000-0000-0000-0000-a[PROXY-FNR]"
    // -------------------------------------------------------------------------

    @Test
    fun `fnr does not match inside uuid when uuid is opted out`() {
        val uuid = "00000000-0000-0000-0000-a12345678901"
        assertEquals(uuid, redactWithUuidOptOut(uuid))
    }

    // -------------------------------------------------------------------------
    // ORG_NUMBER: `(?<!\d)\d{9}(?!\d)`
    // Last segment "a123456789b0": '123456789' (9 digits), preceded by 'a', followed by 'b'
    // Without sanctioning: "00000000-0000-0000-0000-a[PROXY-ORG-NUMBER]b0"
    // -------------------------------------------------------------------------

    @Test
    fun `org number does not match inside uuid when uuid is opted out`() {
        val uuid = "00000000-0000-0000-0000-a123456789b0"
        assertEquals(uuid, redactWithUuidOptOut(uuid))
    }

    // -------------------------------------------------------------------------
    // ACCOUNT: `(?<!\d)\d{4}\.?\d{2}\.?\d{5}(?!\d)` (no dots → 11 consecutive digits)
    // Same UUID as FNR: "a12345678901" — 11 digits, non-digit on both sides
    // Without sanctioning: "00000000-0000-0000-0000-a[PROXY-ACCOUNT]"
    // -------------------------------------------------------------------------

    @Test
    fun `account number does not match inside uuid when uuid is opted out`() {
        val uuid = "00000000-0000-0000-0000-a12345678901"
        assertEquals(uuid, redactWithUuidOptOut(uuid))
    }

    // -------------------------------------------------------------------------
    // Multiple UUIDs in same string: all spans must be sanctioned
    // -------------------------------------------------------------------------

    @Test
    fun `multiple uuids are all sanctioned when uuid is opted out`() {
        val uuid1 = "d156936a-10c2-4597-9c10-55c124f42516"
        val uuid2 = "23456789-0000-0000-0000-000000000000"
        val input = "first=$uuid1 second=$uuid2"
        assertEquals(input, redactWithUuidOptOut(input))
    }

    // -------------------------------------------------------------------------
    // Control: UUID is still redacted normally when NOT opted out
    // -------------------------------------------------------------------------

    @Test
    fun `uuid is redacted normally when not opted out`() {
        val uuid = "d156936a-10c2-4597-9c10-55c124f42516"
        assertEquals("[PROXY-UUID]", redact(uuid))
    }

    @Test
    fun `uuid with potential navident is fully redacted when not opted out`() {
        val uuid = "a123456b-0000-0000-0000-000000000000"
        assertEquals("[PROXY-UUID]", redact(uuid))
    }
}
