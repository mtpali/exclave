package io.nekohasekai.sagernet.utils

import android.net.Uri
import io.nekohasekai.sagernet.ktx.decodeBase64
import io.nekohasekai.sagernet.ktx.isHTTPorHTTPSURL

/** Normalizes the server-side MobileTina wrapper used for Base64 subscriptions. */
object MobileTinaImportNormalizer {
    private val base64Payload = Regex("^[A-Za-z0-9+/=_\\-]+$")
    private val wrapperSchemes = setOf(
        "v2rayng", "clash", "clashmeta", "sing-box", "hiddify", "stash", "shadowrocket"
    )
    private const val MAX_DECODE_DEPTH = 3

    fun normalize(input: String?): String? {
        if (input == null) return null
        val normalized = input.trim().trimStart('\uFEFF', '\u200B', '\u2060').trim()
        if (!normalized.startsWith('#')) return normalized
        val candidate = normalized.drop(1).trimStart()
        val compactCandidate = candidate.filterNot(Char::isWhitespace)
        if (compactCandidate.length < 8 || !base64Payload.matches(compactCandidate)) return normalized
        return compactCandidate
    }

    /** Returns a normalized, single-line subscription URL or null for other import formats. */
    fun subscriptionUrl(input: String?): String? {
        for (candidate in payloadCandidates(input)) {
            directOrWrappedSubscriptionUrl(candidate)?.let { return it }
            candidate.lineSequence().forEach { line ->
                directOrWrappedSubscriptionUrl(line.trim())?.let { return it }
            }
        }
        return null
    }

    /**
     * Produces the useful representations of subscription text without guessing its final
     * protocol. Panels in the wild commonly add a leading '#', use URL-safe Base64, wrap
     * the value in sub:// or data: URLs, or encode the payload more than once.
     */
    fun payloadCandidates(input: String?): List<String> {
        val first = normalize(input) ?: return emptyList()
        val queue = ArrayDeque<Pair<String, Int>>()
        val result = LinkedHashSet<String>()
        queue.add(first to 0)

        while (queue.isNotEmpty()) {
            val (raw, depth) = queue.removeFirst()
            val candidate = normalize(raw)?.takeIf { it.isNotEmpty() } ?: continue
            if (!result.add(candidate) || depth >= MAX_DECODE_DEPTH) continue

            val encodedBody = when {
                candidate.startsWith("sub://", ignoreCase = true) -> candidate.substringAfter("://")
                candidate.startsWith("base64://", ignoreCase = true) -> candidate.substringAfter("://")
                candidate.startsWith("data:", ignoreCase = true) &&
                        candidate.substringBefore(',').contains(";base64", ignoreCase = true) ->
                    candidate.substringAfter(',', missingDelimiterValue = "")
                else -> candidate
            }.filterNot(Char::isWhitespace)

            if (encodedBody.length >= 8 && base64Payload.matches(encodedBody)) {
                runCatching { encodedBody.decodeBase64() }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { queue.add(it to depth + 1) }
            }

            if ('%' in candidate) {
                runCatching { Uri.decode(candidate) }
                    .getOrNull()
                    ?.takeIf { it != candidate && it.isNotBlank() }
                    ?.let { queue.add(it to depth + 1) }
            }

            if (candidate.length >= 2 && candidate.first() == '"' && candidate.last() == '"') {
                candidate.substring(1, candidate.length - 1)
                    .replace("\\/", "/")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .takeIf { it.isNotBlank() }
                    ?.let { queue.add(it to depth + 1) }
            }
        }
        return result.toList()
    }

    private fun directOrWrappedSubscriptionUrl(normalized: String): String? {
        if (normalized.isBlank() || normalized.contains('\n') || normalized.contains('\r')) return null
        normalized.takeIf(::isHTTPorHTTPSURL)?.let { return it }

        // QR generators and subscription panels commonly wrap the real link in the
        // v2rayNG deep-link format. Match MobileTinaVPN's UrlSchemeActivity behavior.
        val wrapped = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val scheme = wrapped.scheme.orEmpty()
        if (scheme.lowercase() !in wrapperSchemes) {
            return null
        }
        val wrappedUrl = sequenceOf("url", "subscription", "link")
            .mapNotNull(wrapped::getQueryParameter)
            .map(String::trim)
            .firstOrNull(::isHTTPorHTTPSURL)
            .orEmpty()
        if (wrappedUrl.contains('\n') || wrappedUrl.contains('\r')) return null
        return wrappedUrl.takeIf(::isHTTPorHTTPSURL)
    }
}
