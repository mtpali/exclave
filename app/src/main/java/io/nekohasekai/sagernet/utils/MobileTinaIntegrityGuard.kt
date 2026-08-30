package io.nekohasekai.sagernet.utils

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ProxyService
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.ui.MainActivity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Detects repackaging plus common manifest/resource edits made by APK modification tools. */
object MobileTinaIntegrityGuard {

    private const val VERIFY_INTERVAL_MS = 37_000L
    private val continuousVerificationInstalled = AtomicBoolean(false)

    private val protectedTextIds = intArrayOf(
        R.string.mobiletina_testing,
        R.string.mobiletina_no_working_server,
        R.string.mobiletina_mode_auto,
        R.string.mobiletina_mode_manual,
        R.string.mobiletina_status_connected,
        R.string.mobiletina_status_disconnected,
        R.string.mobiletina_status_connecting,
        R.string.mobiletina_status_failed,
        R.string.mobiletina_connect_action,
        R.string.mobiletina_open_menu,
        R.string.mobiletina_menu_home,
        R.string.mobiletina_menu_subscriptions,
        R.string.mobiletina_menu_per_app,
        R.string.mobiletina_menu_about,
        R.string.mobiletina_app_title,
        R.string.mobiletina_scan,
        R.string.mobiletina_add,
        R.string.mobiletina_days_remaining,
        R.string.mobiletina_traffic_summary,
        R.string.mobiletina_menu_settings,
        R.string.mobiletina_ping_testing,
        R.string.mobiletina_ping_tap,
        R.string.mobiletina_refreshing_subscription,
        R.string.mobiletina_inactive,
        R.string.mobiletina_ping_value,
        R.string.mobiletina_more_update,
        R.string.mobiletina_more_speed_test,
        R.string.mobiletina_more_sort,
        R.string.mobiletina_sort_latency,
        R.string.mobiletina_sort_name,
        R.string.mobiletina_sort_original,
        R.string.mobiletina_subscriptions_unlocked,
        R.string.mobiletina_copy_subscription_link,
        R.string.mobiletina_all_configs_copied,
        R.string.mobiletina_cancel,
        R.string.mobiletina_enable_internet,
        R.string.mobiletina_back_home,
        R.string.mobiletina_dialog_close,
        R.string.mobiletina_clear_all,
        R.string.mobiletina_clear_all_message,
        R.string.mobiletina_clear_all_confirm,
        R.string.mobiletina_notification_stop,
    )

    private val expectedDigest = byteArrayOf(
        33, -59, -113, -73, 9, -6, -105, 1,
        -6, -116, -14, 89, -49, -118, -61, -83,
        -38, -16, -56, -4, -99, 40, 122, 81,
        -27, -87, 26, 42, 1, -88, 35, -79,
    )

    fun verify(context: Context) {
        if (context.packageName != BuildConfig.APPLICATION_ID) fail()
        verifyReleaseAndManifest(context)
        val textDigest = protectedTextDigest(context)
        if (!MessageDigest.isEqual(textDigest, expectedDigest)) fail()

        if (!BuildConfig.DEBUG) {
            val signerDigest = signingCertificateSha256(context)
            verifySigningCertificate(signerDigest)
            val nonce = SystemClock.elapsedRealtimeNanos() xor
                (Process.myPid().toLong() shl 32) xor Process.myTid().toLong()
            if (!N.c(context.applicationContext, signerDigest, textDigest, nonce)) fail()
        }
    }

    fun installContinuousVerification(application: Application) {
        if (BuildConfig.DEBUG || !continuousVerificationInstalled.compareAndSet(false, true)) return

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = verify(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        val appContext = application.applicationContext
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                verify(appContext)
                handler.postDelayed(this, VERIFY_INTERVAL_MS)
            }
        }, VERIFY_INTERVAL_MS)
    }

    private fun verifyReleaseAndManifest(context: Context) {
        if (BuildConfig.DEBUG) return

        val packageManager = context.packageManager
        val packageInfo = packageInfo(packageManager, context.packageName)
        val applicationInfo = packageInfo.applicationInfo ?: fail()
        val forbiddenFlags = ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_TEST_ONLY
        if (applicationInfo.flags and forbiddenFlags != 0) fail()
        if (applicationInfo.className != SagerNet::class.java.name) fail()
        if (packageInfo.versionName != BuildConfig.VERSION_NAME) fail()
        if (PackageInfoCompat.longVersionCode(packageInfo) != BuildConfig.VERSION_CODE.toLong()) fail()

        val source = runCatching { File(applicationInfo.sourceDir).canonicalFile }.getOrElse { fail() }
        val publicSource = runCatching { File(applicationInfo.publicSourceDir).canonicalFile }
            .getOrElse { fail() }
        if (!source.isFile || source != publicSource || source.extension.lowercase() != "apk") fail()

        val activities = packageInfo.activities.orEmpty()
        val mainActivity = activities.singleOrNull { it.name == MainActivity::class.java.name } ?: fail()
        if (!mainActivity.exported) fail()

        val services = packageInfo.services.orEmpty()
        val proxyService = services.singleOrNull { it.name == ProxyService::class.java.name } ?: fail()
        val vpnService = services.singleOrNull { it.name == VpnService::class.java.name } ?: fail()
        if (proxyService.exported || vpnService.exported) fail()
        if (vpnService.permission != "android.permission.BIND_VPN_SERVICE") fail()
    }

    private fun verifySigningCertificate(actual: ByteArray) {
        val token = decodeSha256(BuildConfig.MOBILETINA_SIGNER_TOKEN) ?: fail()
        val expected = ByteArray(token.size) { index ->
            (token[index].toInt() xor expectedDigest[index].toInt()).toByte()
        }
        if (!MessageDigest.isEqual(actual, expected)) fail()
    }

    private fun signingCertificateSha256(context: Context): ByteArray {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        if (signatures.size != 1) fail()
        return MessageDigest.getInstance("SHA-256").digest(signatures.single().toByteArray())
    }

    private fun protectedTextDigest(context: Context): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        protectedTextIds.forEach { id ->
            digest.update(context.getString(id).toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest()
    }

    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        return packageManager.getPackageInfo(packageName, flags)
    }

    private fun decodeSha256(value: String): ByteArray? {
        if (!value.matches(Regex("[0-9a-f]{64}"))) return null
        return ByteArray(32) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun fail(): Nothing = throw SecurityException()

    private object PackageInfoCompat {
        fun longVersionCode(packageInfo: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode
            else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
    }
}
