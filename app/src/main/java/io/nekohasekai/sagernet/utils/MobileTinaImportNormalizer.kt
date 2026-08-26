package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.ktx.isHTTPorHTTPSURL

/** Normalizes the server-side MobileTina wrapper used for Base64 subscriptions. */
object MobileTinaImportNormalizer {
    private val base64Payload = Regex("^[A-Za-z0-9+/=_\\-\\r\\n]+$")

    fun normalize(input: String?): String? {
        if (input == null) return null
        val normalized = input.trim().trimStart('\uFEFF').trim()
        if (!normalized.startsWith('#')) return normalized
        val candidate = normalized.drop(1).trimStart()
        if (candidate.length < 8 || !base64Payload.matches(candidate)) return normalized
        return candidate
    }

    /** Returns a normalized, single-line subscription URL or null for other import formats. */
    fun subscriptionUrl(input: String?): String? {
        val normalized = normalize(input) ?: return null
        if (normalized.contains('\n') || normalized.contains('\r')) return null
        return normalized.takeIf(::isHTTPorHTTPSURL)
    }
}
