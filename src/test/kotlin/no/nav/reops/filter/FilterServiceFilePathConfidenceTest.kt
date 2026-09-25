package no.nav.reops.filter

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Covers the high-confidence / low-confidence filepath rule split.
 *
 * Background: FilterPatterns.FILEPATH_REGEX used to be one rule, entirely excluded from
 * the path-part of any field classified as a URL (UrlPolicy.pathExcludedLabels). That
 * blanket exclusion was needed because *some* of its branches (generic absolute/relative
 * multi-segment paths) are structurally indistinguishable from a real URL route
 * (e.g. "/soknad/behandling/123.html"). But three of its branches — Windows drive-letter
 * roots, UNC paths, and the file: scheme — can never occur in a legitimate http(s) URL
 * path or query string, by construction of the URL spec. Excluding those too was an
 * over-broad side effect, and real production data (BigQuery `umami_views.event`,
 * Sept 2026 blast-radius investigation) confirmed it: ~107k events with real Windows/
 * OneDrive filepaths — including case-document titles and employee names — sitting in
 * `payload.url`/`referrer` unredacted, because they were misclassified as "ambiguous
 * with a URL path" when they never were.
 *
 * The fix: split the rule into `filepath_high_confidence` (drive-letter, UNC, file://;
 * alwaysEnforced = true, bypasses UrlPolicy's path exclusion) and
 * `filepath_low_confidence` (generic paths; still excluded from URL path-parts, as
 * before). Both emit the same `[PROXY-FILEPATH]` placeholder.
 */
class FilterServiceFilePathConfidenceTest {

    private fun filterService(): FilterService = FilterService(SimpleMeterRegistry())

    private fun redactText(input: String): String {
        val service = filterService()
        return service.filterEvent(TestEventFactory.eventWithData(input)).payload.data!!.get("text").asString()
    }

    private fun redactUrl(url: String): String {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(type = "event", payload = base.payload.copy(url = url))
        return service.filterEvent(event).payload.url!!
    }

    private fun redactUrlLikeKey(key: String, value: String): String {
        val service = filterService()
        val base = TestEventFactory.minimalEvent()
        val event = base.copy(
            type = "event",
            payload = base.payload.copy(data = TestEventFactory.jsonNode(mapOf(key to value)))
        )
        return service.filterEvent(event).payload.data!!.get(key).asString()
    }

    // --- High-confidence: redacted everywhere, including inside URL path-parts ---

    @Test
    fun `drive-letter path is redacted inside payload url, not just free text`() {
        assertEquals("/[PROXY-FILEPATH]", redactUrl("/C:/Users/Admin/Desktop/file.txt"))
    }

    @Test
    fun `file scheme is redacted inside payload url`() {
        assertEquals(
            "[PROXY-FILEPATH]",
            redactUrl("file:///C:/Users/Admin/Desktop/file.txt")
        )
    }

    @Test
    fun `unc path is redacted inside payload url`() {
        assertEquals("[PROXY-FILEPATH]", redactUrl("\\\\Server\\Share\\folder\\file.docx"))
    }

    @Test
    fun `drive-letter path is redacted inside a url-like custom field`() {
        assertEquals("[PROXY-FILEPATH]", redactUrlLikeKey("path", "C:\\Users\\Admin\\file.exe"))
    }

    @Test
    fun `file scheme is redacted inside a url-like custom field`() {
        assertEquals(
            "[PROXY-FILEPATH]",
            redactUrlLikeKey("href", "file:///Users/Admin/Desktop/file.html")
        )
    }

    // --- Low-confidence: still excluded inside URL path-parts (unchanged behavior) ---

    @Test
    fun `generic absolute path in payload url is preserved as a real route`() {
        assertEquals("/soknad/behandling/123.html", redactUrl("/soknad/behandling/123.html"))
    }

    @Test
    fun `generic absolute path in a url-like custom field is preserved`() {
        assertEquals("/var/www/html/page", redactUrlLikeKey("href", "/var/www/html/page"))
    }

    @Test
    fun `dot-relative path in a url-like custom field is preserved`() {
        assertEquals("./dokumenter/viktige", redactUrlLikeKey("url_path", "./dokumenter/viktige"))
    }

    // --- Low-confidence still redacts normally in non-URL free text (unchanged) ---

    @Test
    fun `generic absolute path in free text is still redacted`() {
        assertEquals("[PROXY-FILEPATH]", redactText("/home/user/documents/secret.txt"))
    }

    // --- Real leaked shapes from the BigQuery blast-radius investigation ---
    // (structure/character-mix reproduced from real samples; all names/usernames
    // below are made-up placeholders — "Kul Elg" / "kul.elg" etc — not real employees,
    // so they don't show up if someone searches their own name later.)

    @Test
    fun `real-world OneDrive path with norwegian letters is fully redacted, no residual text`() {
        val out = redactUrl(
            "/C:/Users/kul.elg/OneDrive - Rar Bever AS/Kul Elg/Mine arbeidsforhold siste 5 år.html"
        )
        assertEquals("/[PROXY-FILEPATH]", out)
    }

    @Test
    fun `real-world file scheme path with comma and parens is fully redacted, no residual text`() {
        val out = redactUrl(
            "file:///C:/Users/RarBever/Byggekomite/Fjellrev vei 4 Snodig Elg (Sørlandet-1) - Kultur, inventar og interiør.html"
        )
        assertEquals("[PROXY-FILEPATH]", out)
    }

    @Test
    fun `real-world case document title in path is fully redacted`() {
        val out = redactUrl("/C:/Users/blaraev/OneDrive/Desktop/Dokumentasjon - Søknad om økonomisk sosialhjelp.html")
        assertEquals("/[PROXY-FILEPATH]", out)
    }

    @Test
    fun `real-world url-encoded file scheme path with percent-encoding is fully redacted`() {
        val out = redactUrl(
            "file:///C:/Users/RarBever/OneDrive%20-%20NAV/Skrivebord/Sommerjobb%20-%20Medarbeider.html"
        )
        assertEquals("[PROXY-FILEPATH]", out)
    }

    // --- Same output label regardless of confidence tier ---

    @Test
    fun `high and low confidence rules emit the same placeholder label`() {
        assertEquals("[PROXY-FILEPATH]", redactText("C:\\Users\\Kul.Elg\\file.exe"))
        assertEquals("[PROXY-FILEPATH]", redactText("/home/user/documents/secret.txt"))
    }
}
