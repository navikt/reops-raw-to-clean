package no.nav.reops.filter

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class NodeContext(
    val depth: Int, val containerKey: String?, val key: String?
)

internal class UrlPolicy(
    private val isNestedUrlField: (NodeContext) -> Boolean,
    private val urlLikeKeys: Set<String>,
    private val pathExcludedLabels: Set<String>
) {
    fun isUrlField(ctx: NodeContext): Boolean = isNestedUrlField(ctx)

    fun isUrlLikeField(ctx: NodeContext): Boolean = ctx.key != null && ctx.key in urlLikeKeys

    fun redactUrl(url: String, redactor: Redactor, excludePathLabels: Boolean = true): String {
        val pathLabels = if (excludePathLabels) pathExcludedLabels else emptySet()
        val (path, queryOrEmpty) = splitPathAndQuery(url)
        val redactedPath = redactor.redact(input = path, excludedLabels = pathLabels, preserveUrls = false)

        if (queryOrEmpty.isEmpty()) return redactedPath

        // URL-decode the query string before redacting so that percent-encoded PII
        // (e.g. user%40example.com, Ola%20Nordmann, 1234+Oslo) is matched by the
        // regex rules. Without decoding, '%40' is never seen as '@' by EMAIL_REGEX,
        // and '%20'/'+' are never seen as spaces by NAME_REGEX / ADDRESS_REGEX.
        val decodedQuery = try {
            URLDecoder.decode(queryOrEmpty, StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            queryOrEmpty
        }
        val redactedQuery = redactor.redact(input = decodedQuery, excludedLabels = emptySet(), preserveUrls = false)
        return redactedPath + redactedQuery
    }

    private fun splitPathAndQuery(url: String): Pair<String, String> {
        val q = url.indexOf('?')
        return if (q < 0) url to "" else url.substring(0, q) to url.substring(q)
    }
}