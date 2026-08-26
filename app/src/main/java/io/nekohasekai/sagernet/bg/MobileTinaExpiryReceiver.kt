package io.nekohasekai.sagernet.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MobileTinaExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            MobileTinaExpiryManager.ACTION_EXPIRE -> {
                MobileTinaExpiryManager.requestOnlineVerification(
                    context.applicationContext,
                    intent.getLongExtra(MobileTinaExpiryManager.EXTRA_GROUP_ID, 0L),
                )
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                MobileTinaExpiryManager.recoverPending(context.applicationContext)
        }
    }
}
