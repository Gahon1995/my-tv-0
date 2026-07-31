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

        binding.bar.layoutParams.width = application.px2Px(binding.bar.layoutParams.width)
        binding.bar.layoutParams.height = application.px2Px(binding.bar.layoutParams.height)

        // 根铺满 + 代码显式居中：Fragment show/hide 复用会丢失 XML 的 gravity，
        // 需同 ErrorFragment 一样在代码里强制 gravity=center。
        _binding!!.root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        _binding!!.root.gravity = Gravity.CENTER

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