package com.lizongying.mytv0

import MainViewModel
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginEnd
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv0.databinding.TimeBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TimeFragment : Fragment() {
    private var _binding: TimeBinding? = null
    private val binding get() = _binding!!

    private val delay: Long = 1000

    private var job: Job? = null

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = TimeBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as MyTVApplication

        binding.timeCapsule.layoutParams.height = application.px2Px(binding.timeCapsule.layoutParams.height)

        val layoutParams = binding.timeCapsule.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.timeCapsule.marginTop)
        layoutParams.marginEnd = application.px2Px(binding.timeCapsule.marginEnd)
        binding.timeCapsule.layoutParams = layoutParams

        binding.content.textSize = application.px2PxFont(binding.content.textSize)

        binding.main.layoutParams.width = application.shouldWidthPx()
        binding.main.layoutParams.height = application.shouldHeightPx()

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        job = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                binding.content.text = viewModel.getTime()
                delay(delay)
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            if (_binding == null) {
                Log.w(TAG, "_binding is null")
                return
            }

            if (!this::viewModel.isInitialized) {
                Log.w(TAG, "viewModel is not initialized")
                return
            }

            job = viewLifecycleOwner.lifecycleScope.launch {
                while (isActive) {
                    binding.content.text = viewModel.getTime()
                    delay(delay)
                }
            }
        } else {
            job?.cancel()
            job = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "TimeFragment"
    }
}