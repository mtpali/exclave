package io.nekohasekai.sagernet.utils

import android.content.Context
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import java.security.MessageDigest

/** Detects simple resource-table text edits commonly made by APK resource editors. */
object MobileTinaIntegrityGuard {

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
        val digest = MessageDigest.getInstance("SHA-256")
        protectedTextIds.forEach { id ->
            digest.update(context.getString(id).toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        if (!MessageDigest.isEqual(digest.digest(), expectedDigest)) fail()
    }

    private fun fail(): Nothing = throw SecurityException("MobileTina integrity check failed")
}
