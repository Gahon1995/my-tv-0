package com.lizongying.mytv0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Log
import com.bumptech.glide.Glide
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap


class ImageHelper(private val context: Context) {
    private val cacheDir = context.cacheDir

    private var dir: File = File(cacheDir, LOGO)
    private val files = ConcurrentHashMap<String, File>()

    // 正在后台下载中的 logo（key = 频道名），避免重复并发下载
    private val downloading: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // 独立短超时 client，专用于 logo 下载，避免慢图床长期阻塞。
    // 由 HttpClient.logoClient 提供（保留 trust-all TLS 与 tls12；不污染全局超时）。
    private val logoClient: OkHttpClient = HttpClient.logoClient

    init {
        if (!dir.exists()) {
            dir.mkdir()
        }
        dir.listFiles()?.forEach { file ->
            files[file.name] = file
        }
    }

    // ==================== 台标候选 URL 池（单一权威） ====================

    /** fecemmingming 图库原始地址锚点：raw.githubusercontent 上的同名台标图。 */
    private val FANMINGMING_RAW = "https://raw.githubusercontent.com/fanmingming/live/main/tv"

    /** 实测可用（2026-08 网络环境）的 raw.githubusercontent 代理镜像，按速度排序。 */
    private val RAW_MIRRORS = listOf(
        "https://gh.llkk.cc/",
        "https://ghproxy.net/",
        "https://ghproxy.cn/",
    )

    /** fanmingming 图床原站不可达时，把同一文件的 github 镜像也并入候选池。 */
    private fun mirrorCandidates(raw: String): List<String> {
        val out = mutableListOf(raw)
        if (raw.startsWith("https://live.fanmingming.cn/")) {
            val file = raw.removePrefix("https://live.fanmingming.cn/") // tv/CCTV1.png
            RAW_MIRRORS.forEach { out += "$it$FANMINGMING_RAW/$file" }
        }
        return out
    }

    /**
     * 为某一频道构建完整的台标候选 URL 列表（去重，按优先级排序）：
     * 1. 用户配置的 logo_base_url 前缀(<name>.png)
     * 2. 频道自带 tv.logo（含 fanmingming 原站不可达时的镜像兜底）
     * 3. fanmingming github raw（无论 url 是什么，作为二级权威兜底）
     * 4. 对 raw.githubusercontent 类 URL 套实测可用的代理镜像
     *
     * @param key  频道名（name，取不到则用 title）
     * @param url  频道自带 tvg-logo（可能为空）
     */
    fun logoUrlCandidates(key: String, url: String): List<String> {
        val out = mutableListOf<String>()
        val logoBase = SP.logoBaseUrl?.trim()?.trimEnd('/') ?: ""
        if (logoBase.isNotEmpty()) {
            out += "$logoBase/$key.png"
        }
        if (url.isNotEmpty()) {
            out += mirrorCandidates(url)
        }
        out += "$FANMINGMING_RAW/$key.png"
        // 给所有 raw.githubusercontent URL 追加代理镜像（去重）
        val raws = out.filter { it.startsWith("https://raw.githubusercontent.com") }.distinct()
        for (r in raws) {
            RAW_MIRRORS.forEach { out += "$it$r" }
        }
        return out.distinct()
    }

    // ==================== 下载 ====================

    /** 合法台标图像的最小字节数下限，过滤 404 错误页/空文件/占位图污染缓存。 */
    private val MIN_LOGO_BYTES = 500L

    private suspend fun downloadImage(url: String, file: File): Boolean {
        return withContext(Dispatchers.IO) {
            var tmp: File? = null
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .build()

                logoClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext false
                    val body = response.bodyAlias()?.byteStream() ?: return@withContext false
                    tmp = File(file.parentFile, file.name + ".tmp")
                    body.copyTo(tmp!!.outputStream())
                }
                // 字节数校验：过小视为失败（404 页 / 空 body 污染缓存）
                if (tmp == null || tmp!!.length() < MIN_LOGO_BYTES) {
                    tmp?.delete()
                    return@withContext false
                }
                if (file.exists()) file.delete()
                tmp!!.renameTo(file)
                true
            } catch (e: Exception) {
                Log.e(TAG, "downloadImage error $url")
                try { tmp?.delete() } catch (_: Exception) { }
                false
            }
        }
    }

    // ==================== 预加载 / 展示 ====================

    suspend fun preloadImage(
        key: String,
        urlList: List<String>,
    ) {
        val file = files[key]
        if (file != null) {
            return
        }

        if (urlList.isEmpty()) {
            return
        }

        for (url in urlList) {
            val file = File(cacheDir, "$LOGO/$key")
            if (downloadImage(url, file)) {
                files[file.name] = file
                Log.d(TAG, "downloadImage success $url")
                break
            }
        }
    }

    /**
     * 展示台标。优先读磁盘缓存；无缓存且有真实源时，先用频道号占位图，
     * 随后在后台把候选池逐一下载，成功后落盘并回调重新绑定（解决单 URL 直连
     * 因图床不可达而永久加载不出来的问题）。
     */
    fun loadImage(
        key: String,
        imageView: androidx.appcompat.widget.AppCompatImageView,
        bitmap: Bitmap,
        url: String,
    ) {
        val file = files[key]
        if (file != null) {
            Glide.with(context)
                .load(file)
                .fitCenter()
                .into(imageView)
            return
        }

        // 先用频道号占位图（不阻塞 UI）
        Glide.with(context)
            .load(bitmap)
            .fitCenter()
            .into(imageView)

        // 有真实来源 → 异步兜底下载，成功落盘后重绑
        if (url.isNotEmpty()) {
            triggerAsyncLoad(key, imageView, url)
        }
    }

    private fun triggerAsyncLoad(
        key: String,
        imageView: androidx.appcompat.widget.AppCompatImageView,
        url: String,
    ) {
        if (!downloading.add(key)) {
            return // 已在下载
        }
        AppScope.launch {
            try {
                val candidates = logoUrlCandidates(key, url)
                val ok = withContext(Dispatchers.IO) {
                    for (u in candidates) {
                        val f = File(cacheDir, "$LOGO/$key")
                        if (downloadImage(u, f)) {
                            files[key] = f
                            return@withContext true
                        }
                    }
                    false
                }
                if (ok) {
                    files[key]?.let { cached ->
                        Glide.with(context)
                            .load(cached)
                            .fitCenter()
                            .into(imageView)
                    }
                }
            } finally {
                downloading.remove(key)
            }
        }
    }

    fun clearImage() {
        val dir = File(cacheDir, LOGO)
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    companion object {
        const val TAG = "ImageHelper"
        const val LOGO = "logo"

        // Application 级协程作用域，与 App 同生命周期，用于后台 logo 下载。
        val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
