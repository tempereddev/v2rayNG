package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayoutMediator
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.databinding.DialogWhatsNewBinding
import com.v2ray.ang.databinding.DialogImportSubscriptionBinding
import com.v2ray.ang.dto.SubscriptionItem
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.enums.RoutingType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.ForkReleaseNotesManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : HelperBaseActivity(), NavigationView.OnNavigationItemSelectedListener {
    private enum class RoutingMode(
        val prefValue: String,
        val buttonId: Int,
        val routingPresetIndex: Int,
        val labelResId: Int
    ) {
        RULE("rule", R.id.btn_mode_rule, RoutingType.WHITE_IRAN.ordinal, R.string.routing_mode_rule),
        GLOBAL("global", R.id.btn_mode_global, RoutingType.GLOBAL.ordinal, R.string.routing_mode_global),
        DIRECT("direct", R.id.btn_mode_direct, RoutingType.DIRECT.ordinal, R.string.routing_mode_direct);

        companion object {
            fun fromStoredValue(value: String?) = entries.firstOrNull { it.prefValue == value } ?: RULE
            fun fromButtonId(buttonId: Int) = entries.firstOrNull { it.buttonId == buttonId }
        }
    }

    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }

    private var isQuickTestRunning = false
    private var hasCheckedWhatsNewThisSession = false
    val mainViewModel: MainViewModel by viewModels()
    private lateinit var groupPagerAdapter: GroupPagerAdapter
    private var tabMediator: TabLayoutMediator? = null

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    private val requestVpnForTest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            proceedQuickTest()
        }
    }

    private val requestActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
        if (SettingsChangeManager.consumeSetupGroupTab()) {
            setupGroupTab()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupToolbar(binding.toolbar, false, getString(R.string.title_server))

        groupPagerAdapter = GroupPagerAdapter(this, emptyList())
        binding.viewPager.adapter = groupPagerAdapter
        binding.viewPager.isUserInputEnabled = true

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        binding.navView.setNavigationItemSelectedListener(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setupHomeControls()
        setupGroupTab()
        setupObservers()
        mainViewModel.reloadServerList()

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {
        }
    }

    private fun setupHomeControls() {
        binding.fab.setOnClickListener { handleFabAction() }
        binding.fabLocate.setOnClickListener { locateSelectedServer() }
        binding.layoutTest.setOnClickListener { handleLayoutTestClick() }
        setupRoutingModeToggle()
        setupQuickTestButton()
    }

    private fun setupObservers() {
        mainViewModel.updateTestResultAction.observe(this) { setTestState(it) }
        mainViewModel.isRunning.observe(this) { isRunning ->
            applyRunningState(isLoading = false, isRunning = isRunning)
        }
        mainViewModel.quickTestFinished.observe(this) { finished ->
            if (finished == true) {
                handleQuickTestFinished()
            }
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun handleQuickTestFinished() {
        val shouldReconnect = mainViewModel.isQuickTest
        isQuickTestRunning = false
        mainViewModel.isQuickTest = false
        mainViewModel.quickTestFinished.value = null
        renderQuickTestState(isRunning = false)
        setTestState(getString(R.string.quick_test_done))

        if (shouldReconnect) {
            if (mainViewModel.isRunning.value == true) {
                restartV2Ray()
            } else {
                startV2Ray()
            }
        }
    }

    private fun setupGroupTab() {
        val groups = mainViewModel.getSubscriptions(this)
        groupPagerAdapter.update(groups)

        tabMediator?.detach()
        tabMediator = TabLayoutMediator(binding.tabGroup, binding.viewPager) { tab, position ->
            groupPagerAdapter.groups.getOrNull(position)?.let {
                tab.text = it.remarks
                tab.tag = it.id
            }
        }.also { it.attach() }

        attachSubscriptionTabActions()

        val targetIndex = groups.indexOfFirst { it.id == mainViewModel.subscriptionId }
            .takeIf { it >= 0 }
            ?: (groups.size - 1)
        binding.viewPager.setCurrentItem(targetIndex, false)

        val onlyDefault = groups.size == 1 && groups[0].id == AppConfig.DEFAULT_SUBSCRIPTION_ID
        binding.tabGroup.isVisible = !onlyDefault
    }

    private fun attachSubscriptionTabActions() {
        for (index in 0 until binding.tabGroup.tabCount) {
            val tab = binding.tabGroup.getTabAt(index) ?: continue
            val subscriptionId = tab.tag as? String ?: continue
            if (subscriptionId.isEmpty() || subscriptionId == AppConfig.DEFAULT_SUBSCRIPTION_ID) {
                continue
            }
            tab.view.setOnLongClickListener {
                showRenameSubscriptionDialog(subscriptionId, tab.text?.toString().orEmpty())
                true
            }
        }
    }

    private fun showRenameSubscriptionDialog(subscriptionId: String, currentName: String) {
        val editText = EditText(this).apply {
            setText(currentName)
            selectAll()
            setSingleLine()
        }
        val container = FrameLayout(this).apply {
            val horizontalPadding = (16 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, horizontalPadding, horizontalPadding, 0)
            addView(editText)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_rename_subscription)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val updatedName = editText.text.toString().trim()
                if (updatedName.isNotEmpty() && updatedName != currentName) {
                    val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return@setPositiveButton
                    subItem.remarks = updatedName
                    MmkvManager.encodeSubscription(subscriptionId, subItem)
                    setupGroupTab()
                    toast(R.string.toast_rename_success)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value == true) {
            applyRunningState(isLoading = false, isRunning = false)
            V2RayServiceManager.stopVService(this)
            return
        }

        applyRunningState(isLoading = true, isRunning = false)
        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            setTestState(getString(R.string.connection_test_testing))
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        V2RayServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            V2RayServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun setTestState(content: String?) {
        binding.tvTestState.text = content
    }

    private fun applyRunningState(isLoading: Boolean, isRunning: Boolean) {
        if (isLoading) {
            binding.fab.setImageResource(R.drawable.ic_fab_check)
            return
        }

        if (isRunning) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
            binding.fab.contentDescription = getString(R.string.action_stop_service)
            setTestState(getString(R.string.connection_connected))
            binding.layoutTest.isFocusable = true
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_inactive))
            binding.fab.contentDescription = getString(R.string.tasker_start_service)
            setTestState(getString(R.string.connection_not_connected))
            binding.layoutTest.isFocusable = false
        }
    }

    private fun setupRoutingModeToggle() {
        val storedMode = MmkvManager.decodeSettingsString(AppConfig.PREF_ROUTING_MODE)
        val activeMode = RoutingMode.fromStoredValue(storedMode)

        if (storedMode == null) {
            applyRoutingMode(activeMode, showToast = false, restartIfRunning = false)
        }
        binding.toggleRoutingMode.check(activeMode.buttonId)

        binding.toggleRoutingMode.addOnButtonCheckedListener { _, buttonId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            RoutingMode.fromButtonId(buttonId)?.let(::applyRoutingMode)
        }
    }

    private fun applyRoutingMode(
        mode: RoutingMode,
        showToast: Boolean = true,
        restartIfRunning: Boolean = true
    ) {
        MmkvManager.encodeSettings(AppConfig.PREF_ROUTING_MODE, mode.prefValue)

        lifecycleScope.launch(Dispatchers.IO) {
            SettingsManager.resetRoutingRulesetsFromPresets(this@MainActivity, mode.routingPresetIndex)
            withContext(Dispatchers.Main) {
                if (showToast) {
                    toast(getString(R.string.routing_mode_applied, getString(mode.labelResId)))
                }
                if (restartIfRunning && mainViewModel.isRunning.value == true) {
                    restartV2Ray()
                }
            }
        }
    }

    private fun setupQuickTestButton() {
        binding.btnQuickTest.setOnClickListener {
            if (isQuickTestRunning) {
                cancelQuickTest()
            } else {
                startQuickTest()
            }
        }
    }

    private fun startQuickTest() {
        if (mainViewModel.serversCache.isEmpty()) {
            toast(R.string.quick_test_no_servers)
            return
        }

        if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                requestVpnForTest.launch(intent)
                return
            }
        }
        proceedQuickTest()
    }

    private fun proceedQuickTest() {
        isQuickTestRunning = true
        renderQuickTestState(isRunning = true)
        mainViewModel.forceAutoSort = true
        mainViewModel.isQuickTest = true
        mainViewModel.testAllRealPing()
    }

    private fun cancelQuickTest() {
        isQuickTestRunning = false
        mainViewModel.isQuickTest = false
        renderQuickTestState(isRunning = false)
        MessageUtil.sendMsg2TestService(this, TestServiceMessage(AppConfig.MSG_MEASURE_CONFIG_CANCEL))
    }

    private fun renderQuickTestState(isRunning: Boolean) {
        binding.btnQuickTest.text = getString(
            if (isRunning) R.string.quick_test_cancel else R.string.quick_test_button
        )
        binding.btnQuickTest.isEnabled = true
    }

    override fun onResume() {
        super.onResume()
        if (!hasCheckedWhatsNewThisSession) {
            hasCheckedWhatsNewThisSession = true
            maybeShowWhatsNewDialog()
        }
        checkForAppUpdate()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    mainViewModel.filterConfig(newText.orEmpty())
                    return false
                }
            })

            searchView.setOnCloseListener {
                mainViewModel.filterConfig("")
                false
            }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.add_config -> {
            val anchor = binding.toolbar.findViewById<View>(R.id.add_config) ?: binding.toolbar
            showAddConfigSheet(anchor)
            true
        }

        R.id.export_all -> {
            exportAll()
            true
        }

        R.id.ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllTcping()
            true
        }

        R.id.real_ping_all -> {
            toast(getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
            mainViewModel.testAllRealPing()
            true
        }

        R.id.service_restart -> {
            restartV2Ray()
            true
        }

        R.id.del_all_config -> {
            delAllConfig()
            true
        }

        R.id.del_duplicate_config -> {
            delDuplicateConfig()
            true
        }

        R.id.del_invalid_config -> {
            delInvalidConfig()
            true
        }

        R.id.sort_by_test_results -> {
            sortByTestResults()
            true
        }

        R.id.sub_update -> {
            importConfigViaSub()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerGroupActivity::class.java)
            )
        } else {
            startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(this, ServerActivity::class.java)
            )
        }
    }

    private fun importQRcode(): Boolean {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(this)
            if (clipboard.isNullOrBlank()) {
                toastError(R.string.toast_failure)
                return false
            }

            val subUrls = AngConfigManager.extractSubscriptionUrls(clipboard)
            if (subUrls.isNotEmpty()) {
                showImportSubscriptionDialog(subUrls.first())
            } else {
                importBatchConfig(clipboard)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun showImportSubscriptionDialog(url: String) {
        val existingGuid = AngConfigManager.findExistingSubscriptionByUrl(url)
        if (existingGuid != null) {
            AlertDialog.Builder(this)
                .setTitle(R.string.title_sub_import)
                .setMessage(R.string.toast_sub_already_exists)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val dialogBinding = DialogImportSubscriptionBinding.inflate(layoutInflater)
        val suggestedName = AngConfigManager.extractSubscriptionName(url)
        dialogBinding.etSubName.setText(suggestedName)
        dialogBinding.tvSubUrl.text = url
        dialogBinding.chkEnable.isChecked = true
        dialogBinding.chkAutoUpdate.isChecked = true

        AlertDialog.Builder(this)
            .setTitle(R.string.title_sub_import)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.menu_item_save_config) { _, _ ->
                val name = dialogBinding.etSubName.text.toString().ifBlank { suggestedName }
                val enabled = dialogBinding.chkEnable.isChecked
                val autoUpdate = dialogBinding.chkAutoUpdate.isChecked

                val subItem = SubscriptionItem(
                    remarks = name,
                    url = url,
                    enabled = enabled,
                    autoUpdate = autoUpdate
                )

                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val guid = AngConfigManager.importSubscription(subItem)
                    val result = AngConfigManager.updateConfigViaSingleSub(guid)
                    delay(500L)
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        if (result.configCount > 0) {
                            toast(getString(R.string.title_import_sub_config_count, name, result.configCount))
                        } else {
                            toast(R.string.import_subscription_success)
                        }
                        mainViewModel.subscriptionIdChanged(guid)
                        setupGroupTab()
                        mainViewModel.reloadServerList()
                        lifecycleScope.launch {
                            delay(1500L)
                            toast(R.string.hint_long_press_rename)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importBatchConfig(server: String?) {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val (count, countSub) = AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true)
                delay(500L)
                withContext(Dispatchers.Main) {
                    when {
                        count > 0 -> {
                            toast(getString(R.string.title_import_config_count, count))
                            mainViewModel.reloadServerList()
                        }

                        countSub > 0 -> setupGroupTab()
                        else -> toastError(R.string.toast_failure)
                    }
                    hideLoading()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                    hideLoading()
                }
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        }
    }

    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

    private fun importConfigViaSub(): Boolean {
        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {
            val result = mainViewModel.updateConfigViaSubAll()
            delay(500L)
            launch(Dispatchers.Main) {
                if (result.configCount > 0) {
                    toast(getString(R.string.title_update_config_count, result.configCount))
                    mainViewModel.reloadServerList()
                } else {
                    toastError(R.string.toast_failure)
                }
                hideLoading()
            }
        }
        return true
    }

    fun refreshSubscriptionsFromHome(): Boolean = importConfigViaSub()

    private fun exportAll() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val ret = mainViewModel.exportAllServer()
            launch(Dispatchers.Main) {
                if (ret > 0) {
                    toast(getString(R.string.title_export_config_count, ret))
                } else {
                    toastError(R.string.toast_failure)
                }
                hideLoading()
            }
        }
    }

    private fun delAllConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeAllServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun delDuplicateConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeDuplicateServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_duplicate_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun delInvalidConfig() {
        AlertDialog.Builder(this).setMessage(R.string.del_invalid_config_comfirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                showLoading()
                lifecycleScope.launch(Dispatchers.IO) {
                    val ret = mainViewModel.removeInvalidServer()
                    launch(Dispatchers.Main) {
                        mainViewModel.reloadServerList()
                        toast(getString(R.string.title_del_config_count, ret))
                        hideLoading()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> }
            .show()
    }

    private fun sortByTestResults() {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            mainViewModel.sortByTestResults()
            launch(Dispatchers.Main) {
                mainViewModel.reloadServerList()
                hideLoading()
            }
        }
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri != null) {
                readContentFromUri(uri)
            }
        }
    }

    private fun readContentFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri).use { input ->
                importBatchConfig(input?.bufferedReader()?.readText())
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to read content from URI", e)
        }
    }

    private fun locateSelectedServer() {
        val targetSubscriptionId = mainViewModel.findSubscriptionIdBySelect()
        if (targetSubscriptionId.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        val targetGroupIndex = groupPagerAdapter.groups.indexOfFirst { it.id == targetSubscriptionId }
        if (targetGroupIndex < 0) {
            toast(R.string.toast_server_not_found_in_group)
            return
        }

        if (binding.viewPager.currentItem != targetGroupIndex) {
            binding.viewPager.setCurrentItem(targetGroupIndex, true)
            binding.viewPager.postDelayed({ scrollToSelectedServer(targetGroupIndex) }, 1000)
        } else {
            scrollToSelectedServer(targetGroupIndex)
        }
    }

    private fun scrollToSelectedServer(groupIndex: Int) {
        val itemId = groupPagerAdapter.getItemId(groupIndex)
        val fragment = supportFragmentManager.findFragmentByTag("f$itemId") as? GroupServerFragment

        if (fragment?.isAdded == true && fragment.view != null) {
            fragment.scrollToSelectedServer()
        } else {
            toast(R.string.toast_fragment_not_available)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.sub_setting -> requestActivityLauncher.launch(Intent(this, SubSettingActivity::class.java))
            R.id.per_app_proxy_settings -> requestActivityLauncher.launch(Intent(this, PerAppProxyActivity::class.java))
            R.id.routing_setting -> requestActivityLauncher.launch(Intent(this, RoutingSettingActivity::class.java))
            R.id.user_asset_setting -> requestActivityLauncher.launch(Intent(this, UserAssetActivity::class.java))
            R.id.settings -> requestActivityLauncher.launch(Intent(this, SettingsActivity::class.java))
            R.id.promotion -> Utils.openUri(this, "${Utils.decode(AppConfig.APP_PROMOTION_URL)}?t=${System.currentTimeMillis()}")
            R.id.logcat -> startActivity(Intent(this, LogcatActivity::class.java))
            R.id.check_for_update -> startActivity(Intent(this, CheckUpdateActivity::class.java))
            R.id.backup_restore -> requestActivityLauncher.launch(Intent(this, BackupActivity::class.java))
            R.id.about -> startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun checkForAppUpdate() {
        if (!UpdateCheckerManager.shouldAutoCheck()) {
            return
        }

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(false)
                UpdateCheckerManager.markUpdateChecked()
                withContext(Dispatchers.Main) {
                    showUpdateBanner(result.latestVersion.takeIf { result.hasUpdate })
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showUpdateBanner(latestVersion: String?) {
        if (latestVersion != null) {
            binding.layoutUpdateBanner.isVisible = true
            binding.tvUpdateBannerText.text = getString(R.string.update_banner_text, latestVersion)
            binding.layoutUpdateBanner.setOnClickListener {
                startActivity(Intent(this, CheckUpdateActivity::class.java))
            }
        } else {
            binding.layoutUpdateBanner.isVisible = false
        }
    }

    private fun maybeShowWhatsNewDialog() {
        val currentVersion = BuildConfig.VERSION_NAME
        val lastSeenVersion = MmkvManager.decodeSettingsString(AppConfig.PREF_LAST_SEEN_CHANGELOG_VERSION)
        if (lastSeenVersion == currentVersion) {
            return
        }

        val currentRelease = ForkReleaseNotesManager.getCurrentRelease(this)
        if (currentRelease == null) {
            MmkvManager.encodeSettings(AppConfig.PREF_LAST_SEEN_CHANGELOG_VERSION, currentVersion)
            return
        }

        val message = ForkReleaseNotesManager.formatForDisplay(this, currentRelease)
        if (message.isBlank()) {
            MmkvManager.encodeSettings(AppConfig.PREF_LAST_SEEN_CHANGELOG_VERSION, currentVersion)
            return
        }

        val dialogBinding = DialogWhatsNewBinding.inflate(layoutInflater)
        dialogBinding.tvWhatsNewTitle.text = getString(R.string.whats_new_title, currentRelease.version)
        dialogBinding.tvWhatsNewSummary.text = currentRelease.summary.trim()
        dialogBinding.tvWhatsNewHighlights.text = ForkReleaseNotesManager.formatForDialog(currentRelease)

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.whats_new_view_release) { _, _ ->
                Utils.openUri(this, ForkReleaseNotesManager.getReleaseUrl(currentRelease))
            }
            .setNegativeButton(R.string.whats_new_close, null)
            .setOnDismissListener {
                MmkvManager.encodeSettings(AppConfig.PREF_LAST_SEEN_CHANGELOG_VERSION, currentVersion)
            }
            .show()
    }

    private fun showAddConfigSheet(anchor: View) {
        val popup = PopupMenu(ContextThemeWrapper(this, R.style.CompactPopupMenuTheme), anchor)
        popup.menuInflater.inflate(R.menu.menu_add_config, popup.menu)
        popup.forceShowIconsCompat()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.import_clipboard -> importClipboard()
                R.id.import_qrcode -> importQRcode()
                R.id.import_local -> importConfigLocal()
                R.id.add_manually -> {
                    showAddManuallyMenu(anchor)
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun showAddManuallyMenu(anchor: View) {
        val popup = PopupMenu(ContextThemeWrapper(this, R.style.CompactPopupMenuTheme), anchor)
        popup.menuInflater.inflate(R.menu.menu_add_manually, popup.menu)
        popup.forceShowIconsCompat()
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.import_manually_vmess -> {
                    importManually(EConfigType.VMESS.value)
                    true
                }
                R.id.import_manually_vless -> {
                    importManually(EConfigType.VLESS.value)
                    true
                }
                R.id.import_manually_ss -> {
                    importManually(EConfigType.SHADOWSOCKS.value)
                    true
                }
                R.id.import_manually_trojan -> {
                    importManually(EConfigType.TROJAN.value)
                    true
                }
                R.id.import_manually_hysteria2 -> {
                    importManually(EConfigType.HYSTERIA2.value)
                    true
                }
                R.id.import_manually_wireguard -> {
                    importManually(EConfigType.WIREGUARD.value)
                    true
                }
                R.id.import_manually_socks -> {
                    importManually(EConfigType.SOCKS.value)
                    true
                }
                R.id.import_manually_http -> {
                    importManually(EConfigType.HTTP.value)
                    true
                }
                R.id.import_manually_policy_group -> {
                    importManually(EConfigType.POLICYGROUP.value)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun PopupMenu.forceShowIconsCompat() {
        try {
            val popupField = javaClass.getDeclaredField("mPopup")
            popupField.isAccessible = true
            val popupHelper = popupField.get(this)
            popupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(popupHelper, true)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        tabMediator?.detach()
        super.onDestroy()
    }
}
