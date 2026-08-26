/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlin.math.max
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val MIGRATION_KEY = "mobileTinaSubscriptionAutoUpdateV1"

    data class UpdateSummary(
        val attempted: Int,
        val updated: Int,
    ) {
        val failed: Int get() = attempted - updated
    }

    /** Enables reliable defaults for existing MobileTina subscriptions exactly once. */
    suspend fun initialize() {
        if (DataStore.configurationStore.getBoolean(MIGRATION_KEY) != true) {
            SagerDatabase.groupDao.subscriptions().forEach { group ->
                val subscription = group.subscription ?: return@forEach
                if (subscription.link.isBlank() || subscription.autoUpdate) return@forEach
                subscription.autoUpdate = true
                subscription.autoUpdateDelay = DEFAULT_AUTO_UPDATE_MINUTES
                SagerDatabase.groupDao.updateGroup(group)
            }
            DataStore.configurationStore.putBoolean(MIGRATION_KEY, true)
        }
        reconfigureUpdater()
    }

    suspend fun reconfigureUpdater() {
        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { group ->
                val subscription = group.subscription
                subscription != null && subscription.autoUpdate && subscription.link.isNotBlank()
            }
        val workManager = RemoteWorkManager.getInstance(app)
        if (subscriptions.isEmpty()) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        // PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        val minDelay = max(
            MIN_PERIODIC_MINUTES,
            subscriptions.minOf { it.subscription!!.autoUpdateDelay.toLong() },
        )
        val now = System.currentTimeMillis() / 1000L
        val earliestDueAt = subscriptions.minOf { group ->
            val subscription = group.subscription!!
            subscription.lastUpdated + max(
                MIN_PERIODIC_MINUTES,
                subscription.autoUpdateDelay.toLong(),
            ) * 60L
        }
        val initialDelaySeconds = (earliestDueAt - now)
            .coerceIn(0L, minDelay * 60L)

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequest.Builder(
            UpdateTask::class.java,
            minDelay,
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30L, TimeUnit.SECONDS)
            .apply {
                if (initialDelaySeconds > 0L) {
                    setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
                }
            }
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        Logs.d(
            "reconfigureUpdater, interval: $minDelay min" +
                    if (initialDelaySeconds > 0L) ", initial delay: $initialDelaySeconds s" else "",
        )
    }

    /** Refreshes due subscriptions while the app is visible without needless repeat downloads. */
    suspend fun refreshDueSubscriptions(): UpdateSummary {
        return updateSubscriptions(
            subscriptions = SagerDatabase.groupDao.subscriptions().filter { group ->
                val subscription = group.subscription
                subscription != null && subscription.autoUpdate &&
                        subscription.link.isNotBlank() && isDue(group)
            },
        )
    }

    /** A forced refresh is used only when Smart Connect has no profiles to connect to. */
    suspend fun refreshGroup(group: ProxyGroup, force: Boolean = false): Boolean {
        val subscription = group.subscription ?: return false
        if (subscription.link.isBlank()) return false
        if (!force && (!subscription.autoUpdate || !isDue(group))) return true
        if (group.id in GroupUpdater.updating) return false
        return GroupUpdater.executeUpdate(group, byUser = false)
    }

    private suspend fun updateSubscriptions(subscriptions: List<ProxyGroup>): UpdateSummary {
        var attempted = 0
        var updated = 0
        subscriptions.forEach { group ->
            if (group.id in GroupUpdater.updating) return@forEach
            attempted++
            if (GroupUpdater.executeUpdate(group, byUser = false)) updated++
        }
        return UpdateSummary(attempted, updated)
    }

    private fun isDue(group: ProxyGroup, nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        val subscription = group.subscription ?: return false
        val intervalSeconds = max(
            MIN_PERIODIC_MINUTES,
            subscription.autoUpdateDelay.toLong(),
        ) * 60L
        return subscription.lastUpdated <= 0L || nowSeconds - subscription.lastUpdated >= intervalSeconds
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions = SagerDatabase.groupDao.subscriptions()
                .filter { group ->
                    val subscription = group.subscription
                    subscription != null && subscription.autoUpdate &&
                            subscription.link.isNotBlank() && isDue(group)
                }
            // WorkManager runs in the main process while the VPN normally runs in :bg, so the
            // persisted started profile is the cross-process source of truth here.
            if (DataStore.startedProfile <= 0L) {
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            return try {
                var attempted = 0
                var updated = 0
                for (profile in subscriptions) {
                    if (profile.id in GroupUpdater.updating) continue
                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message, profile.displayName()
                        )
                    )
                    runCatching { nm.notify(2, notification.build()) }
                    attempted++
                    if (GroupUpdater.executeUpdate(profile, byUser = false)) updated++
                }
                if (updated < attempted) Result.retry() else Result.success()
            } finally {
                runCatching { nm.cancel(2) }
            }
        }
    }

    const val DEFAULT_AUTO_UPDATE_MINUTES = 60
    private const val MIN_PERIODIC_MINUTES = 15L

}
