package io.nekohasekai.sagernet.bg

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.decodeBase64
import io.nekohasekai.sagernet.utils.MobileTinaVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** MobileTina subscription marker and online-time expiry support for Exclave's Room model. */
object MobileTinaExpiryManager {
    const val ACTION_EXPIRE = "io.nekohasekai.sagernet.mobiletina.CONFIG_EXPIRE"
    const val ACTION_DATA_CHANGED = "io.nekohasekai.sagernet.mobiletina.DATA_CHANGED"
    const val EXTRA_GROUP_ID = "mobiletina_expiry_group_id"

    private const val PREFS = "mobiletina_expiry"
    private const val PENDING_GROUPS = "pending_groups"
    private const val TRIGGER_PREFIX = "trigger_"
    private const val VERIFY_PREFIX = "mobiletina_expiry_verify_"
    private const val FALLBACK_PREFIX = "mobiletina_expiry_fallback_"
    private const val WORK_GROUP_ID = "group_id"
    private const val TRUSTED_EPOCH = "trusted_epoch"
    private const val TRUSTED_ELAPSED = "trusted_elapsed"
    private const val MIN_NETWORK_TIME = 1_704_067_200_000L
    private const val MAX_NETWORK_TIME = 4_102_444_800_000L
    private const val VPN_STOP_TIMEOUT = 15_000L
    private val tehranZone = ZoneId.of("Asia/Tehran")
    private val mutationMutex = Mutex()
    private val timeSources = arrayOf(
        "https://www.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
        "https://api.github.com/zen",
    )

    data class JsonExpiry(val triggerAtMillis: Long?)

    fun extractJsonExpiry(payload: String?): JsonExpiry? {
        val normalized = payload?.trim().orEmpty()
        parseCustomJson(normalized)?.let { return JsonExpiry(extractTrigger(it)) }
        val decoded = runCatching { normalized.decodeBase64() }.getOrNull() ?: return null
        return parseCustomJson(decoded)?.let { JsonExpiry(extractTrigger(it)) }
    }

    fun syncSubscriptionPayload(
        context: Context,
        group: ProxyGroup,
        subscription: SubscriptionBean,
        payload: String?,
    ) {
        val metadata = extractJsonExpiry(payload) ?: return
        subscription.bytesUsed = -1L
        subscription.bytesRemaining = -1L
        subscription.mobileTinaManagedExpiry = metadata.triggerAtMillis != null
        subscription.expiryDate = metadata.triggerAtMillis?.div(1000L) ?: -1L
        if (metadata.triggerAtMillis == null) {
            cancelForGroup(context, group.id)
        } else {
            persistAndVerify(context, group.id, metadata.triggerAtMillis)
        }
    }

    fun isExpiryMarker(bean: AbstractBean): Boolean {
        if (bean !is SOCKSBean) return false
        bean.applyDefaultValues()
        return bean.serverAddress == "1" && bean.serverPort == 1 &&
                bean.username.isEmpty() && bean.password.isEmpty() && bean.name.isNotBlank()
    }

    fun hasExpiryMarker(): Boolean = runCatching {
        SagerDatabase.groupDao.allGroups().any { group ->
            SagerDatabase.proxyDao.getByGroup(group.id).any { isExpiryMarker(it.requireBean()) }
        }
    }.getOrDefault(false)

    fun existingMarker(): ProxyEntity? = runCatching {
        SagerDatabase.groupDao.allGroups().asSequence()
            .flatMap { SagerDatabase.proxyDao.getByGroup(it.id).asSequence() }
            .firstOrNull { isExpiryMarker(it.requireBean()) }
    }.getOrNull()

    suspend fun retireToMarker(marker: AbstractBean, requireVpnStopped: Boolean = true): ProxyEntity? =
        mutationMutex.withLock {
            marker.applyDefaultValues()
            if (!isExpiryMarker(marker)) return@withLock null
            if (requireVpnStopped && !stopVpnBeforeExpiry()) return@withLock null

            cancelAll(SagerNet.application)
            SagerDatabase.proxyDao.reset()
            SagerDatabase.groupDao.reset()
            val group = ProxyGroup(
                userOrder = 1L,
                ungrouped = true,
                type = GroupType.BASIC,
                order = GroupOrder.BY_DELAY,
            )
            group.id = SagerDatabase.groupDao.createGroup(group)
            val entity = ProxyEntity(groupId = group.id, userOrder = 1L).apply {
                putBean(marker)
                id = SagerDatabase.proxyDao.addProxy(this)
            }
            DataStore.selectedGroup = group.id
            DataStore.selectedProxy = entity.id
            DataStore.currentProfile = 0L
            DataStore.startedProfile = 0L
            SubscriptionUpdater.reconfigureUpdater()
            GroupManager.iterator { groupAdd(group) }
            ProfileManager.iterator { onAdd(entity) }
            notifyDataChanged(SagerNet.application)
            entity
        }

    fun recoverPending(context: Context) {
        pendingGroups(context).forEach { enqueueVerification(context, it, ExistingWorkPolicy.KEEP) }
    }

    fun requestOnlineVerification(context: Context, groupId: Long) {
        if (groupId <= 0L) return
        enqueueVerification(context, groupId, ExistingWorkPolicy.REPLACE)
    }

    fun trustedNowMillis(context: Context = SagerNet.application): Long {
        val prefs = prefs(context)
        val epoch = prefs.getLong(TRUSTED_EPOCH, 0L)
        val elapsed = prefs.getLong(TRUSTED_ELAPSED, 0L)
        return if (epoch in MIN_NETWORK_TIME..MAX_NETWORK_TIME && elapsed > 0L) {
            epoch + (SystemClock.elapsedRealtime() - elapsed).coerceAtLeast(0L)
        } else System.currentTimeMillis()
    }

    private fun persistAndVerify(context: Context, groupId: Long, trigger: Long) {
        val pending = pendingGroups(context).toMutableSet().apply { add(groupId) }
        prefs(context).edit()
            .putLong(triggerKey(groupId), trigger)
            .putStringSet(PENDING_GROUPS, pending.map(Long::toString).toSet())
            .apply()
        enqueueVerification(context, groupId, ExistingWorkPolicy.REPLACE)
    }

    fun cancelForGroup(context: Context, groupId: Long) {
        if (groupId <= 0L) return
        val pending = pendingGroups(context).toMutableSet().apply { remove(groupId) }
        prefs(context).edit()
            .remove(triggerKey(groupId))
            .putStringSet(PENDING_GROUPS, pending.map(Long::toString).toSet())
            .apply()
        cancelScheduled(context, groupId)
    }

    private fun cancelAll(context: Context) {
        // Verification workers may call this method themselves. Leave those workers alive;
        // with the trigger removed they finish as a no-op instead of cancelling mid-mutation.
        pendingGroups(context).forEach { cancelAlarmAndFallback(context, it) }
        prefs(context).edit().clear().apply()
    }

    private suspend fun verifyWithNetworkTime(context: Context, groupId: Long): Boolean {
        val trigger = prefs(context).getLong(triggerKey(groupId), 0L)
        if (trigger <= 0L) return true
        val group = SagerDatabase.groupDao.getById(groupId)
        if (group?.subscription?.mobileTinaManagedExpiry != true) {
            cancelForGroup(context, groupId)
            return true
        }
        val networkNow = fetchNetworkEpochMillis(context) ?: return false
        if (networkNow < trigger) {
            scheduleFromTrustedDelay(context, groupId, trigger - networkNow)
            return true
        }
        if (!stopVpnBeforeExpiry()) return false
        if (prefs(context).getLong(triggerKey(groupId), 0L) != trigger) return true

        val marker = SOCKSBean().apply {
            serverAddress = "1"
            serverPort = 1
            username = ""
            password = ""
            name = MobileTinaVault.expired()
            applyDefaultValues()
        }
        return retireToMarker(marker, requireVpnStopped = false) != null
    }

    private suspend fun stopVpnBeforeExpiry(): Boolean {
        if (!SagerNet.started && DataStore.startedProfile <= 0L) return true
        SagerNet.stopService()
        val deadline = SystemClock.elapsedRealtime() + VPN_STOP_TIMEOUT
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!SagerNet.started && DataStore.startedProfile <= 0L) return true
            delay(100L)
        }
        SagerNet.stopService()
        Logs.w("MobileTina expiry delayed because VPN shutdown was not confirmed")
        return false
    }

    private fun scheduleFromTrustedDelay(context: Context, groupId: Long, delayMillis: Long) {
        val delay = delayMillis.coerceAtLeast(1L)
        cancelAlarmAndFallback(context, groupId)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = SystemClock.elapsedRealtime() + delay
        val pendingIntent = expiryPendingIntent(context, groupId)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()) {
                alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent)
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pendingIntent)
        }
        WorkManager.getInstance(context).enqueueUniqueWork(
            fallbackName(groupId), ExistingWorkPolicy.REPLACE, verificationRequest(groupId, delay)
        )
    }

    private fun enqueueVerification(context: Context, groupId: Long, policy: ExistingWorkPolicy) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            verifyName(groupId), policy, verificationRequest(groupId, 0L)
        )
    }

    private fun verificationRequest(groupId: Long, delayMillis: Long) =
        OneTimeWorkRequestBuilder<ExpiryVerificationWorker>()
            .setInputData(workDataOf(WORK_GROUP_ID to groupId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
            .apply {
                if (delayMillis == 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                }
            }.build()

    private fun cancelScheduled(context: Context, groupId: Long) {
        cancelAlarmAndFallback(context, groupId)
        WorkManager.getInstance(context).cancelUniqueWork(verifyName(groupId))
    }

    private fun cancelAlarmAndFallback(context: Context, groupId: Long) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(expiryPendingIntent(context, groupId))
        WorkManager.getInstance(context).cancelUniqueWork(fallbackName(groupId))
    }

    private fun expiryPendingIntent(context: Context, groupId: Long): PendingIntent {
        val intent = Intent(context, MobileTinaExpiryReceiver::class.java)
            .setAction(ACTION_EXPIRE)
            .setData(Uri.parse("mobiletina://expiry/$groupId"))
            .putExtra(EXTRA_GROUP_ID, groupId)
        return PendingIntent.getBroadcast(
            context,
            (groupId xor (groupId ushr 32)).toInt() and Int.MAX_VALUE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun parseCustomJson(text: String): JsonObject? {
        if (!text.startsWith('{')) return null
        return runCatching {
            JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject
                ?.takeIf { it.get("outbounds")?.isJsonArray == true }
        }.getOrNull()
    }

    private fun extractTrigger(root: JsonObject): Long? {
        val comment = root.get("_comment") ?: return null
        if (!comment.isJsonPrimitive || !comment.asJsonPrimitive.isString) return null
        return parseTimestamp(comment.asString.trim())
    }

    internal fun parseTimestamp(value: String): Long? {
        runCatching { return Instant.parse(value).toEpochMilli() }
        runCatching {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant().toEpochMilli()
        }
        runCatching {
            return ZonedDateTime.parse(value, DateTimeFormatter.ISO_ZONED_DATE_TIME)
                .toInstant().toEpochMilli()
        }
        return runCatching {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(tehranZone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private suspend fun fetchNetworkEpochMillis(context: Context): Long? = withContext(Dispatchers.IO) {
        timeSources.firstNotNullOfOrNull { source ->
            val separator = if ('?' in source) '&' else '?'
            val url = URL("$source${separator}mobiletina_clock=${SystemClock.elapsedRealtime()}")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 7_000
                readTimeout = 7_000
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "MobileTina-Android")
                setRequestProperty("Connection", "close")
            }
            val started = SystemClock.elapsedRealtime()
            try {
                val code = connection.responseCode
                val completed = SystemClock.elapsedRealtime()
                val serverEpoch = connection.getHeaderFieldDate("Date", -1L)
                if (code !in 200..399 || serverEpoch !in MIN_NETWORK_TIME..MAX_NETWORK_TIME) null
                else (serverEpoch + ((completed - started).coerceAtLeast(0L) / 2L)).also { epoch ->
                    prefs(context).edit()
                        .putLong(TRUSTED_EPOCH, epoch)
                        .putLong(TRUSTED_ELAPSED, completed)
                        .apply()
                }
            } catch (e: Exception) {
                Logs.w(e)
                null
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun pendingGroups(context: Context): Set<Long> =
        prefs(context).getStringSet(PENDING_GROUPS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .filterTo(mutableSetOf()) { prefs(context).getLong(triggerKey(it), 0L) > 0L }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun triggerKey(groupId: Long) = TRIGGER_PREFIX + groupId
    private fun verifyName(groupId: Long) = VERIFY_PREFIX + stableToken(groupId)
    private fun fallbackName(groupId: Long) = FALLBACK_PREFIX + stableToken(groupId)
    private fun stableToken(groupId: Long) = MessageDigest.getInstance("SHA-256")
        .digest(groupId.toString().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun notifyDataChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_DATA_CHANGED).setPackage(context.packageName))
    }

    class ExpiryVerificationWorker(appContext: Context, params: WorkerParameters) :
        CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            val groupId = inputData.getLong(WORK_GROUP_ID, 0L)
            if (groupId <= 0L) return Result.failure()
            return if (verifyWithNetworkTime(applicationContext, groupId)) {
                Result.success()
            } else Result.retry()
        }
    }
}
