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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency, bounded server selection for the MobileTina automatic screen.
 *
 * A full subscription can contain dozens of profiles. Waiting for every profile before
 * connecting makes the common case unnecessarily slow, while blindly reusing a stale result
 * makes the connection unreliable. This selector therefore:
 *
 * 1. always re-tests the previously selected profile instead of trusting a stale ping;
 * 2. makes it compete with healthy and evenly distributed alternative profiles;
 * 3. rotates the distributed probes between attempts so a large subscription is explored over time;
 * 4. probes bounded fallback batches and retries only profiles that were healthy before this run.
 *
 * Except for a subscription containing only one profile, every selected profile has succeeded in
 * the current probe run. Consequently a slow or unreachable previous winner cannot be reused just
 * because it won an earlier attempt.
 */
object MobileTinaSmartConnect {

    private val probeRound = AtomicInteger()

    suspend fun selectBest(
        profiles: List<ProxyEntity>,
        selectedProxy: Long,
        isCurrent: () -> Boolean,
    ): ProxyEntity? {
        if (profiles.isEmpty() || !isCurrent()) return null
        if (profiles.size == 1) return profiles.first()

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
        val candidates = buildCandidateList(ranked, selectedProxy, nextProbeRound())

        var offset = 0
        var batchNumber = 0
        while (offset < candidates.size && isCurrent()) {
            val end = minOf(offset + BATCH_SIZE, candidates.size)
            val batch = candidates.subList(offset, end)
            val timeout = if (batchNumber == 0) FAST_TIMEOUT_MS else FALLBACK_TIMEOUT_MS
            val winner = testBatch(batch, timeout, isCurrent).minByOrNull { it.ping }
            if (winner != null && isCurrent()) {
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
        return winner
    }

    private fun buildCandidateList(
        ranked: List<ProxyEntity>,
        selectedProxy: Long,
        round: Int,
    ): List<ProxyEntity> {
        if (ranked.size <= BATCH_SIZE) return ranked

        val competition = LinkedHashMap<Long, ProxyEntity>(BATCH_SIZE)

        // The previous winner must be measured again, but never gets an automatic win.
        ranked.firstOrNull { it.id == selectedProxy }?.let { competition[it.id] = it }

        // Preserve the low-latency fast path by including a few historically healthy servers.
        ranked.asSequence()
            .filter { it.status == 1 && it.ping > 0 }
            .take(PREFERRED_HEALTHY_PROFILES)
            .forEach { competition[it.id] = it }

        // Fill the first batch from across the subscription. Rotating the starting position makes
        // different regions/transports compete on later clicks instead of permanently favoring the
        // first rows of a provider's list.
        val competitionPool = ranked.filterNot { competition.containsKey(it.id) }
        spreadSample(
            competitionPool,
            BATCH_SIZE - competition.size,
            round,
        ).forEach { competition[it.id] = it }

        val candidates = LinkedHashMap<Long, ProxyEntity>(MAX_PROFILES_PER_RUN)
        competition.values.forEach { candidates[it.id] = it }

        val fallbackPool = ranked.filterNot { candidates.containsKey(it.id) }
        spreadSample(
            fallbackPool,
            MAX_PROFILES_PER_RUN - candidates.size,
            round + BATCH_SIZE,
        ).forEach { candidates[it.id] = it }
        return candidates.values.toList()
    }

    private fun spreadSample(
        profiles: List<ProxyEntity>,
        requested: Int,
        round: Int,
    ): List<ProxyEntity> {
        if (requested <= 0 || profiles.isEmpty()) return emptyList()
        if (requested >= profiles.size) return profiles

        val count = minOf(requested, profiles.size)
        val start = Math.floorMod(round, profiles.size)
        return (0 until count).map { index ->
            val spreadOffset = index * profiles.size.toLong() / count
            profiles[((start + spreadOffset) % profiles.size).toInt()]
        }
    }

    private fun nextProbeRound(): Int {
        return probeRound.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) 0 else current + 1
        }
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

    private const val BATCH_SIZE = 8
    private const val MAX_PROFILES_PER_RUN = 24
    private const val PREFERRED_HEALTHY_PROFILES = 3
    private const val RETRY_CANDIDATES = 2
    private const val FAST_TIMEOUT_MS = 2_200
    private const val FALLBACK_TIMEOUT_MS = 3_200
    private const val FINAL_RETRY_TIMEOUT_MS = 4_500
}
