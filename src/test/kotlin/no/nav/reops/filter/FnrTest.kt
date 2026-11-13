package no.nav.reops.filter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

// All valid numbers are synthetic (Tenor: month + 80, Dolly: month + 40) with computed check digits.
class FnrTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "15819010029", // Tenor, 15.01.90
            "03859510034", // Tenor, 03.05.95
            "23489010089", // Dolly, 23.08.90
            "41819010186", // d-nummer (day + 40) on a Tenor date
            "29829210078", // Tenor, 29.02.92 (leap year)
            "158190 10029",
            "158190-10029",
            "158190.10029",
            "15.81.90 10029",
            "15.81.90.10029"
        ]
    )
    fun `accepts valid identity numbers`(candidate: String) {
        assertTrue(Fnr.isValid(candidate), candidate)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "15819010028", // wrong K2
            "15819010019", // wrong K1
            "12345678901",
            "00000000000", // day 0
            "32819010029", // day 32
            "15139010029", // month 13
            "15619010029", // month 61 (neither +40 nor +80)
            "29829110057", // 29.02.91, not a leap year (check digits are valid)
            "1581901002", // 10 digits
            "158190100290" // 12 digits
        ]
    )
    fun `rejects invalid identity numbers`(candidate: String) {
        assertFalse(Fnr.isValid(candidate), candidate)
    }
}
