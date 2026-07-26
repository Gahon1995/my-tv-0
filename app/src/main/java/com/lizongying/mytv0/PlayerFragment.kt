package com.lizongying.mytv0

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.TextureView
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
import com.lizongying.mytv0.view.VideoTexture
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
        // 注册视频纹理供玻璃面板取景（surface_type=texture_view）
        VideoTexture.view = binding.playerView.videoSurfaceView as? TextureView
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
                    player?.seekTo(it.progress * 1000L)
                }
                scheduleHideSeek()
            }
        })
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
        binding.seekTitle.text = "${R.string.catchup_playing.getString()} · $title  (${
            Utils.getDateFormat("HH:mm", tv.catchupBegin.toInt())
        }-${Utils.getDateFormat("HH:mm", tv.catchupEnd.toInt())})"
        binding.seekOverlay.visibility = View.VISIBLE
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
            val p = player ?: return
            if (_binding == null) {
                return
            }
            val duration = p.duration
            if (duration > 0) {
                binding.seekBar.max = (duration / 1000).toInt()
                if (!seekBarDragging) {
                    binding.seekBar.progress = (p.currentPosition / 1000).toInt()
                    binding.seekPosition.text = formatSeekTime(p.currentPosition)
                }
                binding.seekDuration.text = formatSeekTime(duration)
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

    // 快进/快退（秒），并弹出时移条
    fun seekOffset(seconds: Int) {
        val p = player ?: return
        if (!isCatchup()) {
            return
        }
        val duration = p.duration
        var target = p.currentPosition + seconds * 1000L
        target = target.coerceAtLeast(0)
        if (duration > 0) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
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

        // 直播快速起播：降低起播/重缓冲水位，减小换台等待时间
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
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
                    // 回看起播时弹出时移条提示可拖动
                    if (tv.isCatchup) {
                        showSeekOverlay()
                    }
                } else {
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
                        tv.nextVideo()
                        tv.setReady(true)
                        tv.retryTimes = 0
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
        player?.run {
            tvModel.getVideoUrl() ?: return

            while (true) {
                val last = tvModel.isLastVideo()
                val mediaItem = tvModel.getMediaItem()
                if (mediaItem == null) {
                    if (last) {
                        tvModel.setErrInfo(R.string.play_error.getString())
                        break
                    }
                    tvModel.nextVideo()
                    continue
                }
                val mediaSource = tvModel.getMediaSource()
                if (mediaSource != null) {
                    setMediaSource(mediaSource)
                } else {
                    setMediaItem(mediaItem)
                }
                prepare()
                break
            }
        }
    }

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
                    val infosNew = infos.find { it.name == "c2.android.hevc.decoder" }
                        ?.let { mutableListOf(it) }
                    if (infosNew != null) {
                        return infosNew
                    }
                }
            }
            return infos
        }
    }

    fun showVolume(visibility: Int) {
        binding.icon.visibility = visibility
        binding.volume.visibility = visibility
        hideVolume()
    }

    fun setVolumeMax(volume: Int) {
        binding.volume.max = volume
    }

    fun setVolume(progress: Int, volume: Boolean = false) {
        val context = requireContext()
        binding.volume.progress = progress
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
        binding.icon.visibility = View.GONE
        binding.volume.visibility = View.GONE
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
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(autoRecoverRunnable)
        handler.removeCallbacks(updateSeekRunnable)
        handler.removeCallbacks(hideSeekRunnable)
        player?.release()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (VideoTexture.view === binding.playerView.videoSurfaceView) {
            VideoTexture.view = null
        }
        _binding = null
    }

    companion object {
        private const val TAG = "PlayerFragment"
        private const val AUTO_RECOVER_DELAY = 30 * 1000L
    }
}