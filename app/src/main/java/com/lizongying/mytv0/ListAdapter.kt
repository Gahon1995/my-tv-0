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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv0.databinding.ListItemBinding
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.view.ChannelArtFactory


/**
 * 频道列表适配器。
 *
 * 注意：本类刻意保持「update() 全量 notifyDataSetChanged」的简单可靠语义。
 * 此前尝试用 DiffUtil 增量刷新（submitList + 增量 added/removed/changed 观察）会与
 * RecyclerView 布局时序冲突，触发 "Inconsistency detected" 崩溃以及分组切换后列表残留。
 * 分组切换必须整表重建，增量刷新收益远小于它带来的稳定性风险。
 */
class ListAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private var listTVModel: TVListModel?,
) :
    RecyclerView.Adapter<ListAdapter.ViewHolder>() {
    private var listener: ItemListener? = null
    private var focused: View? = null
    private var defaultFocused = false
    private var defaultFocus: Int = -1

    var visible = false

    val application = context.applicationContext as MyTVApplication

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ListItemBinding.inflate(inflater, parent, false)
        val viewHolder = ViewHolder(context, binding)

        binding.icon.layoutParams.width = application.px2Px(binding.icon.layoutParams.width)
        binding.icon.layoutParams.height = application.px2Px(binding.icon.layoutParams.height)

        binding.title.textSize = application.px2PxFont(binding.title.textSize)

        binding.heart.layoutParams.width = application.px2Px(binding.heart.layoutParams.width)
        binding.heart.layoutParams.height = application.px2Px(binding.heart.layoutParams.height)

        // 监听器只在视图创建时注册一次，避免 onBind 反复新建对象
        val view = binding.root
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        binding.heart.setOnClickListener {
            val pos = viewHolder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val tvModel = listTVModel?.getTVModel(pos)
                tvModel?.let {
                    it.setLike(!(it.like.value as Boolean))
                    viewHolder.like(it.like.value as Boolean)
                }
            }
        }

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@OnFocusChangeListener
            val tvModel = listTVModel?.getTVModel(pos)
            if (tvModel == null) return@OnFocusChangeListener
            listener?.onItemFocusChange(tvModel, hasFocus)

            if (hasFocus) {
                viewHolder.focus(true)
                focused = v
                if (visible) {
                    if (pos != listTVModel?.positionValue) {
                        listTVModel?.setPosition(pos)
                    }
                } else {
                    visible = true
                }
            } else {
                viewHolder.focus(false)
            }
        }

        view.setOnClickListener {
            val pos = viewHolder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                listener?.onItemClicked(pos)
            }
        }

        view.setOnTouchListener { _, event ->
            if (event?.action == MotionEvent.ACTION_UP) {
                view.performClick()
                true
            } else {
                false
            }
        }

        view.setOnKeyListener { _, keyCode, event: KeyEvent? ->
            if (event?.action == KeyEvent.ACTION_DOWN) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && pos == 0) {
                        wrapFocusTo(getItemCount() - 1)
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && pos == getItemCount() - 1) {
                        wrapFocusTo(0)
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        val tvModel = listTVModel?.getTVModel(pos)
                        tvModel?.let {
                            it.setLike(!(it.like.value as Boolean))
                            viewHolder.like(it.like.value as Boolean)
                        }
                        return@setOnKeyListener true
                    }
                }
                return@setOnKeyListener listener?.onKey(this, keyCode) == true
            }
            false
        }

        return viewHolder
    }

    private fun wrapFocusTo(p: Int) {
        (recyclerView.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            p,
            0
        )
        recyclerView.postDelayed({
            val v = recyclerView.findViewHolderForAdapterPosition(p)?.itemView
            v?.isSelected = true
            v?.requestFocus()
        }, 0)
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
        recyclerView.post {
            notifyDataSetChanged()
        }
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        listTVModel?.let {
            val tvModel = it.getTVModel(position)!!
            val view = viewHolder.itemView

            if (!defaultFocused && position == defaultFocus) {
                view.requestFocus()
                defaultFocused = true
            }

            viewHolder.like(tvModel.like.value as Boolean)
            viewHolder.bindTitle(tvModel.tv.title)
            viewHolder.bindImage(tvModel)
        }
    }

    override fun getItemCount() = listTVModel?.size() ?: 0

    class ViewHolder(private val context: Context, val binding: ListItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val application = context.applicationContext as MyTVApplication
        val imageHelper = application.imageHelper


        fun bindTitle(text: String) {
            binding.title.text = text
        }

        fun bindImage(tvModel: TVModel) {
            val tv = tvModel.tv
            val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
            val bitmap = ChannelArtFactory.channelBitmap(context, channelNum)

            val name = if (tv.name.isNotEmpty()) { tv.name } else { tv.title }
            imageHelper.loadImage(name, binding.icon, bitmap, tv.logo)
        }

        fun focus(hasFocus: Boolean) {
            if (hasFocus) {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.focus))
                binding.root.setBackgroundResource(R.drawable.bg_item_focused)
                binding.icon.backgroundTintList = ContextCompat.getColorStateList(context, R.color.focus)
            } else {
                binding.title.setTextColor(ContextCompat.getColor(context, R.color.description_blur))
                binding.root.setBackgroundResource(android.R.color.transparent)
                binding.icon.backgroundTintList = ContextCompat.getColorStateList(context, android.R.color.transparent)
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
                binding.heart.setColorFilter(ContextCompat.getColor(context, R.color.heart_red))
            } else {
                binding.heart.setImageDrawable(
                    ContextCompat.getDrawable(
                        context,
                        R.drawable.baseline_favorite_border_24
                    )
                )
                binding.heart.setColorFilter(ContextCompat.getColor(context, R.color.description_blur))
            }
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

    interface ItemListener {
        fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean)
        fun onItemClicked(position: Int, type: String = "list")
        fun onKey(listAdapter: ListAdapter, keyCode: Int): Boolean
    }

    fun setItemListener(listener: ItemListener) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "ListAdapter"
    }
}
