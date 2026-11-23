package no.nav.reops.filter

internal class Redactor(
    private val rules: List<RedactionRule>,
    private val globalExcludedLabels: Set<String> = emptySet()
) {
    fun redact(
        input: String, excludedLabels: Set<String> = emptySet(), preserveUrls: Boolean = true
    ): String {
        val allExcluded = if (globalExcludedLabels.isEmpty()) excludedLabels else globalExcludedLabels + excludedLabels
        var result = input

        // 0) Sanctioning: for each rule that is sanctioned AND excluded, preserve its matched spans
        //    as placeholders so no other rule can fire inside them. Restored last.
        val sanctionedStores = mutableListOf<PlaceholderStore>()
        for (rule in rules) {
            if (!rule.sanctioned || rule.label !in allExcluded) continue
            val store = PlaceholderStore(prefix = "__SANCTIONED_${rule.name.uppercase()}_", suffix = "__")
            result = store.preserveAll(result, rule.regex)
            sanctionedStores.add(store)
        }

        // 1) Optionally preserve URL-like substrings in free-text
        val preservedUrls = PlaceholderStore(prefix = "__PRESERVED_URL_", suffix = "__")
        if (preserveUrls) {
            result = preservedUrls.preserveAll(result, FilterPatterns.URL_REGEX) { url -> Fnr.redact(url) }
        }

        // 2) Apply rules (same order, same checks)
        for (rule in rules) {
            if (rule.label in allExcluded && !rule.alwaysEnforced) continue
            result = applyRule(rule, result)
        }

        // 3) Restore URLs
        result = preservedUrls.restoreAll(result)

        // 4) Restore sanctioned spans (reverse order of preservation)
        for (store in sanctionedStores.asReversed()) {
            result = store.restoreAll(result)
        }

        return result
    }

    /**
     * A JSON key that names an fnr (fnr, userFnr, fødselsnummer) is context for its value, just like
     * a context word in text: the value is masked even if it fails validation.
     */
    fun redactFnrKeyedValue(key: String?, value: String): String {
        if (!isFnrKey(key)) return value
        val rule = rules.firstOrNull { it.name == "fnr_context" } ?: return value
        if (rule.label in globalExcludedLabels) return value
        return applyRule(rule, "$KEY_PREFIX$value").removePrefix(KEY_PREFIX)
    }

    fun isFnrKey(key: String?): Boolean =
        key != null && FilterPatterns.FNR_KEY_REGEX.containsMatchIn(CAMEL_BOUNDARY.replace(key, " "))

    private fun applyRule(rule: RedactionRule, input: String): String {
        if (rule.encodingAware) return applyOnDecodedView(rule, input)
        if (!rule.regex.containsMatchIn(input)) return input

        var result = input
        val validator = rule.validator
        if (validator == null) {
            rule.counter.increment()
            if (!rule.preserve) {
                val group = rule.maskGroup
                result = if (group == null) {
                    rule.regex.replace(result) { "[${rule.label}]" }
                } else {
                    rule.regex.replace(result) { m -> m.replaceGroup(group, "[${rule.label}]") }
                }
            }
        } else if (!rule.preserve) {
            var matched = false
            val replaced = rule.regex.replace(result) { m ->
                if (validator(m.value)) {
                    matched = true
                    "[${rule.label}]"
                } else {
                    m.value
                }
            }
            if (matched) {
                rule.counter.increment()
                result = replaced
            }
        }
        return result
    }

    // Matches on the percent-decoded text and replaces the matching span of the original, so the
    // encoding around the number is kept.
    private fun applyOnDecodedView(rule: RedactionRule, input: String): String {
        val view = DecodedView.of(input)
        val validator = rule.validator
        val out = StringBuilder()
        var last = 0
        var count = 0
        for (m in rule.regex.findAll(view.text)) {
            if (validator != null && !validator(m.value)) continue
            val range = if (rule.maskGroup == null) m.range else m.groups[rule.maskGroup]?.range ?: continue
            val original = view.original(range)
            out.append(input, last, original.first).append("[${rule.label}]")
            last = original.last + 1
            count++
        }
        if (count == 0) return input
        rule.counter.increment()
        return if (rule.preserve) input else out.append(input, last, input.length).toString()
    }

    private companion object {
        const val KEY_PREFIX = "fnr: "
        val CAMEL_BOUNDARY = Regex("(?<=\\p{Ll})(?=\\p{Lu})")
    }

    private class PlaceholderStore(
        private val prefix: String, private val suffix: String
    ) {
        private val values = mutableListOf<String>()

        fun preserveAll(input: String, regex: Regex, preprocess: ((String) -> String)? = null): String {
            val matches = regex.findAll(input).toList()
            if (matches.isEmpty()) return input

            // Collect values in forward order (placeholder(0) = first match, etc.)
            matches.forEach { match ->
                values.add(preprocess?.invoke(match.value) ?: match.value)
            }

            // Replace spans right-to-left so earlier offsets stay valid
            val sb = StringBuilder(input)
            matches.asReversed().forEachIndexed { reversedI, match ->
                val i = matches.size - 1 - reversedI
                sb.replace(match.range.first, match.range.last + 1, placeholder(i))
            }
            return sb.toString()
        }

        fun restoreAll(input: String): String {
            var out = input
            values.forEachIndexed { i, original ->
                out = out.replace(placeholder(i), original)
            }
            return out
        }

        private fun placeholder(i: Int): String = "$prefix${i}$suffix"
    }
}

internal fun MatchResult.replaceGroup(name: String, replacement: String): String {
    val g = groups[name] ?: return replacement
    val from = g.range.first - range.first
    val to = g.range.last + 1 - range.first
    return value.substring(0, from) + replacement + value.substring(to)
}
