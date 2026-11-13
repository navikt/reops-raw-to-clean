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
            if (!rule.regex.containsMatchIn(result)) continue

            val validator = rule.validator
            if (validator == null) {
                rule.counter.increment()
                if (!rule.preserve) {
                    result = rule.regex.replace(result) { "[${rule.label}]" }
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
        }

        // 3) Restore URLs
        result = preservedUrls.restoreAll(result)

        // 4) Restore sanctioned spans (reverse order of preservation)
        for (store in sanctionedStores.asReversed()) {
            result = store.restoreAll(result)
        }

        return result
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
