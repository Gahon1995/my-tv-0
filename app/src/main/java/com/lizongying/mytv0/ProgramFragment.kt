package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.ProgramBinding
import com.lizongying.mytv0.models.TVModel

class ProgramFragment : Fragment(), ProgramAdapter.ItemListener {
    private var _binding: ProgramBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000

    private lateinit var programAdapter: ProgramAdapter

    private lateinit var viewModel: MainViewModel

    // 跟踪当前 TVModel 以便管理 EPG LiveData 观察者
    private var currentTvModel: TVModel? = null

    private val epgObserver = Observer<List<EPG>> { epgList ->
        if (isHidden) return@Observer
        if (epgList != null && epgList.isNotEmpty()) {
            bindEPG(epgList)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ProgramBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireActivity()
        viewModel = ViewModelProvider(context)[MainViewModel::class.java]

        binding.program.setOnClickListener {
            hideSelf()
        }

        onVisible()
    }

    private fun hideSelf() {
        requireActivity().supportFragmentManager.beginTransaction()
            .hide(this)
            .commitAllowingStateLoss()
    }

    private val hideRunnable = Runnable {
        hideSelf()
    }

    fun onVisible() {
        val ctx = requireActivity()

        // 移除旧的观察者，切换到当前播放频道的 EPG LiveData
        currentTvModel?.epg?.removeObserver(epgObserver)

        val tvModel = viewModel.groupModel.getCurrent()
        currentTvModel = tvModel

        tvModel?.let {
            // 观察 EPG 数据变化：万一数据在节目单打开后才加载完，列表自动刷新
            it.epg.observeForever(epgObserver)

            // 立即渲染已有数据（即使为空也要创建 adapter，否则 RecyclerView 空白）
            bindEPG(it.epgValue)
        }

        handler.postDelayed(hideRunnable, delay)
    }

    /**
     * 用给定的 EPG 列表刷新 RecyclerView，并自动定位到当前正在播的节目。
     */
    private fun bindEPG(epgList: List<EPG>) {
        val ctx = requireActivity()
        val index = epgList.indexOfFirst { epg -> epg.endTime > Utils.getDateTimestamp() }

        programAdapter = ProgramAdapter(ctx, binding.list, epgList, index)
        binding.list.adapter = programAdapter
        binding.list.layoutManager = LinearLayoutManager(ctx)
        programAdapter.setItemListener(this)

        if (index > -1) {
            programAdapter.scrollToPositionAndSelect(index)
        }
    }

    fun onHidden() {
        handler.removeCallbacks(hideRunnable)
        // 隐藏时取消 EPG 观察，避免不可见时触发更新
        currentTvModel?.epg?.removeObserver(epgObserver)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            onVisible()
        } else {
            onHidden()
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(hideRunnable)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        currentTvModel?.epg?.removeObserver(epgObserver)
        currentTvModel = null
        _binding = null
    }

    override fun onItemFocusChange(epg: EPG, hasFocus: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

    override fun onKey(keyCode: Int): Boolean {
        return false
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}
