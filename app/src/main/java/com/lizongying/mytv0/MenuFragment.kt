package com.lizongying.mytv0

import MainViewModel
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.MenuBinding
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.view.FocusFx

class MenuFragment : Fragment(), GroupAdapter.ItemListener, ListAdapter.ItemListener,
    MenuEpgAdapter.ItemListener {
    private var _binding: MenuBinding? = null
    private val binding get() = _binding!!

    private lateinit var groupAdapter: GroupAdapter
    private lateinit var listAdapter: ListAdapter
    private lateinit var menuEpgAdapter: MenuEpgAdapter

    // 节目单列当前展示的频道
    private var epgTVModel: TVModel? = null

    private var groupWidth = 0
    private var listWidth = 0

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = MenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        val application = context.applicationContext as MyTVApplication
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        Log.i(TAG, "group size ${viewModel.groupModel.size()}")
        groupAdapter = GroupAdapter(
            context,
            binding.group,
            viewModel.groupModel,
        )
        binding.group.adapter = groupAdapter
        binding.group.layoutManager =
            LinearLayoutManager(context)
        groupWidth = application.px2Px(binding.group.layoutParams.width)
        binding.group.layoutParams.width = if (SP.compactMenu) {
            groupWidth * 2 / 3
        } else {
            groupWidth
        }
        groupAdapter.setItemListener(this)

        listAdapter = ListAdapter(
            context,
            binding.list,
            getList(),
        )
        binding.list.adapter = listAdapter
        binding.list.layoutManager =
            LinearLayoutManager(context)
        listWidth = application.px2Px(binding.list.layoutParams.width)
        binding.list.layoutParams.width = if (SP.compactMenu) {
            listWidth * 4 / 5
        } else {
            listWidth
        }
        listAdapter.setItemListener(this)

        menuEpgAdapter = MenuEpgAdapter(context, binding.epgList)
        binding.epgList.adapter = menuEpgAdapter
        binding.epgList.layoutManager = LinearLayoutManager(context)
        menuEpgAdapter.setItemListener(this)

        binding.menu.setOnClickListener {
            hideSelf()
        }

//        groupAdapter.focusable(false)

        groupAdapter.focusable(true)
        listAdapter.focusable(true)

        onVisible()
    }

    private fun getList(): TVListModel? {
        if (!this::viewModel.isInitialized) {
            Log.e(TAG, "viewModel is not initialized")
            return null
        }

        // 如果不存在當前組，則切換到收藏組
        if (viewModel.groupModel.getCurrentList() == null) {
            viewModel.groupModel.setPosition(0)
        }

        return viewModel.groupModel.getCurrentList()
    }

    fun update() {
        view?.post {
            groupAdapter.changed()

            getList()?.let {
                (binding.list.adapter as ListAdapter).update(it)
            }
        }
    }

    fun updateSize() {
        view?.post {
            binding.group.layoutParams.width = if (SP.compactMenu) {
                groupWidth * 2 / 3
            } else {
                groupWidth
            }

            binding.list.layoutParams.width = if (SP.compactMenu) {
                listWidth * 4 / 5
            } else {
                listWidth
            }
        }
    }

    fun updateList(position: Int) {
        if (!this::viewModel.isInitialized) {
            Log.e(TAG, "viewModel is not initialized")
            return
        }

        viewModel.groupModel.setPosition(position)
        SP.positionGroup = position

        viewModel.groupModel.getCurrentList()?.let {
            (binding.list.adapter as ListAdapter).update(it)
        }
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    override fun onItemFocusChange(listTVModel: TVListModel, hasFocus: Boolean) {
        if (hasFocus) {
            (binding.list.adapter as ListAdapter).update(listTVModel)
            (activity as MainActivity).menuActive()
        }
    }

    override fun onItemFocusChange(tvModel: TVModel, hasFocus: Boolean) {
        if (hasFocus) {
            (activity as MainActivity).menuActive()
        }
    }

    override fun onItemClicked(position: Int) {
        if (!this::viewModel.isInitialized) {
            Log.e(TAG, "viewModel is not initialized")
            return
        }
    }

    override fun onItemClicked(position: Int, type: String) {
        if (!this::viewModel.isInitialized) {
            Log.e(TAG, "viewModel is not initialized")
            return
        }

        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.let {
            it.setPosition(position)
            it.setPositionPlaying()
            it.getCurrent()?.setReady()
        }

        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    // ===== 节目单列（右键展开） =====

    override fun onShowEpg(tvModel: TVModel): Boolean {
        val epg = tvModel.epgValue
        if (epg.isEmpty()) {
            R.string.epg_empty.showToast()
            return true
        }
        epgTVModel = tvModel
        binding.epgColTitle.text = tvModel.tv.title
        binding.epgColSubtitle.text = if (tvModel.supportsCatchup()) {
            R.string.program_subtitle_catchup.getString()
        } else {
            R.string.program_subtitle.getString()
        }
        menuEpgAdapter.update(epg, tvModel.supportsCatchup())
        binding.epgCol.visibility = VISIBLE
        binding.epgList.post {
            menuEpgAdapter.focusCurrent()
        }
        (activity as MainActivity).menuActive()
        return true
    }

    private fun hideEpgCol() {
        binding.epgCol.visibility = GONE
        epgTVModel = null
    }

    override fun onEpgClicked(epg: EPG) {
        val tvModel = epgTVModel ?: return
        val now = Utils.getDateTimestamp()

        if (epg.beginTime >= now) {
            R.string.catchup_future_program.showToast()
            return
        }

        // 切到该频道
        viewModel.groupModel.setPositionPlaying()
        viewModel.groupModel.getCurrentList()?.let { list ->
            val index = list.tvList.value?.indexOfFirst { it.tv.id == tvModel.tv.id } ?: -1
            if (index >= 0) {
                list.setPosition(index)
                list.setPositionPlaying()
            }
        }

        val isLive = epg.beginTime <= now && epg.endTime > now
        if (isLive || !tvModel.supportsCatchup()) {
            // 直播中或不支持回看 → 播直播
            tvModel.setReady()
        } else {
            val end = if (epg.endTime.toLong() > now) now else epg.endTime.toLong()
            tvModel.playCatchup(epg.beginTime.toLong(), end, epg.title)
        }

        hideEpgCol()
        hideSelf()
    }

    override fun onEpgKeyLeft(): Boolean {
        // 左键回频道列表
        hideEpgCol()
        viewModel.groupModel.getCurrentList()?.let {
            listAdapter.toPosition(it.positionValue)
        }
        (activity as MainActivity).menuActive()
        return true
    }

    override fun onEpgActive() {
        (activity as MainActivity).menuActive()
    }

    override fun onKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (listAdapter.itemCount == 0) {
                    R.string.channel_not_exist.showToast()
                    return true
                }

//                binding.group.visibility = GONE
//                groupAdapter.focusable(false)
//                listAdapter.focusable(true)

                // 如果当前分组就是正在播放的分组，回到上次播放的位置；否则定位到第二项（跳过收藏），至少不是顶部
                val target = if (viewModel.groupModel.positionPlayingValue == viewModel.groupModel.positionValue) {
                    viewModel.groupModel.getCurrentList()?.let { it.positionPlayingValue } ?: 0
                } else {
                    viewModel.groupModel.getCurrentList()?.let { list ->
                        if (list.size() > 2) 2 else 0
                    } ?: 0
                }
                listAdapter.toPosition(target)

                return true
            }
        }
        return false
    }

    override fun onKey(listAdapter: ListAdapter, keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
//                binding.group.visibility = VISIBLE
//                groupAdapter.focusable(true)
//                listAdapter.focusable(false)
                listAdapter.clear()
                groupAdapter.scrollToPositionAndSelect(viewModel.groupModel.positionValue)
                return true
            }
        }
        return false
    }

    fun onVisible() {
        if (viewModel.groupModel.tvGroupValue.size < 2 || viewModel.groupModel.getAllList()
                ?.size() == 0
        ) {
            R.string.channel_not_exist.showToast()
            return
        }

        _binding?.let { FocusFx.panelIn(it.menuPanel) }

        val position = viewModel.groupModel.positionPlayingValue
        if (position != viewModel.groupModel.positionValue
        ) {
            updateList(position)
        }
        viewModel.groupModel.getCurrentList()?.let {
            listAdapter.toPosition(it.positionPlayingValue)
        }

        (activity as MainActivity).menuActive()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
        } else {
            view?.post {
                hideEpgCol()
                groupAdapter.visible = false
                listAdapter.visible = false
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "MenuFragment"
    }
}