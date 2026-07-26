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
import com.lizongying.mytv0.databinding.ProgramItemBinding


class ProgramAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var epgList: List<EPG>,
    private var index: Int,
    private val supportsCatchup: Boolean = false,
) :
    RecyclerView.Adapter<ProgramAdapter.ViewHolder>() {

    private var listener: ItemListener? = null
    private var focused: View? = null
    val application = context.applicationContext as MyTVApplication

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ProgramItemBinding.inflate(inflater, parent, false)

        val textSize = application.px2PxFont(binding.title.textSize)
        binding.title.textSize = textSize
        binding.description.textSize = textSize

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val epg = epgList[position]
        val view = viewHolder.itemView

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            listener?.onItemFocusChange(epg, hasFocus)
            val isCurrent = position == index
            if (hasFocus) {
                viewHolder.focus(true, isCurrent)
                focused = view
            } else {
                viewHolder.focus(false, isCurrent)
            }
        }

        view.setOnClickListener {
            listener?.onItemClicked(epg)
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_UP) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return@setOnKeyListener true
                }
            }
            if (event?.action == KeyEvent.ACTION_DOWN) {
                // 确认键：回看该节目
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    listener?.onItemClicked(epg)
                    return@setOnKeyListener true
                }
                // If it is already the first item and you continue to move up...
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    val p = getItemCount() - 1

                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        p,
                        0
                    )

                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }


                // If it is the last item and you continue to move down...
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == getItemCount() - 1) {
                    val p = 0

                    (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                        p,
                        0
                    )

                    recyclerView.postDelayed({
                        val v = recyclerView.findViewHolderForAdapterPosition(p)
                        v?.itemView?.isSelected = true
                        v?.itemView?.requestFocus()
                    }, 0)
                }

                return@setOnKeyListener listener?.onKey(keyCode) == true
            }
            false
        }

        viewHolder.bindTitle(epg)
        viewHolder.bindBadge(epg, supportsCatchup)
    }

    override fun getItemCount() = epgList.size

    class ViewHolder(private val context: Context, private val binding: ProgramItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindTitle(epg: EPG) {
            binding.title.text = "${Utils.getDateFormat("HH:mm", epg.beginTime)}-${
                Utils.getDateFormat(
                    "HH:mm",
                    epg.endTime
                )
            }"
            binding.description.text = epg.title
        }

        /** 徽标：直播中 / 可回看 / 无 */
        fun bindBadge(epg: EPG, supportsCatchup: Boolean) {
            val now = Utils.getDateTimestamp()
            when {
                epg.beginTime <= now && epg.endTime > now -> {
                    binding.badge.text = context.getString(R.string.badge_live)
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_live)
                    binding.badge.setTextColor(
                        ContextCompat.getColor(context, R.color.badge_live)
                    )
                    binding.badge.visibility = android.view.View.VISIBLE
                }

                epg.endTime <= now && supportsCatchup -> {
                    binding.badge.text = context.getString(R.string.badge_catchup)
                    binding.badge.setBackgroundResource(R.drawable.bg_badge_catchup)
                    binding.badge.setTextColor(
                        ContextCompat.getColor(context, R.color.badge_catchup)
                    )
                    binding.badge.visibility = android.view.View.VISIBLE
                }

                else -> binding.badge.visibility = android.view.View.GONE
            }
        }

        fun focus(hasFocus: Boolean, isCurrent: Boolean) {
            com.lizongying.mytv0.view.FocusFx.apply(binding.root, hasFocus)
            if (hasFocus) {
                binding.title.setTextColor(
                    ContextCompat.getColor(context, R.color.text_secondary)
                )
                binding.description.setTextColor(
                    ContextCompat.getColor(context, R.color.text_primary)
                )
            } else {
                if (isCurrent) {
                    val color = ContextCompat.getColor(context, R.color.text_primary)
                    binding.title.setTextColor(
                        ContextCompat.getColor(context, R.color.text_tertiary)
                    )
                    binding.description.setTextColor(color)
                } else {
                    binding.title.setTextColor(
                        ContextCompat.getColor(context, R.color.text_dim)
                    )
                    binding.description.setTextColor(
                        ContextCompat.getColor(context, R.color.text_tertiary)
                    )
                }
            }
        }
    }

    fun scrollToPositionAndSelect(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            recyclerView.postDelayed({
                it.scrollToPositionWithOffset(position, 0)

                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                viewHolder?.itemView?.apply {
                    isSelected = true
                    requestFocus()
                }
            }, 0)
        }
    }

    interface ItemListener {
        fun onItemFocusChange(epg: EPG, hasFocus: Boolean)
        fun onItemClicked(epg: EPG)
        fun onKey(keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "ProgramAdapter"
    }
}

