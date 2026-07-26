package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.marginBottom
import androidx.core.view.marginStart
import androidx.core.view.marginTop
import androidx.fragment.app.Fragment
import com.lizongying.mytv0.databinding.InfoBinding
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.view.FocusFx


class InfoFragment : Fragment() {
    private var _binding: InfoBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.getMainLooper())
    private val delay: Long = 5000
    private val progressInterval: Long = 30000

    private var currentTVModel: TVModel? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = InfoBinding.inflate(inflater, container, false)

        val application = requireActivity().applicationContext as MyTVApplication

        binding.info.layoutParams.width = application.px2Px(binding.info.layoutParams.width)

        val layoutParams = binding.info.layoutParams as ViewGroup.MarginLayoutParams
        layoutParams.bottomMargin = application.px2Px(binding.info.marginBottom)
        binding.info.layoutParams = layoutParams

        binding.logo.layoutParams.width = application.px2Px(binding.logo.layoutParams.width)
        binding.logo.layoutParams.height = application.px2Px(binding.logo.layoutParams.height)
        val padding = application.px2Px(binding.logo.paddingTop)
        binding.logo.setPadding(padding, padding, padding, padding)

        val layoutParamsMain = binding.main.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsMain.marginStart = application.px2Px(binding.main.marginStart)
        binding.main.layoutParams = layoutParamsMain

        val layoutParamsDesc = binding.desc.layoutParams as ViewGroup.MarginLayoutParams
        layoutParamsDesc.topMargin = application.px2Px(binding.desc.marginTop)
        binding.desc.layoutParams = layoutParamsDesc

        binding.channelNum.textSize = application.px2PxFontElder(binding.channelNum.textSize)
        binding.title.textSize = application.px2PxFontElder(binding.title.textSize)
        binding.desc.textSize = application.px2PxFontElder(binding.desc.textSize)

        binding.container.layoutParams.width = application.shouldWidthPx()
        binding.container.layoutParams.height = application.shouldHeightPx()

        _binding!!.root.visibility = View.GONE
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as MainActivity).ready(TAG)
    }

    fun show(tvModel: TVModel) {
        if (!isAdded) {
            Log.e(TAG, "Fragment not attached to a context.")
            return
        }

        currentTVModel = tvModel
        val tv = tvModel.tv

        val context = requireContext()
        val application = context.applicationContext as MyTVApplication
        val imageHelper = application.imageHelper

        binding.title.text = tv.title

        val channelNum = if (tv.number == -1) tv.id.plus(1) else tv.number
        binding.channelNum.text = channelNum.toString()

        val bitmap = PlaceholderLogo.get(context, channelNum)
        val name = tv.name.ifEmpty { tv.title }
        imageHelper.loadImage(name, binding.logo, bitmap, tv.logo)

        updateEpgInfo()

        handler.removeCallbacks(removeRunnable)
        handler.removeCallbacks(progressRunnable)
        view?.visibility = View.VISIBLE
        _binding?.let { FocusFx.panelIn(it.info) }
        handler.postDelayed(removeRunnable, delay)
        handler.postDelayed(progressRunnable, progressInterval)
    }

    /** 当前节目 + 时段 + 接下来 + 进度条 */
    private fun updateEpgInfo() {
        val b = _binding ?: return
        val tvModel = currentTVModel ?: return
        val now = Utils.getDateTimestamp()
        val epgList = tvModel.epg.value

        val current = epgList?.firstOrNull { it.beginTime <= now && it.endTime > now }
        val next = epgList?.firstOrNull { it.beginTime >= now }

        if (current != null) {
            val begin = Utils.getDateFormat("HH:mm", current.beginTime)
            val end = Utils.getDateFormat("HH:mm", current.endTime)
            var text =
                "${R.string.now_playing.getString()}${current.title} $begin-$end"
            if (next != null && next.title != current.title) {
                text += " ・ ${R.string.up_next.getString()}${next.title}"
            }
            b.desc.text = text

            val span = current.endTime - current.beginTime
            if (span > 0) {
                b.epgProgress.progress =
                    ((now - current.beginTime) * 100 / span).toInt().coerceIn(0, 100)
                b.epgProgress.visibility = View.VISIBLE
            } else {
                b.epgProgress.visibility = View.GONE
            }
        } else {
            // 无 EPG：回退旧行为
            val last = epgList?.filter { it.beginTime < now }
            b.desc.text = if (!last.isNullOrEmpty()) last.last().title else "精彩節目"
            b.epgProgress.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(removeRunnable, delay)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(removeRunnable)
        handler.removeCallbacks(progressRunnable)
    }

    private val removeRunnable = Runnable {
        handler.removeCallbacks(progressRunnable)
        view?.visibility = View.GONE
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (view?.visibility == View.VISIBLE) {
                updateEpgInfo()
                handler.postDelayed(this, progressInterval)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(removeRunnable)
        handler.removeCallbacks(progressRunnable)
        _binding = null
    }

    companion object {
        private const val TAG = "InfoFragment"
    }
}
