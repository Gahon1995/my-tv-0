package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.databinding.ProgramBinding

class ProgramFragment : Fragment(), ProgramAdapter.ItemListener {
    private var _binding: ProgramBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler()
    private val delay: Long = 5000

    private lateinit var programAdapter: ProgramAdapter

    private lateinit var viewModel: MainViewModel

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
        val context = requireActivity()

        viewModel.groupModel.getCurrent()?.let {
            val index = it.epgValue.indexOfFirst { it.endTime > Utils.getDateTimestamp() }
            programAdapter = ProgramAdapter(
                context,
                binding.list,
                it.epgValue,
                index,
            )
            binding.list.adapter = programAdapter
            binding.list.layoutManager = LinearLayoutManager(context)

            programAdapter.setItemListener(this)

            if (index > -1) {
                programAdapter.scrollToPositionAndSelect(index)
            }

            handler.postDelayed(hideRunnable, delay)
        }
    }

    fun onHidden() {
        handler.removeCallbacks(hideRunnable)
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
        _binding = null
    }

    override fun onItemFocusChange(epg: EPG, hasFocus: Boolean) {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

    override fun onItemClicked(epg: EPG) {
        val tvModel = viewModel.groupModel.getCurrent() ?: return
        val now = Utils.getDateTimestamp()

        if (epg.beginTime >= now) {
            R.string.catchup_future_program.showToast()
            return
        }

        if (!tvModel.supportsCatchup()) {
            R.string.catchup_not_supported.showToast()
            return
        }

        // 正在直播的节目：回看从开始到当前；已结束节目：完整时段
        val end = if (epg.endTime.toLong() > now) now else epg.endTime.toLong()
        tvModel.playCatchup(epg.beginTime.toLong(), end, epg.title)
        hideSelf()
    }

    override fun onKey(keyCode: Int): Boolean {
        return false
    }

    companion object {
        private const val TAG = "ProgramFragment"
    }
}