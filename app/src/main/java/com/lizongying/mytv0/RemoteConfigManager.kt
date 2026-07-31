package com.lizongying.mytv0

import MainViewModel
import android.content.Context
import android.util.Log
import com.lizongying.mytv0.data.Global.gson
import com.lizongying.mytv0.data.RemoteConfig
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"
    private const val CACHE_FILE_NAME = "remote_config.json"

    var current: RemoteConfig? = null
        private set

    private var cacheFile: File? = null

    fun init(context: Context) {
        cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (current == null) current = loadCache()
    }

    fun serverBaseUrl(): String? {
        var server = SP.remoteConfigServer?.trim()?.trimEnd('/') ?: return null
        if (server.isEmpty()) return null
        if (!server.startsWith("http://") && !server.startsWith("https://"))
            server = "http://$server"
        return server
    }

    suspend fun fetchAndApply(viewModel: MainViewModel) {
        val base = serverBaseUrl() ?: return
        val config = fetch(base) ?: current ?: return
        current = config
        withContext(Dispatchers.Main) { apply(viewModel, config) }
    }

    private suspend fun fetch(base: String): RemoteConfig? = withContext(Dispatchers.IO) {
        try {
            val builder = okhttp3.Request.Builder().url("$base/api/v1/config")
            SP.remoteConfigEtag?.takeIf { it.isNotEmpty() }?.let { builder.header("If-None-Match", it) }
            val response = HttpClient.okHttpClient.newCall(builder.build()).execute()
            response.use {
                when {
                    it.codeAlias() == 304 -> { Log.i(TAG, "remote config not modified"); loadCache() }
                    it.isSuccessful -> {
                        val body = it.bodyAlias()?.string() ?: return@withContext null
                        val config = gson.fromJson(body, RemoteConfig::class.java)
                        cacheFile?.writeText(body)
                        SP.remoteConfigEtag = it.header("ETag") ?: ""
                        Log.i(TAG, "remote config fetched, version ${config.config_version}")
                        config
                    }
                    else -> { Log.e(TAG, "fetch remote config failed: ${it.codeAlias()}"); null }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "fetch remote config error: ${e.message}"); null }
    }

    private fun loadCache(): RemoteConfig? = try {
        cacheFile?.takeIf { it.exists() }?.readText()?.takeIf { it.isNotEmpty() }
            ?.let { gson.fromJson(it, RemoteConfig::class.java) }
    } catch (e: Exception) { Log.e(TAG, "loadCache", e); null }

    private fun apply(viewModel: MainViewModel, config: RemoteConfig) {
        config.epg_url?.takeIf { it.isNotEmpty() }?.let {
            if (!SP.userOverrideEpg && SP.epg != it) { Log.i(TAG, "apply remote epg $it"); SP.epg = it }
        }
        SP.logoBaseUrl = config.logo_base_url?.takeIf { it.isNotEmpty() } ?: SP.logoBaseUrl
        val uris = config.live_sources.orEmpty().map { it.url }.filter { it.isNotEmpty() }
        if (uris.isNotEmpty()) {
            viewModel.sources.mergeRemoteSources(uris)
            if (!SP.userOverrideConfig && SP.configUrl != uris.first()) {
                Log.i(TAG, "apply remote config url ${uris.first()}")
                SP.configUrl = uris.first()
            }
        }
    }
}
