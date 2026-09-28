package no.nav.reops.filter

internal object FilterPatterns {
    val KEEP_REGEX = Regex("((nav|test)[0-9]{6})")

    val FNR_REGEX = Regex("(?<!\\d)\\d{11}(?!\\d)")
    val NAVIDENT_REGEX = Regex("(?<![a-zA-Z0-9])[a-zA-Z]\\d{6}(?!\\d)")
    val EMAIL_REGEX = Regex("(?:mailto:)?[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    val IP_REGEX = Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)")
    val PHONE_REGEX = Regex("(?<!\\d)[2-9]\\d{7}(?!\\d)")

    // Words that should never be treated as (parts of) a person's name.
    private val NAME_EXCLUSIONS = setOf(
        "Norge",
        "Bosatt",
    )

    private val NOT_EXCLUDED_WORD: String = run {
        val alt = NAME_EXCLUSIONS.joinToString("|") { Regex.escape(it) }
        // Capitalized word that is NOT one of the excluded words
        "(?!(?:$alt)\\b)[A-ZÆØÅ][a-zæøå]{1,20}"
    }

    val NAME_REGEX = Regex(
        "\\b$NOT_EXCLUDED_WORD[\\s.]$NOT_EXCLUDED_WORD(?:[\\s.]$NOT_EXCLUDED_WORD)?\\b"
    )
    val ADDRESS_REGEX = Regex("\\b\\d{4}\\s[A-ZÆØÅ][A-ZÆØÅa-zæøå]+(?:\\s[A-ZÆØÅa-zæøå]+)*\\b")

    val SECRET_ADDRESS_REGEX = Regex("(?i)hemmelig(?:%20|\\s+)(?:20\\s*%(?:%20|\\s+))?adresse")
    val ACCOUNT_REGEX = Regex("(?<!\\d)\\d{4}\\.?\\d{2}\\.?\\d{5}(?!\\d)")
    val ORG_NUMBER_REGEX = Regex("(?<!\\d)\\d{9}(?!\\d)")
    val LICENSE_PLATE_REGEX = Regex("(?<![a-zA-Z])[A-Z]{2}\\s?\\d{5}(?!\\d)")
    val SEARCH_REGEX = Regex("[?&](?:q|query|search|k|ord)=[^&\\s]+")

    val UUID_REGEX = Regex("(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])")

    // Used only to preserve URL-like substrings in free-text (not for payload.url/referrer).
    // IMPORTANT: Preserve only path-part (up to ? or #). Query strings/fragments may contain PII.
    val URL_REGEX = Regex(
        pattern = """(?x)
            (?:
                https?://[A-Za-z0-9._\-]+(?:\.[A-Za-z0-9._\-]+)*(?::[0-9]+)?(?:/[A-Za-z0-9._\-/@%&=]*)?
                |
                (?<![A-Za-z0-9._%+\-@])[A-Za-z0-9._\-]+\.[A-Za-z]{2,}/[A-Za-z0-9._\-/@%&=]+
            )
        """.trimIndent(), options = setOf(RegexOption.COMMENTS)
    )

    // Characters allowed inside a single path segment / filename. Widened beyond plain
    // ASCII to also cover Norwegian letters and the punctuation that shows up in real
    // Windows/OneDrive filenames and document titles (commas, parens, apostrophes,
    // en/em dashes) — without this, a match stops at the first non-ASCII character and
    // everything after it (often the most sensitive part, e.g. a case document title)
    // is left unredacted next to the placeholder.
    private const val SEGMENT_CHARS = "A-Za-z0-9ÆØÅæøå._\\-\\s%,()'\u2013\u2014"

    // High-confidence: patterns that cannot occur in a legitimate web URL path or query
    // string, by construction of the URL/URI spec (no valid http(s) URL contains a raw
    // drive letter root, a raw UNC path, or the file: scheme). Safe to redact everywhere,
    // including inside fields otherwise treated as URLs (see UrlPolicy/RedactionRule.alwaysEnforced).
    val FILEPATH_HIGH_CONFIDENCE_REGEX = Regex(
        """(?x)
            (?:
                [A-Za-z]:[/\\]
                (?:[$SEGMENT_CHARS]+[/\\])*
                [$SEGMENT_CHARS]+
                (?:\.[A-Za-z0-9]{1,10})?
                |
                \\\\[$SEGMENT_CHARS]+\\[$SEGMENT_CHARS]+
                (?:\\[$SEGMENT_CHARS]+)*
                (?:\\[$SEGMENT_CHARS]+(?:\.[A-Za-z0-9]{1,10})?)?
                |
                file:///
                [$SEGMENT_CHARS/%:]+
                (?:\.[A-Za-z0-9]{1,10})?
            )
        """.trimIndent(), options = setOf(RegexOption.COMMENTS)
    )

    // Low-confidence: generic absolute/relative multi-segment paths. Structurally
    // indistinguishable from a real URL route (e.g. "/soknad/behandling/123.html"), so
    // this is excluded from the path-part of fields classified as URLs (UrlPolicy) to
    // avoid false-positiving legitimate, publicly-routable page paths.
    val FILEPATH_LOW_CONFIDENCE_REGEX = Regex(
        """(?x)
            (?:
                (?:(?:/[A-Za-z0-9._\-]+(?:/[A-Za-z0-9._\-]+)+(?:\.[A-Za-z0-9]{1,10})?)
                |
                (?:/(?=.*[A-Za-z])[A-Za-z0-9._\-]+\.[A-Za-z0-9]{1,10}))
                |
                (?:\./|\.\./|~/)
                (?:[A-Za-z0-9._\-]+/)*
                [A-Za-z0-9._\-]+
                (?:\.[A-Za-z0-9]{1,10})?
            )
        """.trimIndent(), options = setOf(RegexOption.COMMENTS)
    )

    val ADVERTISING_ID_KEYS: Set<String> = setOf(
        "idfa", "idfv", "adid", "gaid", "android_id", "aaid", "msai", "advertising_id"
    )
}