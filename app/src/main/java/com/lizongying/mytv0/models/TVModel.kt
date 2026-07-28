package com.lizongying.mytv0.models

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.requests.HttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class TVModel(var tv: TV) : ViewModel() {
    var retryTimes = 0
    var retryMaxTimes = 10
    var programUpdateTime = 0L

    private var _groupIndex = 0
    val groupIndex: Int
        get() = if (SP.showAllChannels || _groupIndex == 0) _groupIndex else _groupIndex - 1

    fun setGroupIndex(index: Int) {
        _groupIndex = index
    }

    fun getGroupIndexInAll(): Int {
        return _groupIndex
    }

    var listIndex = 0

    private var sourceTypeList: List<SourceType> =
        listOf(
            SourceType.UNKNOWN,
        )
    private var sourceTypeIndex = 0

    private val _errInfo = MutableLiveData<String>()
    val errInfo: LiveData<String>
        get() = _errInfo

    fun setErrInfo(info: String) {
        _errInfo.value = info
    }

    private var _epg = MutableLiveData<List<EPG>>()
    val epg: LiveData<List<EPG>>
        get() = _epg
    val epgValue: List<EPG>
        get() = _epg.value ?: emptyList()

    fun setEpg(epg: List<EPG>) {
        _epg.value = epg
        // 同时更新当前节目缓存
        _epgNowTitle = computeEpgNow(epg)
    }

    // 缓存当前 EPG 节目标题，避免 RecyclerView 绑定时重复遍历
    private var _epgNowTitle: String? = null
    val epgNowTitle: String?
        get() = _epgNowTitle

    private fun computeEpgNow(epg: List<EPG>): String? {
        if (epg.isEmpty()) return null
        val now = System.currentTimeMillis() / 1000
        return epg.firstOrNull { it.beginTime <= now && it.endTime > now }?.title
    }

    // ===== 回看/时移状态 =====
    private var _isCatchup = false
    val isCatchup: Boolean
        get() = _isCatchup

    // 回看窗口（unix 秒）。catchupBegin 是当前请求的起点；
    // catchupOrigBegin 是节目原始起点（进度条刻度基准，seek 重请求时不变）
    var catchupBegin = 0L
        private set
    var catchupOrigBegin = 0L
        private set
    var catchupEnd = 0L
        private set
    var catchupTitle = ""
        private set

    fun supportsCatchup(): Boolean {
        val mode = tv.catchup?.lowercase() ?: return false
        return when (mode) {
            "append", "default" -> !tv.catchupSource.isNullOrEmpty()
            else -> true // shift 等模式无需模板
        }
    }

    // 播放指定时间段回看；通过 ready 触发正常播放流程
    fun playCatchup(begin: Long, end: Long, title: String = "") {
        catchupBegin = begin
        catchupOrigBegin = begin
        catchupEnd = end
        catchupTitle = title
        _isCatchup = true
        setErrInfo("")
        retryTimes = 0
        _ready.value = true
    }

    /**
     * 回看内 seek：以重新请求新时间窗实现（伪直播流内 seek 会被拉回）。
     * targetAbs 为目标绝对时间（unix 秒），自动夹取在节目窗口内。
     */
    fun seekCatchup(targetAbs: Long) {
        if (!_isCatchup) return
        catchupBegin = targetAbs.coerceIn(catchupOrigBegin, catchupEnd - 5)
        setErrInfo("")
        retryTimes = 0
        _ready.value = true
    }

    // 返回直播
    fun returnToLive() {
        if (!_isCatchup) {
            return
        }
        _isCatchup = false
        setErrInfo("")
        retryTimes = 0
        _ready.value = true
    }

    // ${(b)yyyyMMddHHmmss} / ${(e)yyyyMMddHHmmss} 时间格式模板
    private val catchupFmtRegex = Regex("""\$\{\((b|e)\)([^}]+)\}""")

    // 将 catchup 模板展开为实际回看地址
    private fun expandCatchupTemplate(template: String): String {
        val b = catchupBegin
        val e = catchupEnd
        val now = Utils.getDateTimestamp()
        var s = catchupFmtRegex.replace(template) { m ->
            val ts = if (m.groupValues[1] == "b") b else e
            try {
                SimpleDateFormat(m.groupValues[2], Locale.CHINA).format(Date(ts * 1000))
            } catch (ex: Exception) {
                m.value
            }
        }
        // 常见 token 变体（diyp/TiviMate 生态）
        val tokens = mapOf(
            "{utc}" to b.toString(),
            "\${utc}" to b.toString(),
            "{utcend}" to e.toString(),
            "\${utcend}" to e.toString(),
            "{start}" to b.toString(),
            "\${start}" to b.toString(),
            "{end}" to e.toString(),
            "\${end}" to e.toString(),
            "{lutc}" to now.toString(),
            "\${lutc}" to now.toString(),
            "{now}" to now.toString(),
            "\${now}" to now.toString(),
            "{timestamp}" to now.toString(),
            "\${timestamp}" to now.toString(),
            "{offset}" to (now - b).toString(),
            "\${offset}" to (now - b).toString(),
            "{duration}" to (e - b).toString(),
            "\${duration}" to (e - b).toString(),
        )
        for ((k, v) in tokens) {
            s = s.replace(k, v)
        }
        return s
    }

    // 根据直播地址构造回看地址；失败返回 null
    fun buildCatchupUrl(liveUrl: String): String? {
        val mode = tv.catchup?.lowercase() ?: return null
        val src = tv.catchupSource ?: ""
        val template = when (mode) {
            "append" -> {
                if (src.isEmpty()) {
                    return null
                }
                // 避免拼出 "??" 或重复分隔符
                if (src.startsWith("?")) {
                    if (liveUrl.endsWith("?") || liveUrl.endsWith("&")) {
                        liveUrl + src.substring(1)
                    } else if (liveUrl.contains("?")) {
                        liveUrl + "&" + src.substring(1)
                    } else {
                        liveUrl + src
                    }
                } else {
                    liveUrl + src
                }
            }

            "default" -> if (src.isEmpty()) return null else src

            "shift" -> {
                val sep = if (liveUrl.contains("?")) "&" else "?"
                "$liveUrl${sep}utc={utc}&lutc={lutc}"
            }

            else -> return null
        }
        return expandCatchupTemplate(template)
    }

    private val _videoIndex = MutableLiveData<Int>()
    val videoIndex: LiveData<Int>
        get() = _videoIndex
    val videoIndexValue: Int
        get() = _videoIndex.value ?: 0

    fun getVideoUrl(): String? {
        if (videoIndexValue >= tv.uris.size) {
            return null
        }

        val liveUrl = tv.uris[videoIndexValue]

        if (_isCatchup) {
            val url = buildCatchupUrl(liveUrl)
            if (url != null) {
                return url
            }
            // 模板异常时回退直播，避免黑屏
            _isCatchup = false
        }

        return liveUrl
    }

    private val _like = MutableLiveData<Boolean>()
    val like: LiveData<Boolean>
        get() = _like

    fun setLike(liked: Boolean) {
        _like.value = liked
    }

    private val _ready = MutableLiveData<Boolean>()
    val ready: LiveData<Boolean>
        get() = _ready

    fun setReady(retry: Boolean = false) {
        if (!retry) {
            setErrInfo("")
            retryTimes = 0
            // 正常换台/重选：退出回看，回到直播
            _isCatchup = false

            _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
            sourceTypeIndex =
                max(0, min(sourceTypeList.size - 1, sourceTypeList.indexOf(tv.sourceType)))
        }
        _ready.value = true
    }

    private var userAgent = ""

    private var _httpDataSource: DataSource.Factory? = null
    private var _mediaItem: MediaItem? = null

    @OptIn(UnstableApi::class)
    fun getMediaItem(): MediaItem? {
        _mediaItem = getVideoUrl()?.let {
            val uri = Uri.parse(it) ?: return@let null
            val path = uri.path ?: return@let null
            val scheme = uri.scheme ?: return@let null

            val okHttpDataSource = OkHttpDataSource.Factory(HttpClient.okHttpClient)
            tv.headers?.let { i ->
                okHttpDataSource.setDefaultRequestProperties(i)
                i.forEach { (key, value) ->
                    if (key.equals("user-agent", ignoreCase = true)) {
                        userAgent = value
                        return@forEach
                    }
                }
            }

            _httpDataSource = okHttpDataSource

            sourceTypeList = if (path.lowercase().endsWith(".m3u8")) {
                listOf(SourceType.HLS)
            } else if (path.lowercase().endsWith(".mpd")) {
                listOf(SourceType.DASH)
            } else if (scheme.lowercase() == "rtsp") {
                listOf(SourceType.RTSP)
            } else if (scheme.lowercase() == "rtmp") {
                listOf(SourceType.RTMP)
            } else if (scheme.lowercase() == "rtp" || scheme.lowercase() == "udp") {
                listOf(SourceType.RTP)
            } else {
                listOf(SourceType.HLS, SourceType.PROGRESSIVE)
            }

            MediaItem.fromUri(it)
        }
        return _mediaItem
    }

    fun getSourceTypeDefault(): SourceType {
        return tv.sourceType
    }

    fun getSourceTypeCurrent(): SourceType {
        sourceTypeIndex = max(0, min(sourceTypeList.size - 1, sourceTypeIndex))
        return sourceTypeList[sourceTypeIndex]
    }

    fun nextSourceType(): Boolean {
        sourceTypeIndex = (sourceTypeIndex + 1) % sourceTypeList.size

        return sourceTypeIndex == sourceTypeList.size - 1
    }

    fun confirmSourceType() {
        // TODO save default sourceType
        tv.sourceType = getSourceTypeCurrent()
    }

    fun confirmVideoIndex() {
        tv.videoIndex = videoIndexValue
    }

    @OptIn(UnstableApi::class)
    fun getMediaSource(): MediaSource? {
        if (sourceTypeList.isEmpty()) {
            return null
        }

        if (_mediaItem == null) {
            return null
        }
        val mediaItem = _mediaItem!!

        if (_httpDataSource == null) {
            return null
        }
        val httpDataSource = _httpDataSource!!

        return when (getSourceTypeCurrent()) {
            SourceType.HLS -> HlsMediaSource.Factory(httpDataSource).createMediaSource(mediaItem)
            SourceType.RTSP -> if (userAgent.isEmpty()) {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            } else {
                RtspMediaSource.Factory().setUserAgent(userAgent).createMediaSource(mediaItem)
            }

            SourceType.RTMP -> {
                val rtmpDataSource = RtmpDataSource.Factory()
                ProgressiveMediaSource.Factory(rtmpDataSource)
                    .createMediaSource(mediaItem)
            }

            SourceType.RTP -> {
                // 组播 rtp://,udp:// 通过 UdpDataSource 接收 (通常为 MPEG-TS 流)
                val udpDataSource = DataSource.Factory { UdpDataSource(3000, 100_000) }
                ProgressiveMediaSource.Factory(udpDataSource)
                    .createMediaSource(mediaItem)
            }

            SourceType.DASH -> DashMediaSource.Factory(httpDataSource).createMediaSource(mediaItem)
            SourceType.PROGRESSIVE -> ProgressiveMediaSource.Factory(httpDataSource)
                .createMediaSource(mediaItem)

            else -> null
        }
    }

    fun isLastVideo(): Boolean {
        return videoIndexValue == tv.uris.size - 1
    }

    fun nextVideo(): Boolean {
        if (tv.uris.isEmpty()) {
            return false
        }

        _videoIndex.value = (videoIndexValue + 1) % tv.uris.size
        sourceTypeList = listOf(
            SourceType.UNKNOWN,
        )

        return isLastVideo()
    }

    fun update(t: TV) {
        tv = t
    }

    init {
        _videoIndex.value = max(0, min(tv.uris.size - 1, tv.videoIndex))
        _like.value = SP.getLike(tv.id)
    }

    companion object {
        private const val TAG = "TVModel"
    }
}