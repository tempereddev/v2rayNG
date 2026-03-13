package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.FragmentGroupServerBinding
import com.v2ray.ang.databinding.ItemQrcodeBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.SimpleItemTouchHelperCallback
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>(), SwipeRefreshLayout.OnRefreshListener {
    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity

    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"

        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGroupServerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MainRecyclerAdapter(mainViewModel, ActivityAdapterListener())
        setupRecyclerView()
        binding.refreshLayout.setOnRefreshListener(this)

        mainViewModel.updateListAction.observe(viewLifecycleOwner) { index ->
            if (mainViewModel.subscriptionId == subId) {
                adapter.setData(mainViewModel.serversCache, index)
            }
        }
    }

    private fun setupRecyclerView() {
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = GridLayoutManager(
            requireContext(),
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) 2 else 1
        )
        addCustomDividerToRecyclerView(binding.recyclerView, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowSwipe = false))
        itemTouchHelper?.attachToRecyclerView(binding.recyclerView)
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.subscriptionIdChanged(subId)
    }

    private fun showServerMenu(anchor: View, guid: String, profile: ProfileItem, position: Int) {
        val popup = PopupMenu(ContextThemeWrapper(ownerActivity, R.style.CompactPopupMenuTheme), anchor)
        popup.menuInflater.inflate(R.menu.menu_server_item, popup.menu)
        popup.forceShowIconsCompat()

        if (profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP) {
            popup.menu.findItem(R.id.action_qrcode)?.isVisible = false
            popup.menu.findItem(R.id.action_clipboard)?.isVisible = false
            popup.menu.findItem(R.id.action_full_export)?.isVisible = false
            popup.menu.findItem(R.id.action_share)?.isVisible = false
        }

        popup.setOnMenuItemClickListener { item ->
            try {
                when (item.itemId) {
                    R.id.action_qrcode -> showQRCode(guid)
                    R.id.action_clipboard -> shareToClipboard(guid)
                    R.id.action_full_export -> shareFullContent(guid)
                    R.id.action_share -> shareViaIntent(guid, profile)
                    R.id.action_edit -> editServer(guid, profile)
                    R.id.action_delete -> removeServer(guid, position)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error in server menu", e)
            }
            true
        }
        popup.show()
    }

    private fun showQRCode(guid: String) {
        val qrBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
        qrBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        qrBinding.ivQcode.contentDescription = "QR Code"
        AlertDialog.Builder(ownerActivity)
            .setView(qrBinding.root)
            .show()
    }

    private fun shareToClipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) == 0) {
            ownerActivity.toastSuccess(R.string.toast_success)
        } else {
            ownerActivity.toastError(R.string.toast_failure)
        }
    }

    private fun shareFullContent(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(ownerActivity, guid)
            launch(Dispatchers.Main) {
                if (result == 0) {
                    ownerActivity.toastSuccess(R.string.toast_success)
                } else {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    private fun shareViaIntent(guid: String, profile: ProfileItem) {
        val shareUrl = AngConfigManager.getShareUrl(guid)
        if (shareUrl.isBlank()) {
            ownerActivity.toastError(R.string.toast_failure)
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareUrl)
            putExtra(Intent.EXTRA_SUBJECT, profile.remarks.orEmpty())
        }
        startActivity(Intent.createChooser(intent, null))
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val intent = Intent()
            .putExtra("guid", guid)
            .putExtra("isRunning", mainViewModel.isRunning.value)
            .putExtra("createConfigType", profile.configType.value)
            .putExtra("subscriptionId", subId)

        when (profile.configType) {
            EConfigType.CUSTOM -> ownerActivity.startActivity(intent.setClass(ownerActivity, ServerCustomConfigActivity::class.java))
            EConfigType.POLICYGROUP -> ownerActivity.startActivity(intent.setClass(ownerActivity, ServerGroupActivity::class.java))
            else -> ownerActivity.startActivity(intent.setClass(ownerActivity, ServerActivity::class.java))
        }
    }

    private fun removeServer(guid: String, position: Int) {
        if (guid == MmkvManager.getSelectServer()) {
            ownerActivity.toast(R.string.toast_action_not_allowed)
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity)
                .setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ -> removeServerSub(guid, position) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            removeServerSub(guid, position)
        }
    }

    private fun removeServerSub(guid: String, position: Int) {
        mainViewModel.removeServer(guid)
        adapter.removeServerSub(guid, position)
    }

    private fun setSelectServer(guid: String) {
        val currentSelection = MmkvManager.getSelectServer()
        if (guid != currentSelection) {
            MmkvManager.setSelectServer(guid)
            adapter.setSelectServer(mainViewModel.getPosition(currentSelection.orEmpty()), mainViewModel.getPosition(guid))

            if (mainViewModel.isRunning.value == true) {
                ownerActivity.restartV2Ray()
            }
        }
    }

    override fun onRefresh() {
        ownerActivity.refreshSubscriptionsFromHome()
        binding.refreshLayout.isRefreshing = false
    }

    fun scrollToSelectedServer() {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            ownerActivity.toast(R.string.title_file_chooser)
            return
        }

        val position = mainViewModel.serversCache.indexOfFirst { it.guid == selectedGuid }
        val recyclerView = binding.recyclerView
        if (position >= 0) {
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager
            if (layoutManager != null) {
                recyclerView.post {
                    layoutManager.scrollToPositionWithOffset(position, recyclerView.height / 3)
                }
            } else {
                recyclerView.smoothScrollToPosition(position)
            }
        } else {
            ownerActivity.toast(R.string.toast_server_not_found_in_group)
        }
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

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
        }

        override fun onRemove(guid: String, position: Int) {
            removeServer(guid, position)
        }

        override fun onEdit(guid: String, position: Int, profile: ProfileItem) {
            editServer(guid, profile)
        }

        override fun onSelectServer(guid: String) {
            setSelectServer(guid)
        }

        override fun onShare(guid: String, profile: ProfileItem, position: Int, anchor: View) {
            showServerMenu(anchor, guid, profile, position)
        }
    }
}
