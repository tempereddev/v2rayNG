package com.v2ray.ang.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.v2ray.ang.R
import com.v2ray.ang.contracts.MainAdapterListener
import com.v2ray.ang.databinding.ItemRecyclerFooterBinding
import com.v2ray.ang.databinding.ItemRecyclerMainBinding
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.extension.nullIfBlank
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.MuxFragmentBenchmarkManager
import com.v2ray.ang.helper.ItemTouchHelperAdapter
import com.v2ray.ang.helper.ItemTouchHelperViewHolder
import com.v2ray.ang.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
        private const val SELECTED_BACKGROUND = 0x08000000
    }

    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        data = newData?.toMutableList() ?: mutableListOf()

        if (position >= 0 && position in data.indices) {
            notifyItemChanged(position)
        } else {
            notifyDataSetChanged()
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder !is MainViewHolder) {
            return
        }

        val context = holder.itemMainBinding.root.context
        val item = data[position]
        val guid = item.guid
        val profile = item.profile
        val itemBinding = holder.itemMainBinding

        itemBinding.tvName.text = profile.remarks
        itemBinding.tvStatistics.text = getAddress(profile)
        itemBinding.tvType.text = profile.configType.name

        val affiliation = MmkvManager.decodeServerAffiliationInfo(guid)
        itemBinding.tvTestResult.text = affiliation?.getTestDelayString().orEmpty()
        val pingColor = if ((affiliation?.testDelayMillis ?: 0L) < 0L) {
            R.color.colorPingRed
        } else {
            R.color.colorPing
        }
        itemBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, pingColor))

        renderSelectionState(itemBinding, guid == MmkvManager.getSelectServer())
        renderCapabilityState(itemBinding, profile)
        renderSubscriptionState(itemBinding, getSubscriptionRemarks(profile))

        itemBinding.layoutMore.setOnClickListener { anchor ->
            val currentPosition = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: return@setOnClickListener
            val currentItem = data.getOrNull(currentPosition) ?: return@setOnClickListener
            adapterListener?.onShare(currentItem.guid, currentItem.profile, currentPosition, anchor)
        }
        itemBinding.infoContainer.setOnClickListener {
            adapterListener?.onSelectServer(guid)
        }
    }

    private fun renderSelectionState(binding: ItemRecyclerMainBinding, isSelected: Boolean) {
        if (isSelected) {
            binding.layoutIndicator.setBackgroundResource(R.color.colorIndicator)
            binding.tvName.setTypeface(null, Typeface.BOLD)
            binding.itemBg.setBackgroundColor(SELECTED_BACKGROUND.toInt())
        } else {
            binding.layoutIndicator.setBackgroundResource(0)
            binding.tvName.setTypeface(null, Typeface.NORMAL)
            binding.itemBg.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun renderCapabilityState(binding: ItemRecyclerMainBinding, profile: ProfileItem) {
        binding.tvCapMux.visibility = if (MuxFragmentBenchmarkManager.supportsMux(profile)) View.VISIBLE else View.GONE
        binding.tvCapFrag.visibility = if (MuxFragmentBenchmarkManager.supportsFragment(profile)) View.VISIBLE else View.GONE
    }

    private fun renderSubscriptionState(binding: ItemRecyclerMainBinding, remarks: String) {
        binding.tvSubscription.text = remarks
        binding.layoutSubscription.visibility = if (remarks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun getAddress(profile: ProfileItem): String {
        return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
    }

    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks = if (mainViewModel.subscriptionId.isEmpty()) {
            MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
        } else {
            null
        }
        return subRemarks?.toString().orEmpty()
    }

    fun removeServerSub(guid: String, position: Int) {
        val index = data.indexOfFirst { it.guid == guid }
        if (index >= 0) {
            data.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, data.size - index)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        if (fromPosition >= 0) {
            notifyItemChanged(fromPosition)
        }
        if (toPosition >= 0) {
            notifyItemChanged(toPosition)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM -> MainViewHolder(
                ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )

            else -> FooterViewHolder(
                ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) VIEW_TYPE_FOOTER else VIEW_TYPE_ITEM
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.LTGRAY)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(0)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
    }

    override fun onItemDismiss(position: Int) {
    }
}
