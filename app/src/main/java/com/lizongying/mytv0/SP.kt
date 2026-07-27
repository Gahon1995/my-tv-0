package com.lizongying.mytv0


import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.Global.typeSourceList
import com.lizongying.mytv0.data.Source
import io.github.lizongying.Gua

object SP {
    private const val TAG = "SP"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    private const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    private const val KEY_POSITION_GROUP = "position_group"

    private const val KEY_POSITION_SUB = "position_sub"

    private const val KEY_REPEAT_INFO = "repeat_info"

    private const val KEY_CONFIG_URL = "config"

    private const val KEY_CONFIG_AUTO_LOAD = "config_auto_load"

    private const val KEY_CHANNEL = "channel"

    private const val KEY_DEFAULT_LIKE = "default_like"

    private const val KEY_DISPLAY_SECONDS = "display_seconds"

    private const val KEY_SHOW_ALL_CHANNELS = "show_all_channels"

    private const val KEY_COMPACT_MENU = "compact_menu"

    private const val KEY_LIKE = "like"

    private const val KEY_PROXY = "proxy"

    private const val KEY_EPG = "epg"

    private const val KEY_VERSION = "version"

    private const val KEY_LOG_TIMES = "log_times"

    private const val KEY_SOURCES = "sources"

    private const val KEY_SOFT_DECODE = "soft_decode"

    private const val KEY_ELDER_MODE = "elder_mode"

    private const val KEY_GLASS_BLUR = "glass_blur"

    // 远端配置中心（my-tv-server）地址；为空表示不使用远端配置
    private const val KEY_REMOTE_CONFIG_SERVER = "remote_config_server"

    private const val KEY_REMOTE_CONFIG_ETAG = "remote_config_etag"

    // 远端下发的台标基础地址
    private const val KEY_LOGO_BASE_URL = "logo_base_url"

    // 用户本地手动修改过的项：远端配置只作默认值，不覆盖这些项
    private const val KEY_USER_OVERRIDE_EPG = "user_override_epg"

    private const val KEY_USER_OVERRIDE_CONFIG = "user_override_config"

    const val DEFAULT_CHANNEL_REVERSAL = true
    const val DEFAULT_CHANNEL_NUM = false
    const val DEFAULT_TIME = true
    const val DEFAULT_BOOT_STARTUP = false
    const val DEFAULT_CONFIG_URL = "https://tvsources.p.gahon.top/fmml_ipv6.m3u"
    const val DEFAULT_PROXY = ""
    const val DEFAULT_EPG = "https://tvsources.p.gahon.top/epg/112114_xyz.xml"
    const val DEFAULT_CHANNEL = 0
    const val DEFAULT_SHOW_ALL_CHANNELS = false
    const val DEFAULT_COMPACT_MENU = true
    const val DEFAULT_DISPLAY_SECONDS = true
    const val DEFAULT_LOG_TIMES = 10
    const val DEFAULT_SOFT_DECODE = false
    const val DEFAULT_ELDER_MODE = false

    const val DEFAULT_GLASS_BLUR = true

    // 0 favorite; 1 all
    const val DEFAULT_POSITION_GROUP = 1
    const val DEFAULT_POSITION = 0
    const val DEFAULT_REPEAT_INFO = true
    // 默认开启：启动时自动拉取 DEFAULT_CONFIG_URL 的直播源，保持频道列表最新
    const val DEFAULT_CONFIG_AUTO_LOAD = true
    var DEFAULT_SOURCES = ""

    private lateinit var sp: SharedPreferences

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(
            context.getString(R.string.app_name),
            Context.MODE_PRIVATE
        )

        context.resources.openRawResource(R.raw.sources).bufferedReader()
            .use {
                val str = it.readText()
                if (str.isNotEmpty()) {
                    // 兼容明文与卦象编码两种格式
                    val g = Gua()
                    val text = if (g.verify(str)) g.decode(str) else str
                    DEFAULT_SOURCES = gson.toJson(
                        text.trim().split("\n").map { i ->
                            Source(
                                uri = i.trim()
                            )
                        }, typeSourceList
                    ) ?: ""
                }
            }

        Log.i(TAG, "group position $positionGroup")
        Log.i(TAG, "list position $position")
        Log.i(TAG, "default channel $channel")
        Log.i(TAG, "proxy $proxy")
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, DEFAULT_CHANNEL_REVERSAL)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, DEFAULT_CHANNEL_NUM)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, DEFAULT_TIME)
        set(value) = sp.edit().putBoolean(KEY_TIME, value).apply()

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, DEFAULT_BOOT_STARTUP)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var positionGroup: Int
        get() = sp.getInt(KEY_POSITION_GROUP, DEFAULT_POSITION_GROUP)
        set(value) = sp.edit().putInt(KEY_POSITION_GROUP, value).apply()

    var position: Int
        get() = sp.getInt(KEY_POSITION, DEFAULT_POSITION)
        set(value) = sp.edit().putInt(KEY_POSITION, value).apply()

    var positionSub: Int
        get() = sp.getInt(KEY_POSITION_SUB, 0)
        set(value) = sp.edit().putInt(KEY_POSITION_SUB, value).apply()

    var repeatInfo: Boolean
        get() = sp.getBoolean(KEY_REPEAT_INFO, DEFAULT_REPEAT_INFO)
        set(value) = sp.edit().putBoolean(KEY_REPEAT_INFO, value).apply()

    var configUrl: String?
        get() = sp.getString(KEY_CONFIG_URL, DEFAULT_CONFIG_URL)
        set(value) = sp.edit().putString(KEY_CONFIG_URL, value).apply()

    var configAutoLoad: Boolean
        get() = sp.getBoolean(KEY_CONFIG_AUTO_LOAD, DEFAULT_CONFIG_AUTO_LOAD)
        set(value) = sp.edit().putBoolean(KEY_CONFIG_AUTO_LOAD, value).apply()

    var channel: Int
        get() = sp.getInt(KEY_CHANNEL, DEFAULT_CHANNEL)
        set(value) = sp.edit().putInt(KEY_CHANNEL, value).apply()

    var compactMenu: Boolean
        get() = sp.getBoolean(KEY_COMPACT_MENU, DEFAULT_COMPACT_MENU)
        set(value) = sp.edit().putBoolean(KEY_COMPACT_MENU, value).apply()

    var showAllChannels: Boolean
        get() = sp.getBoolean(KEY_SHOW_ALL_CHANNELS, DEFAULT_SHOW_ALL_CHANNELS)
        set(value) = sp.edit().putBoolean(KEY_SHOW_ALL_CHANNELS, value).apply()

    var defaultLike: Boolean
        get() = sp.getBoolean(KEY_DEFAULT_LIKE, false)
        set(value) = sp.edit().putBoolean(KEY_DEFAULT_LIKE, value).apply()

    var displaySeconds: Boolean
        get() = sp.getBoolean(KEY_DISPLAY_SECONDS, DEFAULT_DISPLAY_SECONDS)
        set(value) = sp.edit().putBoolean(KEY_DISPLAY_SECONDS, value).apply()

    var softDecode: Boolean
        get() = sp.getBoolean(KEY_SOFT_DECODE, DEFAULT_SOFT_DECODE)
        set(value) = sp.edit().putBoolean(KEY_SOFT_DECODE, value).apply()

    var elderMode: Boolean
        get() = sp.getBoolean(KEY_ELDER_MODE, DEFAULT_ELDER_MODE)
        set(value) = sp.edit().putBoolean(KEY_ELDER_MODE, value).apply()

    var glassBlur: Boolean
        get() = sp.getBoolean(KEY_GLASS_BLUR, DEFAULT_GLASS_BLUR)
        set(value) = sp.edit().putBoolean(KEY_GLASS_BLUR, value).apply()

    fun getLike(id: Int): Boolean {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())
        return stringSet?.contains(id.toString()) ?: false
    }

    fun setLike(id: Int, liked: Boolean) {
        val stringSet = sp.getStringSet(KEY_LIKE, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (liked) {
            stringSet.add(id.toString())
        } else {
            stringSet.remove(id.toString())
        }

        sp.edit().putStringSet(KEY_LIKE, stringSet).apply()
    }

    fun deleteLike() {
        sp.edit().remove(KEY_LIKE).apply()
    }

    var proxy: String?
        get() = sp.getString(KEY_PROXY, DEFAULT_PROXY)
        set(value) = sp.edit().putString(KEY_PROXY, value).apply()

    var epg: String?
        get() = sp.getString(KEY_EPG, DEFAULT_EPG)
        set(value) = sp.edit().putString(KEY_EPG, value).apply()

    var version: String?
        get() = sp.getString(KEY_VERSION, "")
        set(value) = sp.edit().putString(KEY_VERSION, value).apply()

    var logTimes: Int
        get() = sp.getInt(KEY_LOG_TIMES, DEFAULT_LOG_TIMES)
        set(value) = sp.edit().putInt(KEY_LOG_TIMES, value).apply()

    var sources: String?
        get() = sp.getString(KEY_SOURCES, DEFAULT_SOURCES)
        set(value) = sp.edit().putString(KEY_SOURCES, value).apply()

    var remoteConfigServer: String?
        get() = sp.getString(KEY_REMOTE_CONFIG_SERVER, "")
        set(value) = sp.edit().putString(KEY_REMOTE_CONFIG_SERVER, value).apply()

    var remoteConfigEtag: String?
        get() = sp.getString(KEY_REMOTE_CONFIG_ETAG, "")
        set(value) = sp.edit().putString(KEY_REMOTE_CONFIG_ETAG, value).apply()

    var logoBaseUrl: String?
        get() = sp.getString(KEY_LOGO_BASE_URL, "")
        set(value) = sp.edit().putString(KEY_LOGO_BASE_URL, value).apply()

    var userOverrideEpg: Boolean
        get() = sp.getBoolean(KEY_USER_OVERRIDE_EPG, false)
        set(value) = sp.edit().putBoolean(KEY_USER_OVERRIDE_EPG, value).apply()

    var userOverrideConfig: Boolean
        get() = sp.getBoolean(KEY_USER_OVERRIDE_CONFIG, false)
        set(value) = sp.edit().putBoolean(KEY_USER_OVERRIDE_CONFIG, value).apply()

    /** 恢复默认：清除用户覆盖标记，让远端/内置默认重新生效 */
    fun clearUserOverrides() {
        userOverrideEpg = false
        userOverrideConfig = false
        remoteConfigEtag = ""
    }
}