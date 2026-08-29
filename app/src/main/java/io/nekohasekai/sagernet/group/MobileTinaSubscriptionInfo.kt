package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubscriptionBean

/** Parses the de-facto Subscription-Userinfo response header without discarding valid old data. */
internal object MobileTinaSubscriptionInfo {

    private val valuePattern = Regex("(?:^|[;,\\s])([a-z]+)\\s*=\\s*([0-9]+)", RegexOption.IGNORE_CASE)

    fun apply(header: String, subscription: SubscriptionBean): Boolean {
        if (header.isBlank()) return false

        val values = valuePattern.findAll(header).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2].toLongOrNull()
        }
        if (values.isEmpty()) return false

        val upload = values["upload"]
        val download = values["download"]
        val total = values["total"]
        val expire = values["expire"]

        val hasTrafficMetadata = upload != null || download != null || total != null
        if (hasTrafficMetadata) {
            val used = (upload ?: 0L) + (download ?: 0L)
            subscription.bytesUsed = used.coerceAtLeast(0L)
            subscription.bytesRemaining = if (total != null && total > 0L) {
                (total - used).coerceAtLeast(0L)
            } else {
                -1L
            }
        }
        subscription.expiryDate = expire ?: -1L
        return hasTrafficMetadata || expire != null
    }
}
