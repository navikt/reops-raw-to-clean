package no.nav.reops.filter

internal object FilterPatterns {
    val KEEP_REGEX = Regex("((nav|test)[0-9]{6})")

    val FNR_REGEX = Regex("(?<!\\d)\\d{6}[ -]?\\d{5}(?!\\d)")
    val NAVIDENT_REGEX = Regex("(?<![a-zA-Z0-9])[a-zA-Z]\\d{6}(?!\\d)")
    val EMAIL_REGEX = Regex("(?:mailto:)?[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    val IP_REGEX = Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)")
    // Restrict separators to common Norwegian groupings rather than accepting
    // arbitrary runs of digits and whitespace. Never span lines.
    val PHONE_REGEX = Regex(
        "(?<!\\d)(?:(?:\\+47|0047)[ ]?)?(?:[2-9]\\d{7}|[2-9]\\d{2} \\d{2} \\d{3}|[2-9]\\d(?: \\d{2}){3})(?!\\d)"
    )

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
        "\\b$NOT_EXCLUDED_WORD[\\s.]$NOT_EXCLUDED_WORD(?:[\\s.]$NOT_EXCLUDED_WORD)*\\b"
    )

    private const val ADDRESS_WORD = "[\\p{Lu}][\\p{L}\\p{M}'’.-]*"
    private const val STREET_TYPE = "(?:gate|gata|gaten|vei|veien|veg|vegen|sti|stien|allé|alle|alléen|alleen|plass|plassen|torg|torget|bakken|svingen|lia|tunet)"
    private const val HOUSE_NUMBER = "\\d{1,4}(?:[ ]?[A-Za-z])?"
    private const val POSTAL_PLACE = "\\d{4}[ \\t]+$ADDRESS_WORD(?:[ \\t]+$ADDRESS_WORD)*"
    private const val STREET_NAME = "$ADDRESS_WORD(?:[ \\t]+(?:$ADDRESS_WORD|(?i:$STREET_TYPE)))*"
    private const val ADDRESS_LINE_BREAK = "(?:[ \\t]*,[ \\t]*|[ \\t]*\\r?\\n[ \\t]*|[ \\t]+)"

    // Full addresses need a postcode/place to distinguish them from product
    // names and numbers. Partial streets need a recognizable street suffix.
    // Run before NAME_REGEX: otherwise multiword streets/places are destroyed
    // before their address context can be recognized.
    val ADDRESS_REGEX = Regex(
        "\\b(?:" +
            "(?i:postboks|p\\.?o\\.?[ ]?box)[ \\t]+\\d+(?:[ \\t]+$ADDRESS_WORD)*" +
            "(?:$ADDRESS_LINE_BREAK$POSTAL_PLACE)?" +
            "|$STREET_NAME[ \\t]+$HOUSE_NUMBER$ADDRESS_LINE_BREAK$POSTAL_PLACE" +
            // Compound streets are case-insensitive, but stay within one word:
            // matching arbitrary lowercase words before a suffix swallows prose.
            "|(?:(?iu:[\\p{L}][\\p{L}\\p{M}'’.-]*$STREET_TYPE)|$STREET_NAME[ \\t]+(?i:$STREET_TYPE))" +
            "[ \\t]+$HOUSE_NUMBER" +
            "|$POSTAL_PLACE)\\b"
    )

    val SECRET_ADDRESS_REGEX = Regex("(?i)hemmelig(?:%20|\\s+)(?:20\\s*%(?:%20|\\s+))?adresse")
    val ACCOUNT_REGEX = Regex("(?<!\\d)\\d{4}\\.?\\d{2}\\.?\\d{5}(?!\\d)")
    val ORG_NUMBER_REGEX = Regex("(?<!\\d)\\d{9}(?!\\d)")
    val LICENSE_PLATE_REGEX = Regex("(?<![a-zA-Z])[A-Z]{2}\\s?\\d{5}(?!\\d)")
    // The value runs until the next '&' (next param), a '#' fragment marker, or
    // end of string. Spaces are part of the value: raw-to-clean URL-decodes the
    // query before matching (UrlPolicy), and raw spaces appear in tracked urls
    // when a SPA pushes unencoded prose into location.href. Stopping at the
    // first space leaked every word after the first downstream.
    val SEARCH_REGEX = Regex("[?&](?:q|query|search|k|ord)=[^&#]+")

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
