package no.nav.reops.filter

import io.micrometer.core.instrument.MeterRegistry
import no.nav.reops.event.Event
import no.nav.reops.event.OptOutFilter
import org.springframework.stereotype.Service
import io.micrometer.core.instrument.Counter

@Service
class FilterService(meterRegistry: MeterRegistry) {
    private val rules: List<RedactionRule> = RuleSetFactory.create(meterRegistry)
    private val keyPolicy: KeyPolicy = DefaultPolicies.keyPolicy()
    private val urlPolicy: UrlPolicy = DefaultPolicies.urlPolicy()

    fun filterEvent(event: Event, optOutFilters: List<OptOutFilter>? = null): Event {
        val excludedLabels = optOutFilters.toExcludedLabels()

        val redactor = Redactor(rules, excludedLabels)
        val p = event.payload
        val traverser = Traverser(
            keyPolicy = keyPolicy, urlPolicy = urlPolicy, redactor = redactor
        )

        val sanitized = p.copy(
            website = p.website,
            id = p.id?.let { FilterPatterns.FNR_REGEX.replace(it) { "[PROXY-FNR]" } },
            hostname = p.hostname?.let { urlPolicy.redactUrl(it, redactor) },
            screen = p.screen?.let { urlPolicy.redactUrl(it, redactor) },
            language = p.language?.let { urlPolicy.redactUrl(it, redactor) },
            title = p.title?.let { urlPolicy.redactUrl(it, redactor) },
            url = p.url?.let { urlPolicy.redactUrl(it, redactor) },
            referrer = p.referrer?.let { urlPolicy.redactUrl(it, redactor) },
            name = p.name?.let { urlPolicy.redactUrl(it, redactor) },
            data = p.data?.let { traverser.transform(it) })

        return event.copy(payload = sanitized)
    }

    private companion object {
        private val OPT_OUT_LABEL_MAP: Map<OptOutFilter, String> = mapOf(
            OptOutFilter.UUID to "PROXY-UUID",
            OptOutFilter.NAME to "PROXY-NAME"
        )

        private fun List<OptOutFilter>?.toExcludedLabels(): Set<String> =
            this?.mapNotNull { OPT_OUT_LABEL_MAP[it] }?.toSet() ?: emptySet()
    }
}

internal object DefaultPolicies {
    fun keyPolicy(): KeyPolicy = KeyPolicy(
        preservedKeys = setOf("api_key", "device_id", "website"),
        droppedKeys = setOf("ip_address"),
        forcedValues = mapOf("ip" to "\$remote"),
        advertisingIdKeys = FilterPatterns.ADVERTISING_ID_KEYS
    )

    fun urlPolicy(): UrlPolicy = UrlPolicy(
        isNestedUrlField = { ctx ->
            ctx.depth == 2 && ctx.containerKey == "payload" && (ctx.key == "url" || ctx.key == "referrer")
        }, urlLikeKeys = setOf(
            "path",
            "href",
            "destinasjon",
            "url",
            "link",
            "pathname",
            "linkText",
            "destination",
            "url_path",
            "fra",
            "lenketekst",
            "lenkesti",
            "newLocation",
            "prevLocation"
        ), pathExcludedLabels = setOf("PROXY-FILEPATH")
    )

}

internal data class RedactionRule(
    val name: String,
    val label: String,
    val regex: Regex,
    val counter: Counter,
    val preserve: Boolean = false,
    val sanctioned: Boolean = false,
    // When true, this rule always runs, even if its label is in the caller's
    // excludedLabels set (e.g. UrlPolicy's pathExcludedLabels). Reserved for rules
    // whose pattern cannot occur in a legitimate URL path/query by construction —
    // see FilterPatterns.FILEPATH_HIGH_CONFIDENCE_REGEX.
    val alwaysEnforced: Boolean = false
)

internal object RuleSetFactory {
    fun create(meterRegistry: MeterRegistry): List<RedactionRule> = listOf(
        keepRule(meterRegistry),
        rule("uuid", "PROXY-UUID", FilterPatterns.UUID_REGEX, meterRegistry, sanctioned = true),
        // Same output label ("PROXY-FILEPATH") for both — downstream consumers see one
        // placeholder value regardless of which branch matched. Only "low_confidence"
        // is ever excluded (see UrlPolicy.pathExcludedLabels); "high_confidence" is
        // alwaysEnforced so it still fires inside URL path-parts.
        rule(
            "filepath_high_confidence", "PROXY-FILEPATH", FilterPatterns.FILEPATH_HIGH_CONFIDENCE_REGEX,
            meterRegistry, alwaysEnforced = true
        ),
        rule("filepath_low_confidence", "PROXY-FILEPATH", FilterPatterns.FILEPATH_LOW_CONFIDENCE_REGEX, meterRegistry),
        rule("fnr", "PROXY-FNR", FilterPatterns.FNR_REGEX, meterRegistry),
        rule("navident", "PROXY-NAVIDENT", FilterPatterns.NAVIDENT_REGEX, meterRegistry),
        rule("email", "PROXY-EMAIL", FilterPatterns.EMAIL_REGEX, meterRegistry),
        rule("ip", "PROXY-IP", FilterPatterns.IP_REGEX, meterRegistry),
        rule("phone", "PROXY-PHONE", FilterPatterns.PHONE_REGEX, meterRegistry),
        rule("address", "PROXY-ADDRESS", FilterPatterns.ADDRESS_REGEX, meterRegistry),
        rule("name", "PROXY-NAME", FilterPatterns.NAME_REGEX, meterRegistry),
        rule("secret_address", "PROXY-SECRET-ADDRESS", FilterPatterns.SECRET_ADDRESS_REGEX, meterRegistry),
        rule("account", "PROXY-ACCOUNT", FilterPatterns.ACCOUNT_REGEX, meterRegistry),
        rule("org_number", "PROXY-ORG-NUMBER", FilterPatterns.ORG_NUMBER_REGEX, meterRegistry),
        rule("license_plate", "PROXY-LICENSE-PLATE", FilterPatterns.LICENSE_PLATE_REGEX, meterRegistry),
        rule("search", "PROXY-SEARCH", FilterPatterns.SEARCH_REGEX, meterRegistry)
    )

    private fun keepRule(meterRegistry: MeterRegistry): RedactionRule = RedactionRule(
        name = "keep",
        label = "PROXY-KEEP",
        regex = FilterPatterns.KEEP_REGEX,
        counter = counter(meterRegistry, "keep"),
        preserve = true
    )

    private fun rule(
        name: String,
        label: String,
        regex: Regex,
        meterRegistry: MeterRegistry,
        sanctioned: Boolean = false,
        alwaysEnforced: Boolean = false
    ): RedactionRule = RedactionRule(
        name = name,
        label = label,
        regex = regex,
        counter = counter(meterRegistry, name),
        preserve = false,
        sanctioned = sanctioned,
        alwaysEnforced = alwaysEnforced
    )

    private fun counter(meterRegistry: MeterRegistry, ruleName: String): Counter =
        Counter.builder("redactions_total").tag("rule", ruleName).register(meterRegistry)
}
