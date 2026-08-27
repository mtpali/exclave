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
import kotlinx.coroutines.delay
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
 * 4. verifies an all-negative batch against an independent probe URL before declaring it dead;
 * 5. probes bounded fallback batches and retries previously healthy profiles after a short warm-up.
 *
 * Except for a subscription containing only one profile, every selected profile has succeeded in
 * the current probe run. Consequently a slow or unreachable previous winner cannot be reused just
 * because it won an earlier attempt. A run-wide infrastructure failure is stored as "unknown"
 * instead of incorrectly turning the entire subscription red.
 */
object MobileTinaSmartConnect {

    private data class ProbeResult(
        val profile: ProxyEntity,
        val latency: Int = 0,
        val error: String? = null,
    ) {
        val succeeded get() = latency > 0
    }

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
        val primaryLink = DataStore.connectionTestURL.trim().ifBlank { PRIMARY_PROBE_URL }
        val secondaryLink = if (primaryLink.equals(SECONDARY_PROBE_URL, ignoreCase = true)) {
            PRIMARY_PROBE_URL
        } else {
            SECONDARY_PROBE_URL
        }
        val confirmedFailures = LinkedHashMap<Long, ProbeResult>()

        var offset = 0
        var batchNumber = 0
        while (offset < candidates.size && isCurrent()) {
            val end = minOf(offset + BATCH_SIZE, candidates.size)
            val batch = candidates.subList(offset, end)
            val timeout = if (batchNumber == 0) FIRST_PROBE_TIMEOUT_MS else FALLBACK_PROBE_TIMEOUT_MS

            val primaryResults = testBatch(batch, primaryLink, timeout, isCurrent)
            if (!isCurrent()) return null
            primaryResults.filter { it.succeeded }.let { successes ->
                if (successes.isNotEmpty()) {
                    persistSuccesses(successes)
                    persistConfirmedFailures(confirmedFailures.values)
                    return successes.minBy { it.latency }.profile
                }
            }

            // An endpoint can be blocked or flaky on a particular mobile network even when the
            // proxy is healthy. Only a second independent URL may confirm an all-negative batch.
            delay(PROBE_RETRY_DELAY_MS)
            if (!isCurrent()) return null
            val secondaryResults = testBatch(batch, secondaryLink, timeout, isCurrent)
            if (!isCurrent()) return null
            val secondarySuccesses = secondaryResults.filter { it.succeeded }
            if (secondarySuccesses.isNotEmpty()) {
                secondaryResults.filterNot { it.succeeded }
                    .forEach { confirmedFailures[it.profile.id] = it }
                persistSuccesses(secondarySuccesses)
                persistConfirmedFailures(confirmedFailures.values)
                return secondarySuccesses.minBy { it.latency }.profile
            }

            secondaryResults.forEach { confirmedFailures[it.profile.id] = it }
            offset = end
            batchNumber++
        }

        if (!isCurrent()) return null

        // Transient packet loss and cold native/plugin startup are common on mobile devices. If
        // this is a fresh import there are no historical results, so include the selected/first
        // candidates as a bounded warm retry instead of failing immediately.
        val retry = (
                previouslyHealthyIds.mapNotNull { id -> profiles.firstOrNull { it.id == id } } +
                        candidates
                ).distinctBy { it.id }.take(FINAL_RETRY_CANDIDATES)
        delay(PROBE_RETRY_DELAY_MS)
        val retryResults = testBatch(retry, secondaryLink, FINAL_RETRY_TIMEOUT_MS, isCurrent)
        if (!isCurrent()) return null
        val retrySuccesses = retryResults.filter { it.succeeded }
        if (retrySuccesses.isNotEmpty()) {
            retrySuccesses.forEach { confirmedFailures.remove(it.profile.id) }
            retryResults.filterNot { it.succeeded }
                .forEach { confirmedFailures[it.profile.id] = it }
            persistSuccesses(retrySuccesses)
            persistConfirmedFailures(confirmedFailures.values)
            return retrySuccesses.minBy { it.latency }.profile
        }

        // Zero successes across independent endpoints is indistinguishable from a blocked probe
        // URL, missing native resource or temporary network failure. Do not persist a destructive
        // "every server is inactive" conclusion. Clearing stale failed flags also repairs lists
        // poisoned by earlier releases; the next successful probe will repopulate accurate states.
        markAttemptInconclusive(profiles, confirmedFailures.values + retryResults)
        return null
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
        link: String,
        timeout: Int,
        isCurrent: () -> Boolean,
    ): List<ProbeResult> = supervisorScope {
        profiles.map { profile ->
            async(Dispatchers.IO) { test(profile, link, timeout, isCurrent) }
        }.awaitAll()
    }

    private suspend fun test(
        profile: ProxyEntity,
        link: String,
        timeout: Int,
        isCurrent: () -> Boolean,
    ): ProbeResult {
        if (!isCurrent()) return ProbeResult(profile)
        return try {
            val latency = V2RayTestInstance(
                profile = profile,
                link = link,
                timeout = timeout,
            ).use { it.doTest() }
            if (!isCurrent()) return ProbeResult(profile)
            if (latency <= 0) {
                ProbeResult(
                    profile = profile,
                    error = app.getString(R.string.mobiletina_no_working_server),
                )
            } else {
                ProbeResult(profile = profile, latency = latency)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ProbeResult(profile = profile, error = error.readableMessage)
        }
    }

    private suspend fun persistSuccesses(results: Collection<ProbeResult>) {
        if (results.isEmpty()) return
        ProfileManager.updateProfile(results.map { result ->
            result.profile.apply {
                status = 1
                ping = result.latency
                error = null
            }
        })
    }

    private suspend fun persistConfirmedFailures(results: Collection<ProbeResult>) {
        if (results.isEmpty()) return
        ProfileManager.updateProfile(results.distinctBy { it.profile.id }.map { result ->
            result.profile.apply {
                status = 3
                ping = 0
                error = result.error ?: app.getString(R.string.mobiletina_no_working_server)
            }
        })
    }

    private suspend fun markAttemptInconclusive(
        profiles: List<ProxyEntity>,
        results: Collection<ProbeResult>,
    ) {
        val errors = results.associateBy({ it.profile.id }, { it.error })
        val changed = profiles.filter { it.status == 3 || errors.containsKey(it.id) }.map { profile ->
            profile.apply {
                status = 0
                ping = 0
                error = errors[id] ?: error
            }
        }
        if (changed.isNotEmpty()) ProfileManager.updateProfile(changed)
    }

    private fun healthRank(profile: ProxyEntity, selectedProxy: Long): Int = when {
        profile.id == selectedProxy && profile.status == 1 && profile.ping > 0 -> 0
        profile.status == 1 && profile.ping > 0 -> 1
        profile.id == selectedProxy && profile.status != 3 -> 2
        profile.status != 3 -> 3
        else -> 4
    }

    // Exclave's established manual tester uses six concurrent native instances. Staying at that
    // proven ceiling avoids resource/port pressure on low-memory ARMv7 devices.
    private const val BATCH_SIZE = 6
    private const val MAX_PROFILES_PER_RUN = 24
    private const val PREFERRED_HEALTHY_PROFILES = 3
    private const val RETRY_CANDIDATES = 2
    private const val FINAL_RETRY_CANDIDATES = 3
    private const val FIRST_PROBE_TIMEOUT_MS = 5_000
    private const val FALLBACK_PROBE_TIMEOUT_MS = 5_500
    private const val FINAL_RETRY_TIMEOUT_MS = 6_500
    private const val PROBE_RETRY_DELAY_MS = 300L
    private const val PRIMARY_PROBE_URL = "https://www.google.com/generate_204"
    private const val SECONDARY_PROBE_URL = "https://www.gstatic.com/generate_204"
}
