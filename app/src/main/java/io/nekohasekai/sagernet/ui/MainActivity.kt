/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 * Copyright (C) 2021 by Max Lv <max.c.lv@gmail.com>                          *
 * Copyright (C) 2021 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
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

package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.text.util.Linkify
import android.view.KeyEvent
import android.view.Gravity
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.bottomappbar.BottomAppBar.FAB_ALIGNMENT_MODE_CENTER
import com.google.android.material.bottomappbar.BottomAppBar.FAB_ALIGNMENT_MODE_END
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.aidl.AppStats
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.TrafficStats
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.test.V2RayTestInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Alerts
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.PackageCache
import io.noties.markwon.Markwon
import libexclavecore.Libexclavecore
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    val userInterface by lazy { GroupInterfaceAdapter(this) }

    private enum class HomeMode { AUTO, MANUAL }

    private var homeMode = HomeMode.AUTO
    private var smartConnectJob: Job? = null
    private var smartConnectGeneration = 0L
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var showingHome = true

    override val onBackPressedCallback = object : OnBackPressedCallback(enabled = false) {
        override fun handleOnBackPressed() {
            displayFragmentWithId(R.id.nav_configuration)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        when (DataStore.fabStyle) {
            FabStyle.SagerNet -> {
                binding.stats.fabAlignmentMode = FAB_ALIGNMENT_MODE_END
                binding.stats.fabCradleMargin = 0F
                binding.stats.fabCradleRoundedCornerRadius = 0F
                binding.stats.cradleVerticalOffset = dp2px(8).toFloat()
            }
            FabStyle.Shadowsocks -> {
                binding.stats.fabAlignmentMode = FAB_ALIGNMENT_MODE_CENTER
                binding.stats.fabCradleMargin = dp2px(5).toFloat()
                binding.stats.fabCradleRoundedCornerRadius = dp2px(6).toFloat()
                binding.stats.cradleVerticalOffset = 0F
            }
        }

        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(
                R.style.Theme_SagerNet_Black, R.style.Theme_SagerNet_LightBlack
            )
        ) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)
        navigation.layoutDirection = View.LAYOUT_DIRECTION_LTR
        navigation.textDirection = View.TEXT_DIRECTION_LTR
        navigation.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        navigation.post { forceLtrTree(navigation) }
        binding.mobiletinaMenu.setOnClickListener { binding.drawerLayout.open() }
        binding.mobiletinaScan.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
        binding.mobiletinaAdd.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
                ?.openMobileTinaAddMenu(binding.mobiletinaAdd)
        }
        binding.mobiletinaMore.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
                ?.openMobileTinaMoreMenu(binding.mobiletinaMore)
        }
        binding.btnModeAuto.setOnClickListener { setHomeMode(HomeMode.AUTO) }
        binding.btnModeManual.setOnClickListener { setHomeMode(HomeMode.MANUAL) }
        binding.fabAuto.setOnClickListener { smartConnectOrStop() }
        binding.fabManual.setOnClickListener { toggleService() }
        binding.btnSmartConnect.setOnClickListener { smartConnectOrStop() }
        if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            ViewCompat.setOnApplyWindowInsetsListener(navigation) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    top = bars.top,
                    right = bars.right,
                    bottom = bars.bottom,
                )
                insets
            }
        } else {
            ViewCompat.setOnApplyWindowInsetsListener(navigation) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            or WindowInsetsCompat.Type.displayCutout()
                )
                v.updatePadding(
                    top = bars.top,
                    left = bars.left,
                    bottom = bars.bottom,
                )
                insets
            }
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }

        binding.fab.setOnClickListener { toggleService() }
        binding.stats.setOnClickListener { if (state == BaseService.State.Connected) binding.stats.testConnection() }

        setContentView(binding.root)

        if (savedInstanceState != null) {
            showingHome = supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment
        }

        changeState(BaseService.State.Idle)
        setHomeMode(HomeMode.AUTO)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        runOnMainDispatcher {
            fun getLicenseKeyName(only: Boolean): String {
                return if (only) "gplv3OnlyAccepted" else "gplv3OrLaterAccepted"
            }
            val only = Libexclavecore.buildWithClash()
            if (DataStore.configurationStore.getBoolean(getLicenseKeyName(only)) != true) {
                DataStore.configurationStore.putBoolean(getLicenseKeyName(only), true)
                DataStore.configurationStore.remove(getLicenseKeyName(!only))
                AlertDialog.Builder(this@MainActivity).apply {
                    setTitle(R.string.license)
                    setView(
                        TextView(this@MainActivity).apply {
                            setPadding(dp2px(16))
                            text = getString(if (only) {
                                R.string.license_gpl_v3_only
                            } else {
                                R.string.license_gpl_v3_or_later
                            })
                            setTextIsSelectable(true)
                            Linkify.addLinks(this, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
                        }
                    )
                    setPositiveButton(android.R.string.ok) { _, _ ->
                        requestPermissions()
                    }
                    setOnCancelListener { _ ->
                        requestPermissions()
                    }
                }.show()
            } else {
                requestPermissions()
            }
        }
    }

    private fun toggleService() {
        if (state.canStop) SagerNet.stopService() else connect.launch(null)
    }

    private fun smartConnectOrStop() {
        if (state.canStop || smartConnectJob?.isActive == true) {
            cancelSmartConnect()
            if (state.canStop) SagerNet.stopService()
            return
        }
        val generation = ++smartConnectGeneration
        updateMobileTinaState(BaseService.State.Connecting)
        binding.tvAutoServer.setText(R.string.mobiletina_testing)
        smartConnectJob = lifecycleScope.launchWhenStarted {
            val profiles = withContext(Dispatchers.IO) {
                SagerDatabase.proxyDao.getByGroup(DataStore.currentGroupId())
            }
            if (generation != smartConnectGeneration) return@launchWhenStarted
            if (profiles.isEmpty()) {
                markSmartConnectFailed(getString(R.string.no_proxies_found))
                return@launchWhenStarted
            }
            val best = if (profiles.size == 1) profiles.first()
            else testAndSelectBest(profiles, generation)
            if (generation != smartConnectGeneration) return@launchWhenStarted
            if (best == null) {
                markSmartConnectFailed(getString(R.string.mobiletina_no_working_server))
                return@launchWhenStarted
            }
            DataStore.selectedProxy = best.id
            binding.tvAutoServer.text = best.displayName()
            binding.tvManualSelected.text = if (best.ping > 0) {
                "${best.displayName()}  •  ${best.ping} ms"
            } else best.displayName()
            binding.tvAutoPing.apply {
                visibility = if (best.ping > 0) View.VISIBLE else View.GONE
                text = if (best.ping > 0) "${best.ping} ms" else ""
            }
            smartConnectJob = null
            connect.launch(null)
        }
    }

    private suspend fun testAndSelectBest(
        profiles: List<ProxyEntity>, generation: Long,
    ): ProxyEntity? = coroutineScope {
        val semaphore = Semaphore(6)
        suspend fun test(profile: ProxyEntity): ProxyEntity? = semaphore.withPermit {
            if (generation != smartConnectGeneration) return@withPermit null
            runCatching {
                val result = V2RayTestInstance(
                    profile, DataStore.connectionTestURL, 3_500
                ).use { it.doTest() }
                profile.status = 1
                profile.ping = result
                profile.error = null
                ProfileManager.updateProfile(profile)
                profile.takeIf { result > 0 }
            }.getOrElse {
                profile.status = 3
                profile.ping = 0
                profile.error = it.readableMessage
                ProfileManager.updateProfile(profile)
                null
            }
        }
        var working = profiles.map { async(Dispatchers.IO) { test(it) } }.awaitAll().filterNotNull()
        if (working.isEmpty() && generation == smartConnectGeneration) {
            delay(350L)
            working = profiles.map { async(Dispatchers.IO) { test(it) } }.awaitAll().filterNotNull()
        }
        working.minByOrNull { it.ping }
    }

    private fun cancelSmartConnect() {
        smartConnectGeneration++
        smartConnectJob?.cancel()
        smartConnectJob = null
    }

    private fun markSmartConnectFailed(message: String) {
        smartConnectJob = null
        updateMobileTinaState(BaseService.State.Stopped, failed = true)
        binding.tvAutoServer.text = message
        snackbar(message).show()
    }

    private fun handleModeSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> swipeDownX = event.x
            MotionEvent.ACTION_UP -> {
                val delta = event.x - swipeDownX
                if (kotlin.math.abs(delta) >= dp2px(72)) {
                    setHomeMode(if (delta < 0) HomeMode.AUTO else HomeMode.MANUAL)
                    return true
                }
            }
        }
        return false
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (showingHome) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeDownX = event.x
                    swipeDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - swipeDownX
                    val dy = event.y - swipeDownY
                    if (kotlin.math.abs(dx) >= dp2px(84) &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.35f) {
                        setHomeMode(if (dx < 0) HomeMode.AUTO else HomeMode.MANUAL)
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setHomeMode(mode: HomeMode) {
        homeMode = mode
        val auto = mode == HomeMode.AUTO
        binding.mobiletinaHomeHeader.visibility = if (showingHome) View.VISIBLE else View.GONE
        binding.autoPanel.visibility = if (showingHome && auto) View.VISIBLE else View.GONE
        binding.coordinator.visibility = if (showingHome && auto) View.GONE else View.VISIBLE
        binding.manualDock.visibility = if (showingHome && !auto) View.VISIBLE else View.GONE
        binding.fab.visibility = View.GONE
        binding.fabProgress.visibility = View.GONE
        binding.stats.visibility = View.GONE
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ConfigurationFragment)
            ?.setMobileTinaPresentation(!auto)
        styleModeButton(binding.btnModeAuto, auto)
        styleModeButton(binding.btnModeManual, !auto)
        if (showingHome && auto) refreshSubscriptionCard()
        if (showingHome && !auto) ensureLatencyOrder()
    }

    private fun ensureLatencyOrder() {
        lifecycleScope.launchWhenStarted {
            withContext(Dispatchers.IO) {
                val group = SagerDatabase.groupDao.getById(DataStore.currentGroupId())
                    ?: return@withContext
                if (group.order != GroupOrder.BY_DELAY) {
                    group.order = GroupOrder.BY_DELAY
                    GroupManager.updateGroup(group, reconfigureUpdater = false)
                }
            }
        }
    }

    private fun refreshSubscriptionCard() {
        lifecycleScope.launchWhenStarted {
            val group = withContext(Dispatchers.IO) {
                SagerDatabase.groupDao.getById(DataStore.currentGroupId())
            }
            val subscription = group?.subscription
            if (subscription == null) {
                binding.subscriptionCard.visibility = View.GONE
                return@launchWhenStarted
            }
            val used = subscription.bytesUsed.coerceAtLeast(0L)
            val remaining = subscription.bytesRemaining.coerceAtLeast(0L)
            val total = used + remaining
            val gb = 1024.0 * 1024.0 * 1024.0
            val days = if (subscription.expiryDate > 0L) {
                (((subscription.expiryDate * 1000L) - System.currentTimeMillis()) / 86_400_000L)
                    .coerceAtLeast(0L)
            } else 0L
            binding.subscriptionCard.visibility = View.VISIBLE
            binding.tvSubscriptionName.text = group.displayName()
            binding.tvSubscriptionDays.text = getString(R.string.mobiletina_days_remaining, days)
            binding.tvSubscriptionTraffic.text = getString(
                R.string.mobiletina_traffic_summary, used / gb, total / gb
            )
            binding.subscriptionProgress.progress = if (total > 0L) {
                ((used * 100L) / total).toInt().coerceIn(0, 100)
            } else 0
        }
    }

    private fun styleModeButton(materialButton: MaterialButton, selected: Boolean) {
        val background = ContextCompat.getColor(
            this,
            if (selected) R.color.mobiletina_mode_selected else R.color.mobiletina_mode_unselected,
        )
        val foreground = ContextCompat.getColor(
            this,
            if (selected) R.color.mobiletina_mode_text_selected else R.color.mobiletina_mode_text_unselected,
        )
        materialButton.backgroundTintList = ColorStateList.valueOf(background)
        materialButton.setTextColor(foreground)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "exclave" && uri.host == "subscription") {
                uri.getQueryParameter("url")?.let {
                    importSubscription(it)
                }
            } else {
                importProfile(uri)
            }
        }
    }

    private fun requestPermissions() {
        if (!DataStore.getInstalledPackagesInited) {
            // For bullshit Chinese OEMs
            DataStore.getInstalledPackagesInited = true
            runOnDefaultDispatcher {
                PackageCache.register()
            }
        }
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!DataStore.postNotificationsPermissionRequested) {
                DataStore.postNotificationsPermissionRequested = true
                if (app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            if (!DataStore.accessLocalNetworkPermissionRequested) {
                DataStore.accessLocalNetworkPermissionRequested = true
                if (app.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                    permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this@MainActivity, permissions.toTypedArray(), 0)
        }
    }

    fun urlTest(): Int {
        if (state != BaseService.State.Connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: String) {
        val group = ProxyGroup(
            name = getString(R.string.mobiletina_subscription_name),
            type = GroupType.SUBSCRIPTION,
        ).apply {
            subscription = SubscriptionBean().apply {
                link = uri
            }
        }

        finishImportSubscription(group)

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        val created = GroupManager.createGroup(subscription)
        DataStore.selectedGroup = created.id
        GroupUpdater.executeUpdate(created, byUser = false)
        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)
            setHomeMode(HomeMode.AUTO)
            refreshSubscriptionCard()
        }
    }

    private fun forceLtrTree(view: View) {
        view.layoutDirection = View.LAYOUT_DIRECTION_LTR
        view.textDirection = View.TEXT_DIRECTION_LTR
        view.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        if (view is TextView) {
            view.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) forceLtrTree(view.getChildAt(index))
        }
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseShareLinks(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, getString(pluginEntity.nameId)
                )
            ).show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.nav_clear_all) {
            binding.drawerLayout.closeDrawers()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.mobiletina_clear_all)
                .setMessage(R.string.mobiletina_clear_all_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobiletina_clear_all_confirm) { _, _ -> clearAllConfigurations() }
                .show()
            return true
        }
        if (item.itemId == R.id.nav_per_app) {
            startActivity(Intent(this, AppManagerActivity::class.java))
            binding.drawerLayout.closeDrawers()
            return true
        }
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }

    private fun clearAllConfigurations() {
        if (state.canStop) SagerNet.stopService()
        lifecycleScope.launchWhenStarted {
            withContext(Dispatchers.IO) {
                GroupManager.deleteGroup(SagerDatabase.groupDao.allGroups())
                DataStore.selectedGroup = 0L
                DataStore.selectedProxy = 0L
            }
            displayFragmentWithId(R.id.nav_configuration)
            setHomeMode(HomeMode.AUTO)
        }
    }


    fun displayFragment(fragment: ToolbarFragment, home: Boolean = false) {
        showingHome = home
        if (::binding.isInitialized) {
            binding.mobiletinaHomeHeader.visibility = if (home) View.VISIBLE else View.GONE
            binding.autoPanel.visibility = if (home && homeMode == HomeMode.AUTO) View.VISIBLE else View.GONE
            binding.coordinator.visibility = if (home && homeMode == HomeMode.AUTO) View.GONE else View.VISIBLE
            binding.manualDock.visibility = if (home && homeMode == HomeMode.MANUAL) View.VISIBLE else View.GONE
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, fragment)
            .commitAllowingStateLoss()
        supportFragmentManager.executePendingTransactions()
        (fragment as? ConfigurationFragment)?.setMobileTinaPresentation(home)
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> {
                displayFragment(ConfigurationFragment(), home = true)
                connection.bandwidthTimeout = connection.bandwidthTimeout
            }
            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> {
                displayFragment(TrafficFragment())
                connection.trafficTimeout = connection.trafficTimeout
            }
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_about -> displayFragment(AboutFragment())
            else -> return false
        }
        navigation.menu.findItem(id)?.isChecked = true
        return true
    }

    fun ruleCreated() {
        navigation.menu.findItem(R.id.nav_route)?.isChecked = true
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_holder, RouteFragment())
            .commitAllowingStateLoss()
        if (SagerNet.started) {
            snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                SagerNet.reloadService()
            }.show()
        }
    }

    var state = BaseService.State.Idle

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
        animate: Boolean = false,
    ) {
        val started = state == BaseService.State.Connected

        if (!started) {
            statsUpdated(emptyList())
        }

        binding.fab.changeState(state, this.state, animate)
        binding.stats.changeState(state)
        updateMobileTinaState(state, failed = msg != null && state == BaseService.State.Stopped)
        if (msg != null) snackbar(msg).show()
        this.state = state

        when (state) {
            BaseService.State.Connected, BaseService.State.Stopped -> {
                statsUpdated(emptyList())
                runOnDefaultDispatcher {
                    // refresh view
                    ProfileManager.postUpdate(DataStore.currentProfile)
                }
            }
            else -> {}
        }
    }

    private fun updateMobileTinaState(
        state: BaseService.State,
        profileName: String? = null,
        failed: Boolean = false,
    ) {
        val (image, status) = if (failed) {
            R.drawable.mt_auto_red to R.string.mobiletina_status_failed
        } else when (state) {
            BaseService.State.Connected -> R.drawable.mt_auto_blue to R.string.mobiletina_status_connected
            BaseService.State.Connecting, BaseService.State.Stopping ->
                R.drawable.mt_auto_yellow to R.string.mobiletina_status_connecting
            BaseService.State.Idle, BaseService.State.Stopped ->
                R.drawable.mt_auto_white to R.string.mobiletina_status_disconnected
        }
        binding.fabAuto.setImageResource(image)
        binding.tvAutoStatus.setText(status)
        val visibleProfile = profileName?.takeUnless { it.equals("Idle", ignoreCase = true) }
        if (state == BaseService.State.Idle || state == BaseService.State.Stopped) {
            binding.tvAutoServer.text = ""
            binding.tvManualSelected.text = ""
        } else if (!visibleProfile.isNullOrBlank()) {
            binding.tvAutoServer.text = visibleProfile
            binding.tvManualSelected.text = visibleProfile
        }
        binding.fabManual.setImageResource(
            if (state == BaseService.State.Connected) R.drawable.mt_manual_fab
            else R.drawable.mt_manual_stop
        )
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.drawerLayout, text, Snackbar.LENGTH_LONG).apply {
            if (homeMode == HomeMode.MANUAL) anchorView = binding.fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
        updateMobileTinaState(state, profileName)
    }

    override fun statsUpdated(stats: List<AppStats>) {
        (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? TrafficFragment)?.emitStats(
            stats
        )
    }

    override fun routeAlert(type: Int, routeName: String) {
        val markwon = Markwon.create(this)
        when (type) {
            Alerts.ROUTE_ALERT_NOT_VPN -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_vpn, routeName))
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
            Alerts.ROUTE_ALERT_NEED_COARSE_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_coarse_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_NEED_FINE_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_fine_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_NEED_BACKGROUND_LOCATION_ACCESS -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_background_location, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.missing_permission)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent().apply {
                                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                data = Uri.fromParts(
                                    "package", packageName, null
                                )
                            })
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_LOCATION_DISABLED -> {
                val message = markwon.toMarkdown(getString(R.string.route_need_location_enabled, routeName))
                MaterialAlertDialogBuilder(this).setTitle(R.string.location_disabled)
                    .setMessage(message)
                    .setNeutralButton(R.string.open_settings) { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        } catch (e: Exception) {
                            snackbar(e.readableMessage).show()
                        }
                    }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            Alerts.ROUTE_ALERT_ALL_PACKAGES_UNINSTALLED -> {
                val message = markwon.toMarkdown("Ignored $routeName because all packages are uninstalled.")
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    val connection = SagerConnection(true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(try {
        BaseService.State.entries[service.state].also {
            SagerNet.started = it.canStop
        }
    } catch (_: RemoteException) {
        BaseService.State.Idle
    })

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun trafficUpdated(profileId: Long, stats: TrafficStats, isCurrent: Boolean) {
        if (profileId == 0L) return

        if (isCurrent) binding.stats.updateTraffic(
            stats.txRateProxy, stats.rxRateProxy
        )

        runOnDefaultDispatcher {
            ProfileManager.postTrafficUpdated(profileId, stats)
        }
    }

    override fun profilePersisted(profileId: Long) {
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(profileId)
        }
    }

    override fun observatoryResultsUpdated(groupId: Long) {
        runOnDefaultDispatcher {
            GroupManager.postReload(groupId)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (state.canStop) {
                    snackbar(getString(R.string.restart)).setAction(R.string.apply) {
                        SagerNet.reloadService()
                    }.show()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = 1000
        GroupManager.userInterface = userInterface
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        GroupManager.userInterface = null
        super.onStop()
    }

    override fun onDestroy() {
        cancelSmartConnect()
        super.onDestroy()
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }

        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false

        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

}
