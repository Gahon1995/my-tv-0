package com.lizongying.mytv0

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.MenuEpgItemBinding
import com.lizongying.mytv0.view.FocusFx

/**
 * 频道菜单第三列：当前选中频道的节目单。
 * OK 播放（过去节目→回看，当前→直播），左键返回频道列表。
 */
class MenuEpgAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var epgList: List<EPG> = emptyList(),
    private var supportsCatchup: Boolean = false,
) : RecyclerView.Adapter<MenuEpgAdapter.ViewHolder>() {

    interface ItemListener {
        fun onEpgClicked(epg: EPG)
        fun onEpgKeyLeft(): Boolean
        fun onEpgActive()
    }

    private var listener: ItemListener? = null

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    val application = context.applicationContext as MyTVApplication

    fun update(epgList: List<EPG>, supportsCatchup: Boolean) {
        this.epgList = epgList
        this.supportsCatchup = supportsCatchup
        recyclerView.post { notifyDataSetChanged() }
    }

    /** 定位并聚焦当前直播中的节目 */
    fun focusCurrent() {
        val now = Utils.getDateTimestamp()
        var index = epgList.indexOfFirst { it.beginTime <= now && it.endTime > now }
        if (index < 0) {
            index = epgList.indexOfFirst { it.endTime > now }
        }
        if (index < 0) {
            index = 0
        }
        if (epgList.isEmpty()) return
        (recyclerView.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, recyclerView.height / 3)
        val p = index
        recyclerView.postDelayed({
            val vh = recyclerView.findViewHolderForAdapterPosition(p)
            vh?.itemView?.requestFocus()
        }, 60)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = MenuEpgItemBinding.inflate(inflater, parent, false)
        binding.time.textSize = application.px2PxFontElder(binding.time.textSize)
        binding.title.textSize = application.px2PxFontElder(binding.title.textSize)
        binding.badge.textSize = application.px2PxFont(binding.badge.textSize)
        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val epg = epgList[position]
        val view = viewHolder.itemView

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            FocusFx.apply(v, hasFocus)
            viewHolder.focus(hasFocus)
            if (hasFocus) {
                listener?.onEpgActive()
            }
        }

        view.setOnClickListener {
            listener?.onEpgClicked(epg)
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        listener?.onEpgClicked(epg)
                        return@setOnKeyListener true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        return@setOnKeyListener listener?.onEpgKeyLeft() == true
                    }

                    // 列表内不循环，到顶/底不动
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        listener?.onEpgActive()
                        return@setOnKeyListener position == 0
                    }

                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        listener?.onEpgActive()
                        return@setOnKeyListener position == itemCount - 1
                    }
                }
            }
            false
        }

        viewHolder.bind(epg, supportsCatchup)
    }

    override fun getItemCount() = epgList.size

    class ViewHolder(private val context: Context, val binding: MenuEpgItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(epg: EPG, supportsCatchup: Boolean) {
            binding.time.text = Utils.getDateFormat("HH:mm", epg.beginTime)
            binding.title.text = epg.title

            val now = Utils.getDateTimestamp()
            when {
                epg.beginTime <= now && epg.endTime > now -> {
                    binding.badge.text = context.getString(R.string.badge_live)
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_live)
                    binding.badge.setTextColor(
                        ContextCompat.getColor(context, R.color.badge_live)
                    )
                    binding.badge.visibility = View.VISIBLE
                }

                epg.endTime <= now && supportsCatchup -> {
                    binding.badge.text = context.getString(R.string.badge_catchup)
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_catchup)
                    binding.badge.setTextColor(
                        ContextCompat.getColor(context, R.color.badge_catchup)
                    )
                    binding.badge.visibility = View.VISIBLE
                }

                else -> binding.badge.visibility = View.GONE
            }
        }

        fun focus(hasFocus: Boolean) {
            binding.title.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (hasFocus) R.color.text_primary else R.color.text_secondary
                )
            )
        }
    }

    companion object {
        private const val TAG = "MenuEpgAdapter"
    }
}
