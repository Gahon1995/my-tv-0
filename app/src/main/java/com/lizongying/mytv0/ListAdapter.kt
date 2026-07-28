package com.lizongying.mytv0

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
import android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter as RVListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv0.databinding.ListItemBinding
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.view.FocusFx


/**
 * 频道列表适配器。
 *
 * 使用 [RVListAdapter] + [DiffUtil] 增量更新，替代旧版手动 notifyDataSetChanged，
 * 大幅减少滚动时的 ViewHolder rebind。
 */
class ListAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var listTVModel: TVListModel?,
) : RVListAdapter<TVModel, ListAdapter.ViewHolder>(DiffCallback) {

    private var listener: ItemListener? = null
    private var focused: View? = null
    private var defaultFocused = false
    private var defaultFocus: Int = -1

    var visible = false

    val application = context.applicationContext as MyTVApplication

    /** 供外部 diff 更新使用 */
    fun submitNewList(models: List<TVModel>) {
        submitList(models.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ListItemBinding.inflate(inflater, parent, false)

        binding.icon.layoutParams.width = application.px2PxElder(binding.icon.layoutParams.width)
        binding.icon.layoutParams.height = application.px2PxElder(binding.icon.layoutParams.height)
        binding.icon.setPadding(application.px2Px(binding.icon.paddingTop))

        binding.title.textSize = application.px2PxFontElder(binding.title.textSize)
        binding.epgNow.textSize = application.px2PxFontElder(binding.epgNow.textSize)
        binding.num.textSize = application.px2PxFontElder(binding.num.textSize)

        binding.heart.layoutParams.width = application.px2PxElder(binding.heart.layoutParams.width)
        binding.heart.layoutParams.height = application.px2PxElder(binding.heart.layoutParams.height)
        binding.heart.setPadding(application.px2Px(binding.heart.paddingTop))

        return ViewHolder(context, binding)
    }

    fun focusable(able: Boolean) {
        recyclerView.isFocusable = able
        recyclerView.isFocusableInTouchMode = able
        if (able) {
            recyclerView.descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        } else {
            recyclerView.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        }
    }

    fun update(listTVModel: TVListModel) {
        this.listTVModel = listTVModel
        submitNewList(listTVModel.tvList.value ?: emptyList())
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        val tvModel = getItem(position)
        val view = viewHolder.itemView

        view.isFocusable = true
        view.isFocusableInTouchMode = true

        viewHolder.bind(tvModel)

        if (!defaultFocused && position == defaultFocus) {
            view.requestFocus()
            defaultFocused = true
        }

        val onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            listener?.onItemFocusChange(tvModel, hasFocus)

            if (hasFocus) {
                viewHolder.focus(true)
                focused = view
                if (visible) {
                    if (position != listTVModel?.positionValue) {
                        listTVModel?.setPosition(position)
                    }
                } else {
                    visible = true
                }
            } else {
                viewHolder.focus(false)
            }
        }

        view.onFocusChangeListener = onFocusChangeListener

        view.setOnClickListener { _ ->
            listener?.onItemClicked(position)
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(
                v: View?,
                event: MotionEvent?
            ): Boolean {
                v ?: return false
                event ?: return false

                when (event.action) {
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        return true
                    }
                }

                return false
            }
        })

        // 监听器在 onBindViewHolder 设置是合理的——RecyclerView 复用时 ViewHolder 不变但数据变了，
        // 必须重新绑定回调引用的数据（tvModel）。Lambda 捕获的 tvModel 是每次 bind 的当前项。
        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
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

                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 右键：展开该频道节目单（收藏改为长按 OK）
                    return@setOnKeyListener listener?.onShowEpg(tvModel) == true
                }

                return@setOnKeyListener listener?.onKey(this, keyCode) == true
            }
            false
        }

        // 长按 OK：收藏/取消收藏
        view.setOnLongClickListener {
            tvModel.setLike(!(tvModel.like.value as Boolean))
            viewHolder.like(tvModel.like.value as Boolean)
            true
        }
    }

    fun toPosition(position: Int) {
        Log.i(TAG, "position $position")
        recyclerView.post {
            (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                position,
                0
            )

            recyclerView.postDelayed({
                val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
                viewHolder?.itemView?.isSelected = true
                viewHolder?.itemView?.requestFocus()
            }, 0)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        // 回收时取消 Glide 加载，避免 ViewHolder 复用时旧请求写入新 item
        com.bumptech.glide.Glide.with(context).clear(holder.binding.icon)
    }

    interface ItemListener {
        fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean)
        fun onItemClicked(position: Int, type: String = "list")
        fun onKey(listAdapter: ListAdapter, keyCode: Int): Boolean
        fun onShowEpg(tvModel: TVModel): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    class ViewHolder(private val context: Context, val binding: ListItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val application = context.applicationContext as MyTVApplication
        val imageHelper = application.imageHelper

        /** 绑定数据——尽量轻量，不做重复 Glide 解码 */
        fun bind(tvModel: TVModel) {
            val tv = tvModel.tv

            binding.title.text = tv.title

            // 频道号
            val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
            binding.num.text = channelNum.toString()

            // EPG 当前节目（TVModel 层已缓存结果，无需每次遍历）
            val nowTitle = tvModel.epgNowTitle
            if (nowTitle != null) {
                binding.epgNow.text = nowTitle
                binding.epgNow.visibility = View.VISIBLE
            } else {
                binding.epgNow.visibility = View.GONE
            }

            // 收藏状态
            val liked = tvModel.like.value as? Boolean ?: false
            like(liked)

            // 台标（用 PlaceholderLogo + key 避免重复 Glide 解码）
            bindImage(tvModel)

            // 焦点态重置（由 onBindViewHolder 里的 onFocusChangeListener 管理）
            binding.title.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            binding.epgNow.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
        }

        private fun bindImage(tvModel: TVModel) {
            val tv = tvModel.tv

            val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
            val bitmap = PlaceholderLogo.get(context, channelNum)

            val name = if (tv.name.isNotEmpty()) { tv.name } else { tv.title }
            imageHelper.loadImage(name, binding.icon, bitmap, tv.logo)
        }

        fun focus(hasFocus: Boolean) {
            FocusFx.apply(binding.root, hasFocus)
            if (hasFocus) {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                binding.epgNow.setTextColor(
                    ContextCompat.getColor(context, R.color.text_secondary)
                )
            } else {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                binding.epgNow.setTextColor(ContextCompat.getColor(context, R.color.text_dim))
            }
        }

        fun like(liked: Boolean) {
            if (liked) {
                binding.heart.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        R.drawable.baseline_favorite_24
                    )
                )
            } else {
                binding.heart.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        R.drawable.baseline_favorite_border_24
                    )
                )
            }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<TVModel>() {
        override fun areItemsTheSame(oldItem: TVModel, newItem: TVModel): Boolean {
            return oldItem.tv.id == newItem.tv.id
        }

        override fun areContentsTheSame(oldItem: TVModel, newItem: TVModel): Boolean {
            // 只比较会改变 UI 的字段，跳过 uris/headers/child 等大字段
            return oldItem.tv.title == newItem.tv.title &&
                    oldItem.tv.name == newItem.tv.name &&
                    oldItem.tv.number == newItem.tv.number &&
                    oldItem.tv.logo == newItem.tv.logo &&
                    oldItem.like.value == newItem.like.value &&
                    oldItem.epgNowTitle == newItem.epgNowTitle &&
                    oldItem.tv.group == newItem.tv.group
        }
    }

    companion object {
        private const val TAG = "ListAdapter"
    }
}
