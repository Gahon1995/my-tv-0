package com.lizongying.mytv0

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.lizongying.mytv0.databinding.ErrorBinding

class ErrorFragment : Fragment() {
    private var _binding: ErrorBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // 只 inflate 一次（原实现 inflate 了两次并返回第二个未做像素适配的实例，
        // 既泄漏又可能改变 Fragment 根在容器中的布局），并强制根占满全屏以便居中。
        _binding = ErrorBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as MyTVApplication

        // 根铺满全屏；内部 panel(main) 用带 gravity=CENTER 的 FrameLayout.LayoutParams
        // 显式定位到根中心，不依赖根 XML 上可能在 Fragment show/hide 复用中被丢弃的 gravity。
        _binding!!.root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        binding.main.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        )

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        binding.logo.layoutParams.height = application.px2Px(binding.logo.layoutParams.height)

        val layoutParams = binding.msg.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.msg.marginTop)
        binding.msg.layoutParams = layoutParams

        binding.msg.textSize = application.px2PxFont(binding.msg.textSize)

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

    fun setMsg(msg: String) {
        if (_binding != null) {
            binding.msg.text = msg
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "ErrorFragment"
    }
}