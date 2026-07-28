package com.lizongying.mytv0

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter as RVListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv0.databinding.GroupItemBinding
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.view.FocusFx


class GroupAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var tvGroupModel: TVGroupModel,
) : RVListAdapter<TVListModel, GroupAdapter.ViewHolder>(DiffCallback) {

    private var listener: ItemListener? = null
    private var focused: View? = null
    private var defaultFocused = false
    private var defaultFocus: Int = -1

    var visible = false

    private var first = true

    val application = context.applicationContext as MyTVApplication

    fun submitNewList(models: List<TVListModel>) {
        submitList(models.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = GroupItemBinding.inflate(inflater, parent, false)

        val layoutParams = binding.title.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.marginStart = application.px2Px(binding.title.marginStart)
        layoutParams.bottomMargin = application.px2Px(binding.title.marginBottom)
        binding.title.layoutParams = layoutParams

        binding.title.textSize = application.px2PxFontElder(binding.title.textSize)

        binding.root.isFocusable = true
        binding.root.isFocusableInTouchMode = true
        return ViewHolder(context, binding)
    }

    fun focusable(able: Boolean) {
        recyclerView.isFocusable = able
        recyclerView.isFocusableInTouchMode = able
        if (able) {
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        } else {
            recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val listTVModel = getItem(position)
        val view = viewHolder.itemView

        if (!defaultFocused && position == defaultFocus) {
            view.requestFocus()
            defaultFocused = true
        }

        val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            listener?.onItemFocusChange(listTVModel, hasFocus)

            if (hasFocus) {
                viewHolder.focus(true, isCurrent(position))
                focused = view

                val p = listTVModel.getGroupIndex()
                if (p != tvGroupModel.positionValue) {
                    tvGroupModel.setPosition(p)
                }
            } else {
                viewHolder.focus(false, isCurrent(position))
            }
        }

        view.onFocusChangeListener = onFocusChangeListener

        view.setOnClickListener { _ ->
            listener?.onItemClicked(position)
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_UP) {
                recyclerView.postDelayed({
                    val oldLikeMode = tvGroupModel.isInLikeMode
                    tvGroupModel.isInLikeMode = position == 0
                    if (tvGroupModel.isInLikeMode) {
//                        R.string.favorite_mode.showToast()
                    } else if (oldLikeMode) {
//                        R.string.standard_mode.showToast()
                    }
                }, 500)
            }
            if (event?.action == KeyEvent.ACTION_DOWN) {

                // If it is already the first item and you continue to move up...
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                    val p = itemCount - 1

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
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && position == itemCount - 1) {
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

                return@setOnKeyListener listener?.onKey(keyCode) ?: false
            }
            false
        }

        viewHolder.bindTitle(listTVModel.getName())
        viewHolder.focus(view.hasFocus(), isCurrent(position))
    }

    /** 该分组是否为当前所选分组（用于非焦点态高亮） */
    private fun isCurrent(position: Int): Boolean {
        val groupPosition = if (SP.showAllChannels || tvGroupModel.positionValue == 0) {
            tvGroupModel.positionValue
        } else {
            tvGroupModel.positionValue - 1
        }
        return position == groupPosition
    }

    class ViewHolder(private val context: Context, private val binding: GroupItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bindTitle(text: String) {
            binding.title.text = when (text) {
                "我的收藏" -> context.getString(R.string.my_favorites)
                "全部頻道" -> context.getString(R.string.all_channels)
                else -> text
            }
        }

        fun focus(hasFocus: Boolean, isCurrent: Boolean = false) {
            FocusFx.apply(binding.root, hasFocus)
            if (hasFocus) {
                // selector 已处理焦点背景
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            } else if (isCurrent) {
                binding.title.setBackgroundResource(R.drawable.bg_group_active)
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                return
            } else {
                binding.title.setTextColor(
                    ContextCompat.getColor(
                        context,
                        R.color.text_tertiary
                    )
                )
            }
            binding.title.setBackgroundResource(R.drawable.bg_item_selector)
        }
    }

    fun scrollToPositionAndSelect(position: Int) {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        layoutManager?.let {
            val delay = if (first) {
                // 首次布局尚未完成，延迟等待；之后无需延迟
                first = false
                100L
            } else {
                0L
            }

            recyclerView.postDelayed({
                val groupPosition =
                    if (SP.showAllChannels || position == 0) position else position - 1
                it.scrollToPositionWithOffset(groupPosition, 0)

                val viewHolder = recyclerView.findViewHolderForAdapterPosition(groupPosition)
                viewHolder?.itemView?.apply {
                    isSelected = true
                    requestFocus()
                }
            }, delay)
        }
    }

    interface ItemListener {
        fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean)
        fun onItemClicked(position: Int)
        fun onKey(keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    fun changed() {
        submitNewList(tvGroupModel.tvGroup.value ?: emptyList())
    }

    object DiffCallback : DiffUtil.ItemCallback<TVListModel>() {
        override fun areItemsTheSame(oldItem: TVListModel, newItem: TVListModel): Boolean {
            return oldItem.getName() == newItem.getName()
        }

        override fun areContentsTheSame(oldItem: TVListModel, newItem: TVListModel): Boolean {
            return oldItem.getName() == newItem.getName() &&
                    oldItem.size() == newItem.size()
        }
    }

    companion object {
        private const val TAG = "GroupAdapter"
    }
}
