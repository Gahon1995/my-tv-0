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
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lizongying.mytv0.databinding.ListItemBinding
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.view.ChannelArtFactory
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer


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

    // —— 增量事件观察（收藏增删/单行变化局部刷新）——
    private var observedModel: TVListModel? = null
    private var lifecycleOwner: LifecycleOwner? = null

    private val addedObserver = Observer<Pair<Int, Int>> { pair ->
        pair?.let { (index, _) ->
            notifyInsertedAt(index)
            syncBoundListFromCurrent()
        }
    }
    private val removedObserver = Observer<Pair<Int, Int>> { pair ->
        pair?.let { (index, _) ->
            notifyRemovedAt(index)
            syncBoundListFromCurrent()
        }
    }
    private val changedObserver = Observer<Pair<Int, Int>> { pair ->
        pair?.let { (index, _) ->
            notifyChangedAt(index)
            syncBoundListFromCurrent()
        }
    }

    /** 让 DiffUtil 用于对比的快照与模型当前数据保持同步，避免增量后重复移除。 */
    private fun syncBoundListFromCurrent() {
        listTVModel?.let { boundList = it.tvListSnapshot }
    }

    /** 绑定 LifecycleOwner 后即开始观察当前数据模型的增量事件（收藏增删/单行变化）。 */
    fun attach(owner: LifecycleOwner) {
        lifecycleOwner = owner
        observeCurrentModel()
    }

    private fun observeCurrentModel() {
        val owner = lifecycleOwner ?: return
        val current = listTVModel ?: return
        if (observedModel === current) return
        detachObservers()
        observedModel = current
        current.added.observe(owner, addedObserver)
        current.removed.observe(owner, removedObserver)
        current.changed.observe(owner, changedObserver)
    }

    private fun detachObservers() {
        val owner = lifecycleOwner ?: return
        observedModel?.let { model ->
            model.added.removeObserver(addedObserver)
            model.removed.removeObserver(removedObserver)
            model.changed.removeObserver(changedObserver)
        }
        observedModel = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(context)
        val binding = ListItemBinding.inflate(inflater, parent, false)
        val viewHolder = ViewHolder(context, binding)

        binding.icon.layoutParams.width = application.px2Px(binding.icon.layoutParams.width)
        binding.icon.layoutParams.height = application.px2Px(binding.icon.layoutParams.height)

        binding.title.textSize = application.px2PxFont(binding.title.textSize)

        binding.heart.layoutParams.width = application.px2Px(binding.heart.layoutParams.width)
        binding.heart.layoutParams.height = application.px2Px(binding.heart.layoutParams.height)

        val view = binding.root
        view.isFocusable = true
        view.isFocusableInTouchMode = true

        // 监听器只在视图创建时注册一次，避免 onBind 反复新建对象（P0-2）
        binding.heart.setOnClickListener {
            val pos = viewHolder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val model = listTVModel?.getTVModel(pos)
                model?.let { tvModel ->
                    tvModel.setLike(!(tvModel.like.value as Boolean))
                    viewHolder.like(tvModel.like.value as Boolean)
                }
            }
        }

        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@OnFocusChangeListener
            val tvModel = listTVModel?.getTVModel(pos) ?: return@OnFocusChangeListener
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
                        val model = listTVModel?.getTVModel(pos)
                        model?.let { tvModel ->
                            tvModel.setLike(!(tvModel.like.value as Boolean))
                            viewHolder.like(tvModel.like.value as Boolean)
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

    // 缓存当前展示的列表，供 DiffUtil 与原列表做增量对比
    private var boundList: List<TVModel> = emptyList()

    /**
     * 用 DiffUtil 计算差异并做增量刷新：
     * 收藏增删、单行 like 变化只会触发 insert/remove/change，而不是整表重绘。
     * 列表切换（group 变化）时也可安全调用——DiffUtil 会退回整表刷新。
     */
    fun update(listTVModel: TVListModel) {
        this.listTVModel = listTVModel
        val newList = listTVModel.tvListSnapshot
        observeCurrentModel()
        recyclerView.post {
            submitList(newList)
        }
    }

    private fun submitList(newList: List<TVModel>) {
        val oldList = boundList
        if (oldList.isEmpty() && newList.isEmpty()) return

        val result: DiffUtil.DiffResult = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize() = oldList.size
                override fun getNewListSize() = newList.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldList[oldItemPosition].tv.id == newList[newItemPosition].tv.id
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val oldM = oldList[oldItemPosition]
                    val newM = newList[newItemPosition]
                    return oldM.tv.id == newM.tv.id
                            && oldM.like.value == newM.like.value
                            && oldM.tv.title == newM.tv.title
                            && oldM.tv.logo == newM.tv.logo
                }
            },
            // 单个小规模数据源差异用同步计算即可；大数据源也没有可观延迟
            false
        )
        result.dispatchUpdatesTo(this)
        boundList = newList.toList()
    }

    /** 单行精确刷新（用于收藏状态行内变化），由外部 TVListModel 增量事件触发。 */
    fun notifyChangedAt(position: Int) {
        if (position in 0 until itemCount) {
            notifyItemChanged(position)
        }
    }

    /** 单行插入。 */
    fun notifyInsertedAt(position: Int) {
        if (position in 0..itemCount) {
            notifyItemInserted(position)
        }
    }

    /** 单行删除。 */
    fun notifyRemovedAt(position: Int) {
        if (position in 0 until itemCount) {
            notifyItemRemoved(position)
        }
    }

    fun clear() {
        focused?.clearFocus()
        recyclerView.invalidate()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        detachObservers()
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, position: Int) {
        listTVModel?.let {
            val tvModel = it.getTVModel(position)
            val view = viewHolder.itemView

            if (!defaultFocused && position == defaultFocus) {
                view.requestFocus()
                defaultFocused = true
            }

            viewHolder.like(tvModel!!.like.value as Boolean)
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
