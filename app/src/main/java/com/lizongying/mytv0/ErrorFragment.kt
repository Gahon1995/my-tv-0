package com.lizongying.mytv0

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.lizongying.mytv0.databinding.ErrorBinding
import com.lizongying.mytv0.view.FocusFx

class ErrorFragment : Fragment() {
    private var _binding: ErrorBinding? = null
    private val binding get() = _binding!!

    private var pendingMsg: String? = null
    private var pendingSub: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ErrorBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as MyTVApplication

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        binding.logo.layoutParams.height = application.px2Px(binding.logo.layoutParams.height)

        val layoutParams = binding.msg.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.topMargin = application.px2Px(binding.msg.marginTop)
        binding.msg.layoutParams = layoutParams

        binding.msg.textSize = application.px2PxFontElder(binding.msg.textSize)
        binding.subMsg.textSize = application.px2PxFontElder(binding.subMsg.textSize)

        pendingMsg?.let { binding.msg.text = it }
        pendingSub?.let { binding.subMsg.text = it }

        return binding.root
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            _binding?.let { FocusFx.panelIn(it.errorCard) }
        }
    }

    fun setMsg(msg: String) {
        pendingMsg = msg
        if (_binding != null) {
            binding.msg.text = msg
        }
    }

    fun setSubMsg(sub: String) {
        pendingSub = sub
        if (_binding != null) {
            binding.subMsg.text = sub
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
