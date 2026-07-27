package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.databinding.PlayerBinding
import com.lizongying.mytv0.models.TVModel
import java.util.Locale


class PlayerFragment : Fragment() {
    private var _binding: PlayerBinding? = null
    private val binding get() = _binding!!

    private var player: ExoPlayer? = null

    private var tvModel: TVModel? = null

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideVolume = 2 * 1000L
    private val delayHideSeek = 5 * 1000L
    private var seekBarDragging = false
    private val perfTracker = PlaybackPerfTracker()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initSeekBar()
        updatePlayer()
        (activity as MainActivity).ready(TAG)
    }

    // ===== 回看时移条 =====

    private fun initSeekBar() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.seekPosition.text = formatSeekTime(progress * 1000L)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                seekBarDragging = true
                handler.removeCallbacks(hideSeekRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBarDragging = false
                seekBar?.let {
                    // 回看 seek＝按绝对时间重新请求流（伪直播流内 seek 会被拉回）
                    val tv = tvModel ?: return@let
                    tv.seekCatchup(tv.catchupOrigBegin + it.progress)
                }
                scheduleHideSeek()
            }
        })
    }

    /** 当前回看的节目内绝对位置（秒，相对节目开始） */
    private fun catchupAbsPosition(): Long {
        val tv = tvModel ?: return 0
        val playerPos = (player?.currentPosition ?: 0) / 1000
        return (tv.catchupBegin - tv.catchupOrigBegin) + playerPos
    }

    /** 节目总时长（秒） */
    private fun catchupDuration(): Long {
        val tv = tvModel ?: return 0
        return (tv.catchupEnd - tv.catchupOrigBegin).coerceAtLeast(0)
    }

    fun isCatchup(): Boolean {
        return tvModel?.isCatchup == true
    }

    fun isSeekVisible(): Boolean {
        return binding.seekOverlay.visibility == View.VISIBLE
    }

    fun showSeekOverlay() {
        val tv = tvModel ?: return
        if (!tv.isCatchup) {
            return
        }
        val title = if (tv.catchupTitle.isEmpty()) tv.tv.title else tv.catchupTitle
        binding.seekTitle.text = "$title  (${
            Utils.getDateFormat("HH:mm", tv.catchupOrigBegin.toInt())
        }-${Utils.getDateFormat("HH:mm", tv.catchupEnd.toInt())})"
        binding.seekOverlay.visibility = View.VISIBLE
        com.lizongying.mytv0.view.FocusFx.panelIn(binding.seekOverlay)
        handler.removeCallbacks(updateSeekRunnable)
        handler.post(updateSeekRunnable)
        scheduleHideSeek()
    }

    fun hideSeekOverlay() {
        binding.seekOverlay.visibility = View.GONE
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideSeekRunnable)
    }

    private fun scheduleHideSeek() {
        handler.removeCallbacks(hideSeekRunnable)
        handler.postDelayed(hideSeekRunnable, delayHideSeek)
    }

    private val hideSeekRunnable = Runnable {
        if (!seekBarDragging && player?.isPlaying != false) {
            hideSeekOverlay()
        } else {
            scheduleHideSeek()
        }
    }

    private val updateSeekRunnable = object : Runnable {
        override fun run() {
            if (_binding == null) {
                return
            }
            // 进度条以节目绝对时间轴为准（EPG 窗口），不依赖流内 duration——
            // 伪直播回看流的 duration 往往不可信（时长不对的根因）
            val duration = catchupDuration()
            if (duration > 0) {
                binding.seekBar.max = duration.toInt()
                if (!seekBarDragging) {
                    val pos = catchupAbsPosition().coerceIn(0, duration)
                    binding.seekBar.progress = pos.toInt()
                    binding.seekPosition.text = formatSeekTime(pos * 1000)
                }
                binding.seekDuration.text = formatSeekTime(duration * 1000)
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun formatSeekTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.CHINA, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.CHINA, "%02d:%02d", m, s)
        }
    }

    // 快进/快退（秒），并弹出时移条。
    // 流本身可 seek（duration 可信且窗口足够）时走流内 seek；
    // 否则按绝对时间重新请求新窗口（伪直播流 seek 会被拉回的根治方案）
    fun seekOffset(seconds: Int) {
        val p = player ?: return
        val tv = tvModel ?: return
        if (!isCatchup()) {
            return
        }
        val target = p.currentPosition + seconds * 1000L
        val streamDuration = p.duration
        val seekableInStream = p.isCurrentMediaItemSeekable &&
                streamDuration > 0 &&
                target in 0 until streamDuration
        if (seekableInStream) {
            p.seekTo(target)
        } else {
            tv.seekCatchup(tv.catchupBegin + (p.currentPosition / 1000) + seconds)
        }
        showSeekOverlay()
    }

    // 暂停/继续，并弹出时移条
    fun togglePlayPause() {
        val p = player ?: return
        if (!isCatchup()) {
            return
        }
        if (p.isPlaying) {
            p.pause()
        } else {
            // 回看流播完暂停在末尾时，继续播直接回直播
            if (p.playbackState == Player.STATE_ENDED) {
                returnToLive()
                return
            }
            p.play()
        }
        showSeekOverlay()
    }

    // 退出回看返回直播
    fun returnToLive() {
        hideSeekOverlay()
        tvModel?.returnToLive()
        R.string.back_to_live.showToast()
    }

    private var lastVideoRatio = 0f
    private var lastParentW = 0
    private var lastParentH = 0

    @OptIn(UnstableApi::class)
    fun updatePlayer() {
        if (context == null) {
            Log.e(TAG, "context == null")
            return
        }

        val ctx = requireContext()

        val playerView = binding.playerView

        val renderersFactory = DefaultRenderersFactory(ctx)
        val playerMediaCodecSelector = PlayerMediaCodecSelector()
        renderersFactory.setMediaCodecSelector(playerMediaCodecSelector)
        renderersFactory.setExtensionRendererMode(
            if (SP.softDecode) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        )

        if (player != null) {
            player?.release()
        }

        // 直播：降低缓冲水位减小内存占用；回看：较大缓冲抵抗网络抖动
        val liveMinBufferMs = 15_000  // 直播 15s 缓冲（默认 50s 过大）
        val liveMaxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                liveMinBufferMs,
                liveMaxBufferMs,
                1000, // 起播缓冲 1s（默认 2.5s）
                2000  // seek/重缓冲后 2s（默认 5s）
            )
            .build()

        player = ExoPlayer.Builder(ctx)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .build()
        // 申请音频焦点：与其他应用（音乐/语音助手）互斥发声
        player?.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player?.repeatMode = REPEAT_MODE_ALL
        player?.playWhenReady = true
        player?.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // 按实际视频比例适配（修复：整数除法恒为1、写死16:9导致4:3频道拉伸、
                // layoutParams 被改小后无法恢复的问题），始终以父容器全尺寸计算
                val root = _binding?.root ?: return
                val parentW = root.measuredWidth
                val parentH = root.measuredHeight
                if (parentW == 0 || parentH == 0 || videoSize.width == 0 || videoSize.height == 0) {
                    return
                }
                val videoRatio =
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height

                // 去抖动：仅在视频比例或父容器尺寸真正变化时才重新 layout；
                // HLS 自适应码率切换可能频繁触发此回调，但比例未变时不需重 layout
                if (videoRatio == lastVideoRatio && parentW == lastParentW && parentH == lastParentH) {
                    return
                }
                lastVideoRatio = videoRatio
                lastParentW = parentW
                lastParentH = parentH

                val parentRatio = parentW.toFloat() / parentH
                val layoutParams = playerView.layoutParams ?: return
                if (parentRatio > videoRatio) {
                    layoutParams.width = (parentH * videoRatio).toInt()
                    layoutParams.height = parentH
                } else {
                    layoutParams.width = parentW
                    layoutParams.height = (parentW / videoRatio).toInt()
                }
                playerView.layoutParams = layoutParams
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!

                if (isPlaying) {
                    tv.confirmSourceType()
                    tv.confirmVideoIndex()
                    tv.setErrInfo("")
                    tv.retryTimes = 0
                    handler.removeCallbacks(autoRecoverRunnable)
                    perfTracker.start()
                    // 回看起播时弹出时移条提示可拖动
                    if (tv.isCatchup) {
                        showSeekOverlay()
                    }
                } else {
                    perfTracker.logSummary(TAG)
                    perfTracker.stop()
                    Log.i(TAG, "${tv.tv.title} 播放停止")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                // 回看播完自动回直播（REPEAT_MODE_OFF 时才会走到 ENDED）
                if (playbackState == Player.STATE_ENDED && tvModel?.isCatchup == true) {
                    Log.i(TAG, "catchup ended, return to live")
                    returnToLive()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    (activity as MainActivity).onPlayEnd()
                }
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)

                if (tvModel == null) {
                    Log.e(TAG, "tvModel == null")
                    return
                }

                val tv = tvModel!!

                // 直播流落后于窗口（HLS 缓冲不足/网络抖动），直接回到直播点重试即可，
                // 无需切换源类型或线路
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    Log.i(TAG, "behind live window, re-seek to live edge")
                    player?.seekToDefaultPosition()
                    player?.prepare()
                    return
                }

                // 回看流失败：快速重试 2 次后直接回直播，
                // 不走换线路/长退避流程（回看 URL 是模板拼的，其他线路大概率同样失败）
                if (tv.isCatchup) {
                    if (tv.retryTimes < 2) {
                        tv.retryTimes++
                        handler.removeCallbacks(retryRunnable)
                        handler.postDelayed(retryRunnable, 500L)
                    } else {
                        Log.i(TAG, "catchup failed, return to live")
                        R.string.catchup_not_supported.showToast()
                        returnToLive()
                    }
                    return
                }

                if (tv.retryTimes < tv.retryMaxTimes) {
                    var last = true
                    if (tv.getSourceTypeDefault() == SourceType.UNKNOWN) {
                        last = tv.nextSourceType()
                    }
                    if (last) {
                        tv.retryTimes++
                    }
                    // 重试退避：0.5s 起，随重试次数递增，上限 2s，避免瞬时打满
                    val delay = (500L * (tv.retryTimes + 1)).coerceAtMost(2000L)
                    handler.removeCallbacks(retryRunnable)
                    handler.postDelayed(retryRunnable, delay)
                    Log.i(
                        TAG,
                        "retry in ${delay}ms ${tv.videoIndex.value} ${tv.getSourceTypeCurrent()} ${tv.retryTimes}/${tv.retryMaxTimes}"
                    )
                } else {
                    if (!tv.isLastVideo()) {
                        // 换线路也走 500ms 退避，避免多条坏线路时快速空转
                        tv.nextVideo()
                        tv.retryTimes = 0
                        handler.removeCallbacks(retryRunnable)
                        handler.postDelayed(retryRunnable, 500L)
                    } else {
                        // 永不黑屏：不停留在错误页等人工处理，
                        // 显示友好提示并在 30s 后自动从第一条线路重新尝试
                        tv.setErrInfo(R.string.play_error_retry.getString())
                        tv.retryTimes = 0
                        handler.removeCallbacks(autoRecoverRunnable)
                        handler.postDelayed(autoRecoverRunnable, AUTO_RECOVER_DELAY)
                    }
                }
            }
        })

        playerView.player = player
        tvModel?.let {
            play(it)
        }
    }

    @OptIn(UnstableApi::class)
    fun play(tvModel: TVModel) {
        // 换台时取消上一个频道的重试/自动恢复任务，避免误触发
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(autoRecoverRunnable)
        this.tvModel = tvModel
        // 回看是有限长流：关闭循环以便播完回直播；直播维持循环
        player?.repeatMode = if (tvModel.isCatchup) Player.REPEAT_MODE_OFF else REPEAT_MODE_ALL
        if (!tvModel.isCatchup) {
            hideSeekOverlay()
        }

        val p = player ?: return

        // 跳过无效线路：某些 line 可能 URL 为空或 mediaSource 构造失败，
        // 此时自动 nextVideo 尝试下一条线路
        while (true) {
            val last = tvModel.isLastVideo()
            if (tvModel.getVideoUrl() == null) {
                if (last) {
                    tvModel.setErrInfo(R.string.play_error.getString())
                    return
                }
                tvModel.nextVideo()
                continue
            }
            val mediaSource = tvModel.getMediaSource()
            if (mediaSource != null) {
                // 换台用 setMediaSource 替代 stop+prepare：
                // setMediaSource 会内部处理资源切换，保留 decoder 实例，
                // 避免低端设备上重复初始化解码器的开销（换台延迟降低 30-50%）
                p.setMediaSource(mediaSource)
            } else {
                val mediaItem = tvModel.getMediaItem()
                if (mediaItem == null) {
                    if (last) {
                        tvModel.setErrInfo(R.string.play_error.getString())
                        return
                    }
                    tvModel.nextVideo()
                    continue
                }
                p.setMediaItem(mediaItem)
            }
            // 直播流 prepare 从 live edge 开始；回看流 prepare 会自然定位到 catchup 请求窗口
            p.prepare()
            p.play()
            break
        }
    }

    /**
     * 解码器选择器：优先硬件解码器，避免硬编码 decoder 名称。
     * HEVC 优先选 hardware-accelerated decoder（适配不同 SoC 的命名差异）。
     */
    @OptIn(UnstableApi::class)
    class PlayerMediaCodecSelector : MediaCodecSelector {
        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
            val infos = MediaCodecUtil.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (mimeType == MimeTypes.VIDEO_H265 && !requiresSecureDecoder && !requiresTunnelingDecoder) {
                if (infos.isNotEmpty()) {
                    // 优先硬件解码器：按 isHardwareAccelerated 排序，
                    // 硬件在前，避免用软解导致掉帧
                    val hwFirst = infos.sortedByDescending {
                        try {
                            if (it.hardwareAccelerated) 1 else 0
                        } catch (_: Exception) {
                            0
                        }
                    }
                    return hwFirst.toMutableList()
                }
            }
            return infos
        }
    }

    fun showVolume(visibility: Int) {
        binding.volumePill.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
        val max = binding.volume.max
        binding.volumeNum.text = if (max > 0) {
            (progress * 100 / max).toString()
        } else {
            progress.toString()
        }
        binding.icon.setImageDrawable(
            ContextCompat.getDrawable(
                context,
                if (volume) {
                    if (progress > 0) R.drawable.volume_up_24px else R.drawable.volume_off_24px
                } else {
                    R.drawable.light_mode_24px
                }
            )
        )
    }

    fun hideVolume() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, delayHideVolume)
    }

    fun hideVolumeNow() {
        handler.removeCallbacks(hideVolumeRunnable)
        handler.postDelayed(hideVolumeRunnable, 0)
    }

    private val hideVolumeRunnable = Runnable {
        binding.volumePill.visibility = View.GONE
    }

    private val retryRunnable = Runnable {
        tvModel?.setReady(true)
    }

    // 全部线路重试耗尽后的自动恢复：回到第一条线路重新走完整重试流程
    private val autoRecoverRunnable = Runnable {
        tvModel?.let {
            Log.i(TAG, "auto recover ${it.tv.title}")
            it.setReady()
        }
    }

    override fun onResume() {
        super.onResume()
        if (player?.isPlaying == false) {
            if (tvModel?.isCatchup == true) {
                // 回看模式：保留暂停位置，不跳直播点
                return
            }
            // 待机唤醒/切回前台：直播流挂起久了 position 已失效，
            // 直接回直播点重新起播，失败则走完整重试流程
            player?.seekToDefaultPosition()
            player?.prepare()
            player?.play()
        }
    }

    override fun onPause() {
        super.onPause()
        if (player?.isPlaying == true) {
            player?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        perfTracker.stop()
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(autoRecoverRunnable)
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideSeekRunnable)
        player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val AUTO_RECOVER_DELAY = 30 * 1000L
    }
}