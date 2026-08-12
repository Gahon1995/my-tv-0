package com.lizongying.mytv0

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.data.RemoteUpdate
import com.lizongying.mytv0.requests.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit


class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) :
    ConfirmationFragment.ConfirmationListener,
    DownloadDialogFragment.Listener {

    private enum class DownloadState { IDLE, DOWNLOADING, COMPLETED, FAILED }

    private var pendingUpdate: RemoteUpdate? = null
    private var downloadJob: Job? = null
    private var downloadDialog: DownloadDialogFragment? = null
    private var downloadState = DownloadState.IDLE
    private var downloadFailedReason: String? = null
    private var currentApkName: String? = null

    /** 从远端配置(my-tv-server)读取更新信息。若未配置远端则返回 null。 */
    private suspend fun getRemoteUpdate(): RemoteUpdate? {
        // 先强制刷新一次远端配置，确保拿到最新的 update 字段
        withContext(Dispatchers.IO) {
            RemoteConfigManager.refreshNow()
        }
        return RemoteConfigManager.current?.update?.takeIf {
            !it.apk_url.isNullOrEmpty() && it.version_code != null
        }
    }

    fun checkAndUpdate() {
        Log.i(TAG, "checkAndUpdate")
        "正在检查更新…".showToast()
        CoroutineScope(Dispatchers.Main).launch {
            var message = "未获取到更新信息"
            var hasUpdate = false
            var newVersion: RemoteUpdate? = null
            try {
                val update = getRemoteUpdate()
                Log.i(TAG, "remote update: ${update?.version_name} ${update?.version_code}, local $versionCode")
                if (update != null) {
                    if (update.version_code!!.toLong() > versionCode) {
                        // 有更新：展示版本 + 更新说明 + 下载链接，由用户确认
                        hasUpdate = true
                        newVersion = update
                        message = buildString {
                            append("检测到新版本 ${update.version_name}")
                            update.changelog?.takeIf { it.isNotBlank() }?.let {
                                append("\n\n更新说明：\n$it")
                            }
                            append("\n\n下载链接：\n${update.apk_url}")
                        }
                    } else {
                        message = "已是最新版本，不需要更新"
                    }
                } else {
                    message = if (RemoteConfigManager.serverBaseUrl().isNullOrEmpty()) {
                        "未配置远程服务器，无法检查更新"
                    } else {
                        "未获取到更新信息"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error occurred: ${e.message}", e)
                message = "检查更新失败：${e.message}"
            }
            pendingUpdate = newVersion
            // 只有检测到有更新时才弹窗，其余情况用 Toast 提示
            if (hasUpdate) {
                updateUI(message, true)
            } else {
                message.showToast()
            }
        }
    }

    private fun updateUI(text: String, update: Boolean) {
        if (downloadState == DownloadState.DOWNLOADING) {
            "正在下载更新中…".showToast()
            return
        }
        val dialog = ConfirmationFragment(this@UpdateManager, text, update)
        dialog.show((context as FragmentActivity).supportFragmentManager, TAG)
    }

    private fun startDownload(update: RemoteUpdate) {
        val apkName = update.apk_name
        val apkUrl = update.apk_url
        if (apkName.isNullOrEmpty() || apkUrl.isNullOrEmpty()) {
            "下载地址缺失，无法下载".showToast()
            return
        }
        if (downloadState == DownloadState.DOWNLOADING) {
            "正在下载中…".showToast()
            return
        }

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: run {
                "无法获取下载目录".showToast()
                return
            }
        dir.mkdirs()
        val finalFile = File(dir, apkName)

        // 已下载过完整文件 → 跳过下载直接进入安装态(apk_name 带版本号，不会撞旧文件)
        if (finalFile.exists() && finalFile.length() > 0) {
            Log.i(TAG, "apk already exists, skip download: $finalFile")
            currentApkName = apkName
            downloadState = DownloadState.COMPLETED
            showDownloadDialog()?.showCompleted()
            return
        }

        // 清理上次残留 .tmp，保证重下覆盖
        File(dir, "$apkName.tmp").delete()
        currentApkName = apkName
        downloadState = DownloadState.DOWNLOADING
        val dialog = showDownloadDialog()
        // 弹窗已存在时立即刷新为下载态；新弹窗由 onDialogAttached 回灌状态
        dialog?.showDownloading(update.version_name)

        downloadJob = CoroutineScope(Dispatchers.Main).launch {
            // github 链接会生成代理镜像列表，逐个尝试；其余链接只有原地址
            val urls = Utils.getUrls(apkUrl)
            var lastError: Exception? = null
            for (url in urls) {
                if (!isActive) return@launch
                try {
                    downloadFrom(url, dir, apkName, finalFile)
                    onDownloadSucceeded()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "download url failed: $url", e)
                }
            }
            onDownloadFailed(lastError ?: IOException("所有下载地址均失败"))
        }
    }

    /** 流式下载到 .tmp 文件，完成后 rename 为正式文件名。全部在 IO 线程执行。 */
    private suspend fun downloadFrom(url: String, dir: File, apkName: String, finalFile: File) {
        withContext(Dispatchers.IO) {
            // newBuilder 与原 client 共享连接池/Dispatcher/DNS，不影响全局直播流；
            // 默认 readTimeout 是"两次读之间"超时，慢速镜像下容易误杀，放宽到 120s
            val client = HttpClient.okHttpClient.newBuilder()
                .readTimeout(120, TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw IOException("响应体为空")
                val total = body.contentLength()
                val tmpFile = File(dir, "$apkName.tmp")
                var downloaded = 0L
                var lastPct = -1
                var blockCount = 0L
                body.source().use { source ->
                    tmpFile.outputStream().use { out ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            val read = source.read(buffer, 0, buffer.size)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            blockCount++
                            if (!isActive) throw CancellationException()
                            // 节流：百分比变化或每 32 块刷新一次弹窗
                            val pct = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                            if (pct != lastPct || blockCount % PROGRESS_REFRESH_BLOCKS == 0L) {
                                lastPct = pct
                                withContext(Dispatchers.Main) {
                                    downloadDialog?.showProgress(
                                        pct, downloaded, total.takeIf { it > 0 }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // 全部读完 → 原子落位：先删旧文件再 rename，避免半成品文件被误认
            finalFile.delete()
            if (!File(dir, "$apkName.tmp").renameTo(finalFile)) {
                throw IOException("临时文件重命名失败")
            }
        }
    }

    private fun onDownloadSucceeded() {
        downloadState = DownloadState.COMPLETED
        if (downloadDialog?.isAdded == true) {
            downloadDialog?.showCompleted()
        } else {
            "下载完成".showToast()
        }
        Log.i(TAG, "download completed: $currentApkName")
    }

    private fun onDownloadFailed(e: Exception) {
        downloadState = DownloadState.FAILED
        downloadFailedReason = e.message ?: "未知错误"
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
            currentApkName?.let { File(dir, "$it.tmp").delete() }
        }
        if (downloadDialog?.isAdded == true) {
            downloadDialog?.showFailed(downloadFailedReason!!)
        } else {
            "下载失败：$downloadFailedReason".showToast()
        }
        Log.e(TAG, "download failed: $downloadFailedReason", e)
    }

    private fun installNewVersion() {
        val apkName = currentApkName ?: return
        val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName)
        Log.i(TAG, "apkFile $apkFile")

        if (!apkFile.exists()) {
            "安装文件不存在，请重新下载".showToast()
            downloadState = DownloadState.IDLE
            return
        }

        // 安装 APK 需要 "安装未知应用" 权限；未开启时引导用户去系统设置开启
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "REQUEST_INSTALL_PACKAGES not granted, guide to settings")
            "请允许安装未知应用后再安装".showToast()
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.onFailure { Log.e(TAG, "open install-source settings failed", it) }
            return
        }

        runCatching {
            // 关闭进度弹窗，安装器返回后不再依赖旧窗口
            downloadDialog?.dismiss()
            downloadDialog = null
            // Android 7.0 以下系统安装器不支持 content:// URI，退回 file://
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", apkFile
                )
            } else {
                Uri.fromFile(apkFile)
            }
            Log.i(TAG, "apkUri $apkUri")
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        }.onFailure {
            Log.e(TAG, "start install error", it)
            "无法打开安装器：${it.message}".showToast()
        }
    }

    private fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { dir ->
            currentApkName?.let { File(dir, "$it.tmp").delete() }
        }
        downloadState = DownloadState.IDLE
        downloadDialog?.dismiss()
        downloadDialog = null
        "已取消下载".showToast()
    }

    private fun showDownloadDialog(): DownloadDialogFragment? {
        downloadDialog?.let { if (it.isAdded) return it }
        val activity = context as? FragmentActivity ?: return null
        return DownloadDialogFragment.show(activity.supportFragmentManager, this).also {
            downloadDialog = it
        }
    }

    override fun onConfirm() {
        Log.i(TAG, "onConfirm $pendingUpdate")
        pendingUpdate?.let { startDownload(it) }
    }

    override fun onCancel() {
    }

    // ---- DownloadDialogFragment.Listener ----

    override fun onCancelDownload() {
        Log.i(TAG, "onCancelDownload")
        cancelDownload()
    }

    override fun onRetryDownload() {
        Log.i(TAG, "onRetryDownload")
        pendingUpdate?.let { startDownload(it) }
    }

    override fun onInstall() {
        Log.i(TAG, "onInstall")
        installNewVersion()
    }

    override fun onDialogDismissed() {
        downloadDialog = null
    }

    override fun onDialogAttached(dialog: DownloadDialogFragment) {
        if (downloadState == DownloadState.IDLE) {
            dialog.dismiss()
            downloadDialog = null
            return
        }
        downloadDialog = dialog
        // 弹窗重建后回灌当前状态
        when (downloadState) {
            DownloadState.DOWNLOADING -> dialog.showDownloading(pendingUpdate?.version_name)
            DownloadState.COMPLETED -> dialog.showCompleted()
            DownloadState.FAILED -> dialog.showFailed(downloadFailedReason ?: "未知错误")
            DownloadState.IDLE -> {
            }
        }
    }

    fun destroy() {
        downloadJob?.cancel()
        downloadJob = null
        downloadDialog?.dismiss()
        downloadDialog = null
        Log.i(TAG, "destroy")
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val DOWNLOAD_BUFFER_SIZE = 8192
        private const val PROGRESS_REFRESH_BLOCKS = 32L
    }
}
