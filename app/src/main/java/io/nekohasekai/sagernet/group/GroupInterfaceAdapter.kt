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

package io.nekohasekai.sagernet.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCancellableCoroutine {
            var resumed = false
            fun tryResume(value: Boolean) {
                if (!resumed) {
                    resumed = true
                    it.resume(value)
                }
            }
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> tryResume(true) }
                    .setNegativeButton(io.nekohasekai.sagernet.R.string.mobiletina_cancel) { _, _ -> tryResume(false) }
                    .setOnCancelListener { _ -> tryResume(false) }
                    .show()
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
    ) {
        // MobileTina updates subscriptions silently. The list is refreshed by GroupManager.
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            context.snackbar(message).show()
        }
    }

}
