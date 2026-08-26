package io.nekohasekai.sagernet.utils

import android.net.Uri
import io.nekohasekai.sagernet.ktx.decodeBase64
import io.nekohasekai.sagernet.ktx.isHTTPorHTTPSURL

/** Normalizes the server-side MobileTina wrapper used for Base64 subscriptions. */
object MobileTinaImportNormalizer {
    private val base64Payload = Regex("^[A-Za-z0-9+/=_\\-\\r\\n]+$")

    fun normalize(input: String?): String? {
        if (input == null) return null
        val normalized = input.trim().trimStart('\uFEFF').trim()
        if (!normalized.startsWith('#')) return normalized
        val candidate = normalized.drop(1).trimStart()
        val compactCandidate = candidate.filterNot(Char::isWhitespace)
        if (compactCandidate.length < 8 || !base64Payload.matches(compactCandidate)) return normalized
        return compactCandidate
    }

    /** Returns a normalized, single-line subscription URL or null for other import formats. */
    fun subscriptionUrl(input: String?): String? {
        val normalized = normalize(input) ?: return null
        directOrWrappedSubscriptionUrl(normalized)?.let { return it }

        // MobileTinaVPN checks decoded input for subscriptions too. This covers QR or
        // clipboard values such as #Base64 where the decoded value is the actual URL.
        val decoded = runCatching { normalized.decodeBase64() }.getOrNull() ?: return null
        val normalizedDecoded = normalize(decoded) ?: return null
        return directOrWrappedSubscriptionUrl(normalizedDecoded)
    }

    private fun directOrWrappedSubscriptionUrl(normalized: String): String? {
        if (normalized.contains('\n') || normalized.contains('\r')) return null
        normalized.takeIf(::isHTTPorHTTPSURL)?.let { return it }

        // QR generators and subscription panels commonly wrap the real link in the
        // v2rayNG deep-link format. Match MobileTinaVPN's UrlSchemeActivity behavior.
        val wrapped = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val scheme = wrapped.scheme.orEmpty()
        val host = wrapped.host.orEmpty()
        if (!scheme.equals("v2rayng", ignoreCase = true) ||
            (!host.equals("install-config", ignoreCase = true) &&
                    !host.equals("install-sub", ignoreCase = true))) {
            return null
        }
        val wrappedUrl = wrapped.getQueryParameter("url")?.trim().orEmpty()
        if (wrappedUrl.contains('\n') || wrappedUrl.contains('\r')) return null
        return wrappedUrl.takeIf(::isHTTPorHTTPSURL)
    }
}
