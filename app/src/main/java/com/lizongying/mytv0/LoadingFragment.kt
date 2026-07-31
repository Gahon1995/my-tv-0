package com.lizongying.mytv0

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.lizongying.mytv0.databinding.LoadingBinding

class LoadingFragment : Fragment() {
    private var _binding: LoadingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LoadingBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as MyTVApplication

        val barW = application.px2Px(binding.bar.layoutParams.width)
        val barH = application.px2Px(binding.bar.layoutParams.height)

        // 直接用带 gravity 的 FrameLayout.LayoutParams 把孩子定位到根的中心，
        // 不依赖根 XML 上可能在 Fragment show/hide 复用中被丢弃的 gravity。
        binding.bar.layoutParams = FrameLayout.LayoutParams(barW, barH, Gravity.CENTER)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "LoadingFragment"
    }
}