import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
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

    // 源 m3u 头部 x-tvg-url 指定的 EPG（持久化于 SP.epgFromSource）；为空表示当前源未指定
    private var epgUrl = SP.epgFromSource

    private lateinit var imageHelper: ImageHelper

    val sources = Sources()

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
            // 源 m3u 指定的 EPG 优先，没有（或拉取失败）才用 SP.epg 当前值兜底
            var success = false
            val primary = epgUrl?.takeIf { it.isNotEmpty() }
            if (!primary.isNullOrEmpty()) {
                success = updateEPG(primary)
            }
            if (!success && !SP.epg.isNullOrEmpty() && SP.epg != primary) {
                updateEPG(SP.epg!!)
            }
        }
    }

    fun updateConfig() {
        viewModelScope.launch {
            RemoteConfigManager.fetchAndApply(this@MainViewModel)
            if (SP.configAutoLoad) {
                SP.configUrl?.let {
                    if (it.startsWith("http")) {
                        Log.i(TAG, "update config url: $it")
                        importFromUrl(it)
                    }
                }
            }
            // EPG 拉取统一收口在这里（apply 与源解析都已完成，用最新地址），
            // 避免启动早期用旧地址抢先拉取；preloadLogo 同样放这里，
            // 确保 SP.logoBaseUrl 已 apply 后再构建台标候选
            updateEPG()
            preloadLogo()
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

        _channelsOk.value = true

        // 不延迟，立即异步拉取远端配置与最新直播源，不阻塞初始起播
        viewModelScope.launch {
            updateConfig()
        }
    }

    suspend fun preloadLogo() {
        if (!this::imageHelper.isInitialized) {
            Log.w(TAG, "imageHelper is not initialized")
            return
        }

        val semaphore = Semaphore(4) // 限制并发数，避免卡顿

        // 找到当前播放频道的位置，优先加载附近频道
        val currentIndex = groupModel.getCurrentList()?.let { list ->
            list.getCurrent()?.tv?.id ?: -1
        } ?: -1

        viewModelScope.launch {
            // 按距离当前播放频道的远近排序
            val sorted = listModel.mapIndexed { idx, tvModel -> Pair(idx, tvModel) }
                .sortedBy { (idx, _) ->
                    if (currentIndex < 0) idx
                    else kotlin.math.abs(idx - currentIndex)
                }

            sorted.map { (_, tvModel) ->
                val name = tvModel.tv.name.ifEmpty { tvModel.tv.title }
                val urls = imageHelper.logoUrlCandidates(name, tvModel.tv.logo)

                async {
                    semaphore.acquire()
                    try {
                        imageHelper.preloadImage(name, urls)
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll()
        }
    }

    suspend fun readEPG(input: InputStream): Boolean = withContext(Dispatchers.IO) {
        try {
            val res = EPGXmlParser().parse(input)

            // 在主线程之外完成磁盘写入（大 JSON），主线程只做内存 setEpg，避免卡顿
            val e1 = mutableMapOf<String, List<EPG>>()
            val toSet = mutableListOf<Pair<TVModel, List<EPG>>>()
            val list = listModel
            for (m in list) {
                val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                if (name.isEmpty()) {
                    continue
                }
                for ((n, epg) in res) {
                    if (name.contains(n, ignoreCase = true)) {
                        e1[name] = epg
                        toSet.add(m to epg)
                        break
                    }
                }
            }

            // 一个频道都没匹配上视为失败：不覆盖已有内存 EPG，也不写空缓存
            // （否则一次"解析成功但匹配不上"会把上次的好缓存冲掉）
            if (toSet.isEmpty()) {
                Log.w(TAG, "readEPG no channel matched")
                return@withContext false
            }
            kotlin.runCatching { cacheEPG.writeText(gson.toJson(e1)) }

            withContext(Dispatchers.Main) {
                for ((m, epg) in toSet) {
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

    private suspend fun readEPG(str: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val res: Map<String, List<EPG>> = gson.fromJson(str, typeEPGMap)

            withContext(Dispatchers.Main) {
                for (m in listModel) {
                    val name = m.tv.name.ifEmpty { m.tv.title }.lowercase()
                    if (name.isEmpty()) {
                        continue
                    }

                    val epg = res[name]
                    if (epg != null) {
                        m.setEpg(epg)
                    }
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
        // 优先用缓存的远程源（如果存在且不为空），否则用内置兜底
        val cached = getCache()
        val str = if (cached.isNotEmpty()) {
            cached
        } else {
            context.resources.openRawResource(DEFAULT_CHANNELS_FILE).bufferedReader()
                .use { it.readText() }
        }

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
            // 提前去重：如果解码后内容与缓存完全一致，跳过整个解析流程，避免无意义的重建播放器
            var string = str
            val g = Gua()
            if (g.verify(str)) {
                string = g.decode(str)
            }
            if (initialized && string == cacheChannels) {
                Log.i(TAG, "remote content unchanged, skip rebuild")
                return
            }

            if (str2Channels(str)) {
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
                if (!silent) {
                    _channelsOk.value = true
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

        if (initialized && string == cacheChannels) {
            Log.w(TAG, "same channels")
            return true
        }

        val list: List<TV>

        when (string[0]) {
            '[' -> {
                // JSON 源不携带 x-tvg-url，清掉可能残留的源指定 EPG
                epgUrl = null
                try {
                    list = gson.fromJson(string, typeTvList)
                    Log.i(TAG, "导入频道 ${list.size} $list")
                } catch (e: Exception) {
                    Log.e(TAG, "str2Channels", e)
                    return false
                }
            }

            '#' -> {
                // 源指定的 EPG 只可能来自 m3u 的 x-tvg-url，先重置再在循环中解析
                epgUrl = null
                val lines = string.lines()
                val nameRegex = Regex("""tvg-name="([^"]+)"""")
                val logRegex = Regex("""tvg-logo="([^"]+)"""")
                val numRegex = Regex("""tvg-chno="([^"]+)"""")
                val epgRegex = Regex("""x-tvg-url="([^"]+)"""")
                val groupRegex = Regex("""group-title="([^"]+)"""")

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
                        tv.uris = if (tv.uris.isEmpty()) {
                            listOf(trimmedLine)
                        } else {
                            tv.uris.toMutableList().apply {
                                this.add(trimmedLine)
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
                    )
                    l.add(t1)
                }
                list = l
                Log.i(TAG, "导入频道 ${list.size} $list")
            }

            else -> {
                // txt 源不携带 x-tvg-url，清掉可能残留的源指定 EPG
                epgUrl = null
                val lines = string.lines()
                var group = ""
                val l = mutableListOf<TV>()
                val tvMap = mutableMapOf<String, List<String>>()
                for (line in lines) {
                    val trimmedLine = line.trim()
                    if (trimmedLine.isNotEmpty()) {
                        if (trimmedLine.contains("#genre#")) {
                            group = trimmedLine.split(',', limit = 2)[0].trim()
                        } else {
                            if (!trimmedLine.contains(",")) {
                                continue
                            }
                            val arr = trimmedLine.split(',').map { it.trim() }
                            val title = arr.first().trim()
                            val uris = arr.drop(1)

                            val key = group + title
                            if (!tvMap.containsKey(key)) {
                                tvMap[key] = listOf(group)
                            }
                            tvMap[key] = tvMap[key]!! + uris
                        }
                    }
                }
                for ((title, uris) in tvMap) {
                    val channelGroup = uris.first();
                    uris.drop(1);
                    val tv = TV(
                        -1,
                        "",
                        title.removePrefix(channelGroup),
                        "",
                        "",
                        "",
                        uris,
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

        // 持久化源指定的 EPG（仅 '#' 分支解析到的 x-tvg-url，其余分支为空），
        // 内容未变化 skip rebuild 时也能在下次启动继续使用
        SP.epgFromSource = epgUrl ?: ""

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
            preloadLogo()
        }

        return true
    }

    companion object {
        private const val TAG = "MainViewModel"
        const val CACHE_FILE_NAME = "channels.txt"
        const val CACHE_EPG = "epg.xml"
        val DEFAULT_CHANNELS_FILE = R.raw.channels
    }
}