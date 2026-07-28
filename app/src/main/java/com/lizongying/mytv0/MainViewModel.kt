import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Base64
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.lizongying.mytv0.ImageHelper
import com.lizongying.mytv0.MyTVApplication
import com.lizongying.mytv0.R
import com.lizongying.mytv0.RemoteConfigManager
import com.lizongying.mytv0.SP
import com.lizongying.mytv0.Utils.getDateFormat
import com.lizongying.mytv0.Utils.getUrls
import com.lizongying.mytv0.bodyAlias
import com.lizongying.mytv0.codeAlias
import com.lizongying.mytv0.data.EPG
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeEPGMap
import com.lizongying.mytv0.data.Global.typeTvList
import com.lizongying.mytv0.data.Source
import com.lizongying.mytv0.data.SourceType
import com.lizongying.mytv0.data.TV
import com.lizongying.mytv0.models.EPGXmlParser
import com.lizongying.mytv0.models.Sources
import com.lizongying.mytv0.models.TVGroupModel
import com.lizongying.mytv0.models.TVListModel
import com.lizongying.mytv0.models.TVModel
import com.lizongying.mytv0.requests.HttpClient
import com.lizongying.mytv0.showToast
import io.github.lizongying.Gua
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream


class MainViewModel : ViewModel() {
    private var timeFormat = if (SP.displaySeconds) "HH:mm:ss" else "HH:mm"

    private lateinit var appDirectory: File
    var listModel: List<TVModel> = emptyList()
    val groupModel = TVGroupModel()
    private var cacheFile: File? = null
    private var cacheChannels = ""
    private var initialized = false

    private lateinit var cacheEPG: File
    private var epgUrl = SP.epg

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

    // TVBox JSON 配置解析后需异步拉取真实直播源，此时不应把 JSON 原文写入缓存
    private var redirectingToLiveUrl = false

    private val _channelsOk = MutableLiveData<Boolean>()
    val channelsOk: LiveData<Boolean>
        get() = _channelsOk

    fun setDisplaySeconds(displaySeconds: Boolean) {
        timeFormat = if (displaySeconds) "HH:mm:ss" else "HH:mm"
        SP.displaySeconds = displaySeconds
    }

    fun getTime(): String {
        return getDateFormat(timeFormat)
    }

    fun updateEPG() {
        viewModelScope.launch {
            var success = false
            // 用户设置的 EPG 优先；m3u 内嵌的 x-tvg-url 作为备选
            if (!SP.epg.isNullOrEmpty()) {
                success = updateEPG(SP.epg!!)
            }
            if (!success && !epgUrl.isNullOrEmpty() && epgUrl != SP.epg) {
                updateEPG(epgUrl!!)
            }
        }
    }

    fun updateConfig() {
        viewModelScope.launch {
            // 先拉取并应用远端配置中心（未配置服务器时为空操作），
            // 使远端下发的直播源/EPG 在本次自动更新中即时生效
            RemoteConfigManager.fetchAndApply(this@MainViewModel)

            if (SP.configAutoLoad) {
                SP.configUrl?.let {
                    if (it.startsWith("http")) {
                        Log.i(TAG, "update config url: $it")
                        importFromUrl(it, silent = true)
                        updateEPG()
                    }
                }
            }
        }
    }

    private fun getCache(): String {
        return if (cacheFile!!.exists()) {
            cacheFile!!.readText()
        } else {
            ""
        }
    }

    fun init(context: Context) {
        val application = context.applicationContext as MyTVApplication
        imageHelper = application.imageHelper

        RemoteConfigManager.init(context.applicationContext)

        groupModel.addTVListModel(TVListModel("我的收藏", 0))
        groupModel.addTVListModel(TVListModel("全部頻道", 1))

        appDirectory = context.filesDir
        cacheFile = File(appDirectory, CACHE_FILE_NAME)
        if (!cacheFile!!.exists()) {
            cacheFile!!.createNewFile()
        }

        cacheChannels = getCache()

        if (cacheChannels.isEmpty()) {
            Log.i(TAG, "cacheChannels isEmpty")
            cacheChannels =
                context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                    .use { it.readText() }
        }

        Log.i(TAG, "cacheChannels $cacheFile $cacheChannels")

        try {
            str2Channels(cacheChannels)
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
            cacheFile!!.deleteOnExit()
            R.string.channel_read_error.showToast()
        }

        viewModelScope.launch {
            cacheEPG = File(appDirectory, CACHE_EPG)
            if (!cacheEPG.exists()) {
                cacheEPG.createNewFile()
            } else {
                Log.i(TAG, "cacheEPG exists")
                if (readEPG(cacheEPG.readText())) {
                    Log.i(TAG, "cacheEPG success")
                } else {
                    Log.i(TAG, "cacheEPG failure")
                }
            }
        }

        initialized = true

        // 通知 UI 层：频道已就绪，可以起播
        _channelsOk.value = true

        // 延迟后异步拉取远端配置与最新直播源，不阻塞初始起播
        viewModelScope.launch {
            kotlinx.coroutines.delay(5_000) // 等初始起播稳定后再拉
            updateConfig()
        }
    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            Log.w(TAG, "imageHelper is not initialized")
            return
        }

        // 同名频道（多线路/多分组）只预加载一次
        val seen = mutableSetOf<String>()
        for (tvModel in listModel) {
            var name = tvModel.tv.name
            if (name.isEmpty()) {
                name = tvModel.tv.title
            }
            if (name.isEmpty() || !seen.add(name)) {
                continue
            }
            val url = tvModel.tv.logo
            // 远端配置的台标基础地址优先，内置地址兜底
            val logoBase = SP.logoBaseUrl?.trim()?.trimEnd('/') ?: ""
            var urls =
                (if (logoBase.isNotEmpty()) getUrls("$logoBase/$name.png") else emptyList()) +
                        listOf(
                            "https://live.fanmingming.cn/tv/$name.png"
                        ) + getUrls("https://raw.githubusercontent.com/fanmingming/live/main/tv/$name.png")
            if (url.isNotEmpty()) {
                urls = (getUrls(url) + urls).distinct()
            }

            imageHelper.preloadImage(
                name,
                urls,
            )
        }
    }

    suspend fun readEPG(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)

            // 名称匹配是 O(N*M) 字符串扫描，必须在 IO 线程完成；
            // 主线程只做 setEpg（LiveData 更新）
            val matched = mutableListOf<Pair<TVModel, List<EPG>>>()
            val e1 = mutableMapOf<String, List<EPG>>()
            for (m in listModel) {
                val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                if (name.isEmpty()) {
                    continue
                }

                for ((n, epg) in res) {
                    if (name.contains(n, ignoreCase = true)) {
                        matched.add(Pair(m, epg))
                        e1[name] = epg
                        break
                    }
                }
            }

            withContext(Dispatchers.Main) {
                for ((m, epg) in matched) {
                    m.setEpg(epg)
                }
            }

            // 大 JSON 序列化留在 IO 线程
            cacheEPG.writeText(gson.toJson(e1))
            Log.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>> = gson.fromJson(str, typeEPGMap)

            val matched = mutableListOf<Pair<TVModel, List<EPG>>>()
            for (m in listModel) {
                val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                if (name.isEmpty()) {
                    continue
                }

                val epg = res[name]
                if (epg != null) {
                    matched.add(Pair(m, epg))
                }
            }

            withContext(Dispatchers.Main) {
                for ((m, epg) in matched) {
                    m.setEpg(epg)
                }
            }
            Log.i(TAG, "readEPG success")
            true
        } catch (e: Exception) {
            Log.e(TAG, "readEPG", e)
            false
        }
    }

    private suspend fun updateEPG(url: String): Boolean {
        val urls = url.split(",").flatMap { u -> getUrls(u) }

        var success = false
        for (a in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        if (readEPG(response.bodyAlias()!!.byteStream())) {
                            Log.i(TAG, "EPG $a success")
                            success = true
                        }
                    } else {
                        Log.e(TAG, "EPG $a ${response.codeAlias()}")
                    }
                } catch (e: Exception) {
//                    Log.e(TAG, "EPG $a error", e)
                    Log.e(TAG, "EPG $a error")
                }
            }

            if (success) {
                break
            }
        }

        return success
    }

    private suspend fun importFromUrl(url: String, id: String = "", silent: Boolean = false) {
        val urls = getUrls(url).map { Pair(it, url) }

        var err = 0
        var shouldBreak = false
        for ((a, b) in urls) {
            Log.i(TAG, "request $a")
            withContext(Dispatchers.IO) {
                try {
                    val request = okhttp3.Request.Builder().url(a).build()
                    val response = HttpClient.okHttpClient.newCall(request).execute()

                    if (response.isSuccessful) {
                        val str = response.bodyAlias()?.string() ?: ""
                        withContext(Dispatchers.Main) {
                            tryStr2Channels(str, null, b, id, silent)
                        }
                        err = 0
                        shouldBreak = true
                    } else {
                        Log.e(TAG, "Request status ${response.codeAlias()}")
                        err = R.string.channel_status_error
                    }
                } catch (e: JsonSyntaxException) {
                    e.printStackTrace()
                    Log.e(TAG, "JSON Parse Error", e)
                    err = R.string.channel_format_error
                    shouldBreak = true
                } catch (e: NullPointerException) {
                    e.printStackTrace()
                    Log.e(TAG, "Null Pointer Error", e)
                    err = R.string.channel_read_error
                } catch (e: Exception) {
                    e.printStackTrace()
                    Log.e(TAG, "Request error $e")
                    err = R.string.channel_request_error
                }
            }
            if (shouldBreak) break
        }

        if (err != 0) {
            err.showToast()
        }
    }

    fun reset(context: Context) {
        val str = context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
            .use { it.readText() }

        try {
            str2Channels(str)
        } catch (e: Exception) {
            e.printStackTrace()
            R.string.channel_read_error.showToast()
        }
    }

    fun importFromUri(uri: Uri, id: String = "") {
        if (uri.scheme == "file") {
            val file = uri.toFile()
            Log.i(TAG, "file $file")
            val str = if (file.exists()) {
                file.readText()
            } else {
                R.string.file_not_exist.showToast()
                return
            }

            tryStr2Channels(str, file, uri.toString(), id)
        } else {
            viewModelScope.launch {
                importFromUrl(uri.toString(), id)
            }
        }
    }

    fun tryStr2Channels(str: String, file: File?, url: String, id: String = "", silent: Boolean = false) {
        try {
            redirectingToLiveUrl = false
            if (str2Channels(str)) {
                if (redirectingToLiveUrl) {
                    // TVBox JSON：真实频道由 importFromUrl 异步导入并写缓存，这里跳过
                    redirectingToLiveUrl = false
                    return
                }
                Log.i(TAG, "write to cacheFile $cacheFile $str")
                cacheFile!!.writeText(str)
                Log.i(TAG, "cacheFile ${getCache()}")
                cacheChannels = str
                if (url.isNotEmpty()) {
                    SP.configUrl = url
                    val source = Source(
                        id = id,
                        uri = url
                    )
                    sources.addSource(
                        source
                    )
                }
                // 启动后自动更新源时（initialized=true）：不触发 channelsOk 重播，
                // 避免播放中重建播放器实例导致画面卡顿。
                // groupModel.setChange() 已刷新频道列表 UI，无需重建播放。
                if (!initialized) {
                    _channelsOk.value = true
                }
                if (!silent) {
                    R.string.channel_import_success.showToast()
                }
                Log.i(TAG, "channel import success")
            } else {
                if (!silent) {
                    R.string.channel_import_error.showToast()
                }
                Log.w(TAG, "channel import error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryStr2Channels", e)
            file?.deleteOnExit()
            if (!silent) {
                R.string.channel_read_error.showToast()
            }
        }
    }

    private fun str2Channels(str: String): Boolean {
        var string = str
        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val g = Gua()
        if (g.verify(str)) {
            string = g.decode(str)
        }

        if (string.isEmpty()) {
            Log.w(TAG, "channels is empty")
            return false
        }

        // TVBox: 整体 base64 编码的直播源自动解码
        string = decodeBase64IfNeeded(string).trim()

        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val list: List<TV>

        when (string[0]) {
            '{' -> {
                // TVBox JSON 配置：{"lives":[{"name":"...","url":"http://xxx/live.txt"}]}
                try {
                    val obj = JsonParser.parseString(string).asJsonObject
                    val lives = obj.getAsJsonArray("lives")
                    if (lives == null || lives.size() == 0) {
                        Log.w(TAG, "TVBox config has no lives")
                        return false
                    }
                    var liveUrl = ""
                    val extraUrls = mutableListOf<String>()
                    for (e in lives) {
                        val live = e.asJsonObject
                        val u = live.get("url")?.asString ?: continue
                        if (u.isEmpty()) continue
                        if (liveUrl.isEmpty()) {
                            liveUrl = u
                            live.get("epg")?.asString?.let { epgUrl = it }
                        } else {
                            extraUrls.add(u)
                        }
                    }
                    if (liveUrl.isEmpty()) {
                        Log.w(TAG, "TVBox lives has no url")
                        return false
                    }
                    Log.i(TAG, "TVBox live url $liveUrl, extra ${extraUrls.size}")
                    // 其余 lives 也加入源列表，便于在源管理中切换
                    for (u in extraUrls.reversed()) {
                        sources.addSource(Source(uri = u))
                    }
                    redirectingToLiveUrl = true
                    viewModelScope.launch {
                        importFromUrl(liveUrl)
                    }
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "str2Channels tvbox", e)
                    return false
                }
            }

            '[' -> {
                try {
                    list = gson.fromJson(string, typeTvList)
                    Log.i(TAG, "导入频道 ${list.size} $list")
                } catch (e: Exception) {
                    Log.e(TAG, "str2Channels", e)
                    return false
                }
            }

            '#' -> {
                val lines = string.lines()
                val nameRegex = Regex("""tvg-name="([^"]+)"""")
                val logRegex = Regex("""tvg-logo="([^"]+)"""")
                val numRegex = Regex("""tvg-chno="([^"]+)"""")
                val epgRegex = Regex("""x-tvg-url="([^"]+)"""")
                val groupRegex = Regex("""group-title="([^"]+)"""")
                // 回看属性：catchup="append/default/shift" catchup-source="...${(b)yyyyMMddHHmmss}..."
                val catchupRegex = Regex("""catchup="([^"]+)"""")
                val catchupSourceRegex = Regex("""catchup-source="([^"]+)"""")

                // #EXTM3U 头部的全局回看设置（频道未单独声明时继承）
                var globalCatchup: String? = null
                var globalCatchupSource: String? = null

                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<TV>>()

                var tv = TV()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) {
                        continue
                    }
                    if (trimmedLine.startsWith("#EXTM3U")) {
                        epgUrl = epgRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                        globalCatchup = catchupRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                        globalCatchupSource =
                            catchupSourceRegex.find(trimmedLine)?.groupValues?.get(1)?.trim()
                    } else if (trimmedLine.startsWith("#EXTINF")) {
                        val key = tv.group + tv.name
                        if (key.isNotEmpty()) {
                            tvMap[key] =
                                if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
                        }
                        tv = TV()
                        val info = trimmedLine.split(",")
                        tv.title = info.last().trim()
                        var name = nameRegex.find(info.first())?.groupValues?.get(1)?.trim()
                        tv.name = if (name.isNullOrEmpty()) tv.title else name
                        tv.logo = logRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                        tv.number =
                            numRegex.find(info.first())?.groupValues?.get(1)?.trim()?.toInt() ?: -1
                        tv.group = groupRegex.find(info.first())?.groupValues?.get(1)?.trim() ?: ""
                        // 频道级 catchup 优先，否则继承全局
                        tv.catchup = catchupRegex.find(info.first())?.groupValues?.get(1)?.trim()
                            ?: globalCatchup
                        tv.catchupSource =
                            catchupSourceRegex.find(info.first())?.groupValues?.get(1)?.trim()
                                ?: globalCatchupSource
                    } else if (trimmedLine.startsWith("#EXTVLCOPT:http-")) {
                        val keyValue =
                            trimmedLine.substringAfter("#EXTVLCOPT:http-").split("=", limit = 2)
                        if (keyValue.size == 2) {
                            tv.headers = if (tv.headers == null) {
                                mapOf<String, String>(keyValue[0] to keyValue[1])
                            } else {
                                tv.headers!!.toMutableMap().apply {
                                    this[keyValue[0]] = keyValue[1]
                                }
                            }
                        }
                    } else if (!trimmedLine.startsWith("#")) {
                        // TVBox m3u: 剥离 url$备注 后缀
                        val uri = trimmedLine.substringBefore('$').trim()
                        if (uri.isEmpty()) {
                            continue
                        }
                        tv.uris = if (tv.uris.isEmpty()) {
                            listOf(uri)
                        } else {
                            tv.uris.toMutableList().apply {
                                this.add(uri)
                            }
                        }
                    }
                }
                val key = tv.group + tv.name
                if (key.isNotEmpty()) {
                    tvMap[key] = if (!tvMap.containsKey(key)) listOf(tv) else tvMap[key]!! + tv
                }
                for ((_, tv) in tvMap) {
                    val uris = tv.map { t -> t.uris }.flatten()
                    val t0 = tv[0]
                    val t1 = TV(
                        -1,
                        t0.name,
                        t0.title,
                        "",
                        t0.logo,
                        "",
                        uris,
                        0,
                        t0.headers,
                        t0.group,
                        SourceType.UNKNOWN,
                        t0.number,
                        emptyList(),
                        t0.catchup,
                        t0.catchupSource,
                    )
                    l.add(t1)
                }
                list = l
                Log.i(TAG, "导入频道 ${list.size} $list")
            }

            else -> {
                val lines = string.lines()
                var group = ""
                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<String>>()
                for (line in lines) {
                    // 容错：全角逗号/冒号统一为半角
                    val trimmedLine = line.trim().replace('，', ',').replace('：', ':')
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            if (!trimmedLine.contains(",")) {
                                continue
                            }
                            val arr = trimmedLine.split(',').map { it.trim() }
                            val title = arr.first().trim()
                            if (title.isEmpty()) {
                                continue
                            }
                            // TVBox txt: 支持 url1#url2#url3 多线路，及 url$备注 后缀
                            val uris = arr.drop(1)
                                .flatMap { it.split('#') }
                                .map { it.substringBefore('$').trim() }
                                .filter { it.isNotEmpty() }

                            val key = group + title
                            if (!tvMap.containsKey(key)) {
                                tvMap[key] = listOf(group)
                            }
                            tvMap[key] = tvMap[key]!! + uris
                        }
                    }
                }
                for ((title, uris) in tvMap) {
                    // 列表首元素是分组名，必须剥离（原代码 uris.drop(1) 返回值被丢弃，
                    // 导致分组名混入播放地址，每个频道都会先重试一次无效地址）
                    val channelGroup = uris.first()
                    val realUris = uris.drop(1)
                    if (realUris.isEmpty()) {
                        continue
                    }
                    val tv = TV(
                        -1,
                        "",
                        title.removePrefix(channelGroup),
                        "",
                        "",
                        "",
                        realUris,
                        0,
                        emptyMap(),
                        channelGroup,
                        SourceType.UNKNOWN,
                        -1,
                        emptyList(),
                    )

                    l.add(tv)
                }
                list = l
                Log.d(TAG, "导入频道 $list")
                Log.i(TAG, "导入频道 ${list.size}")
            }
        }

        groupModel.initTVGroup()

        val map: MutableMap<String, MutableList<TVModel>> = mutableMapOf()
        for (v in list) {
            if (v.group !in map) {
                map[v.group] = mutableListOf()
            }
            map[v.group]?.add(TVModel(v))
        }

        val listModelNew: MutableList<TVModel> = mutableListOf()
        var groupIndex = 2
        var id = 0
        for ((k, v) in map) {
            val listTVModel = TVListModel(k.ifEmpty { "未知" }, groupIndex)
            for ((listIndex, v1) in v.withIndex()) {
                v1.tv.id = id
                v1.setLike(SP.getLike(id))
                v1.setGroupIndex(groupIndex)
                v1.listIndex = listIndex
                listTVModel.addTVModel(v1)
                listModelNew.add(v1)
                id++
            }
            groupModel.addTVListModel(listTVModel)
            groupIndex++
        }

        listModel = listModelNew

        // 全部频道
        groupModel.tvGroupValue[1].setTVListModel(listModel)

        if (string != cacheChannels && g.encode(string) != cacheChannels) {
            groupModel.initPosition()
        }

        groupModel.setChange()

        viewModelScope.launch {
            // 延迟预载 logo：避免启动时与起播抢带宽
            kotlinx.coroutines.delay(10_000)
            preloadLogo()
        }

        return true
    }

    /**
     * TVBox 直播源常整体 base64 编码。若内容像 base64 且解码后为可读文本则返回解码结果。
     */
    private fun decodeBase64IfNeeded(str: String): String {
        val s = str.trim()
        if (s.isEmpty()) return str
        // 已是明文格式则跳过
        val c = s[0]
        if (c == '#' || c == '[' || c == '{' || s.contains(",http") || s.contains("://")) {
            return str
        }
        if (!Regex("^[A-Za-z0-9+/=\\r\\n]+$").matches(s)) {
            return str
        }
        return try {
            val decoded = Base64.decode(s, Base64.DEFAULT).toString(Charsets.UTF_8)
            if (decoded.contains("://") || decoded.startsWith("#EXTM3U") || decoded.contains("#genre#")) {
                Log.i(TAG, "base64 decoded channels")
                decoded
            } else {
                str
            }
        } catch (e: Exception) {
            str
        }
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
        const val CACHE_EPG = "epg.xml"
        val DEFAULT_CHANNELS_FILE = R.raw.channels
    }
}