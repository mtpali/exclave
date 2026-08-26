/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026 MobileTina                                               *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.utils

import android.os.SystemClock
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Low-latency, bounded server selection for the MobileTina automatic screen.
 *
 * A full subscription can contain dozens of profiles. Waiting for every profile before
 * connecting makes the common case unnecessarily slow, while blindly reusing a stale result
 * makes the connection unreliable. This selector therefore:
 *
 * 1. reuses a successful result only for a short monotonic-time window;
 * 2. ranks the selected and previously healthy profiles ahead of unknown/failed profiles;
 * 3. probes a small fast batch, followed by bounded fallback batches;
 * 4. retries only the two profiles that were healthy before this run.
 *
 * Every selected profile is either the only available profile, a very recent verified profile,
 * or has succeeded in the current probe run.
 */
object MobileTinaSmartConnect {

    private data class RecentSuccess(
        val profileId: Long,
        val fingerprint: Int,
        val verifiedAtElapsed: Long,
    )

    @Volatile
    private var recentSuccess: RecentSuccess? = null

    suspend fun selectBest(
        profiles: List<ProxyEntity>,
        selectedProxy: Long,
        isCurrent: () -> Boolean,
    ): ProxyEntity? {
        if (profiles.isEmpty() || !isCurrent()) return null
        if (profiles.size == 1) return profiles.first()

        val now = SystemClock.elapsedRealtime()
        recentSuccess?.takeIf {
            now - it.verifiedAtElapsed in 0..RECENT_SUCCESS_TTL_MS
        }?.let { cached ->
            profiles.firstOrNull {
                it.id == cached.profileId && it.status == 1 && it.ping > 0 &&
                        fingerprint(it) == cached.fingerprint
            }?.let { return it }
        }

        val previouslyHealthyIds = profiles.asSequence()
            .filter { it.status == 1 && it.ping > 0 }
            .sortedBy { it.ping }
            .map { it.id }
            .take(RETRY_CANDIDATES)
            .toList()

        val ranked = profiles.sortedWith(
            compareBy<ProxyEntity>(
                { healthRank(it, selectedProxy) },
                { it.ping.takeIf { ping -> ping > 0 } ?: Int.MAX_VALUE },
                { it.userOrder },
            )
        )
        val candidates = buildCandidateList(ranked)

        var offset = 0
        var batchNumber = 0
        while (offset < candidates.size && isCurrent()) {
            val end = minOf(offset + BATCH_SIZE, candidates.size)
            val batch = candidates.subList(offset, end)
            val timeout = if (batchNumber == 0) FAST_TIMEOUT_MS else FALLBACK_TIMEOUT_MS
            val winner = testBatch(batch, timeout, isCurrent).minByOrNull { it.ping }
            if (winner != null && isCurrent()) {
                remember(winner)
                return winner
            }
            offset = end
            batchNumber++
        }

        if (!isCurrent()) return null

        // Transient packet loss is common on mobile networks. A longer retry of only the
        // previously healthy profiles is more useful and much faster than repeating all tests.
        val retry = previouslyHealthyIds.mapNotNull { id -> profiles.firstOrNull { it.id == id } }
        val winner = testBatch(retry, FINAL_RETRY_TIMEOUT_MS, isCurrent).minByOrNull { it.ping }
        if (winner != null && isCurrent()) remember(winner)
        return winner
    }

    private fun buildCandidateList(ranked: List<ProxyEntity>): List<ProxyEntity> {
        if (ranked.size <= MAX_PROFILES_PER_RUN) return ranked

        val preferred = ranked.take(BATCH_SIZE)
        val remaining = ranked.drop(BATCH_SIZE)
        val sampleSize = MAX_PROFILES_PER_RUN - preferred.size
        if (sampleSize <= 1) return preferred + remaining.first()

        // Spread fallback probes over the entire subscription instead of testing only its
        // first rows. Providers commonly group servers by country or transport.
        val sampled = (0 until sampleSize).map { index ->
            val position = index * (remaining.lastIndex.toLong()) / (sampleSize - 1L)
            remaining[position.toInt()]
        }
        return preferred + sampled
    }

    private suspend fun testBatch(
        profiles: List<ProxyEntity>,
        timeout: Int,
        isCurrent: () -> Boolean,
    ): List<ProxyEntity> = supervisorScope {
        profiles.map { profile ->
            async(Dispatchers.IO) { test(profile, timeout, isCurrent) }
        }.awaitAll().filterNotNull()
    }

    private suspend fun test(
        profile: ProxyEntity,
        timeout: Int,
        isCurrent: () -> Boolean,
    ): ProxyEntity? {
        if (!isCurrent()) return null
        return try {
            val latency = V2RayTestInstance(
                profile = profile,
                link = DataStore.connectionTestURL,
                timeout = timeout,
            ).use { it.doTest() }
            if (!isCurrent()) return null
            if (latency <= 0) {
                profile.status = 3
                profile.ping = 0
                profile.error = app.getString(R.string.mobiletina_no_working_server)
                ProfileManager.updateProfile(profile)
                null
            } else {
                profile.status = 1
                profile.ping = latency
                profile.error = null
                ProfileManager.updateProfile(profile)
                profile
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrent()) {
                profile.status = 3
                profile.ping = 0
                profile.error = error.readableMessage
                ProfileManager.updateProfile(profile)
            }
            null
        }
    }

    private fun healthRank(profile: ProxyEntity, selectedProxy: Long): Int = when {
        profile.id == selectedProxy && profile.status == 1 && profile.ping > 0 -> 0
        profile.status == 1 && profile.ping > 0 -> 1
        profile.id == selectedProxy && profile.status != 3 -> 2
        profile.status != 3 -> 3
        else -> 4
    }

    private fun remember(profile: ProxyEntity) {
        recentSuccess = RecentSuccess(
            profileId = profile.id,
            fingerprint = fingerprint(profile),
            verifiedAtElapsed = SystemClock.elapsedRealtime(),
        )
    }

    private fun fingerprint(profile: ProxyEntity): Int {
        return 31 * profile.type + profile.requireBean().hashCode()
    }

    private const val BATCH_SIZE = 8
    private const val MAX_PROFILES_PER_RUN = 24
    private const val RETRY_CANDIDATES = 2
    private const val FAST_TIMEOUT_MS = 2_200
    private const val FALLBACK_TIMEOUT_MS = 3_200
    private const val FINAL_RETRY_TIMEOUT_MS = 4_500
    private const val RECENT_SUCCESS_TTL_MS = 90_000L
}
