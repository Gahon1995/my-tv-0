package com.lizongying.mytv0

import android.app.DownloadManager
import android.app.DownloadManager.Request
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lizongying.mytv0.data.RemoteUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class UpdateManager(
    private var context: Context,
    private var versionCode: Long
) :
    ConfirmationFragment.ConfirmationListener {

    private var downloadReceiver: DownloadReceiver? = null
    private var pendingUpdate: RemoteUpdate? = null

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
            updateUI(message, hasUpdate)
        }
    }

    private fun updateUI(text: String, update: Boolean) {
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

        val downloadManager =
            context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request =
            Request(Uri.parse(apkUrl))
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.mkdirs()
        Log.i(TAG, "save dir ${Environment.DIRECTORY_DOWNLOADS}")
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            apkName
        )
        request.setTitle("${context.getString(R.string.app_name)} ${update.version_name}")
        request.setNotificationVisibility(Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setAllowedOverRoaming(false)
        request.setMimeType("application/vnd.android.package-archive")

        // 获取下载任务的引用
        val downloadReference = downloadManager.enqueue(request)

        // 重复点击时先注销旧的 receiver，避免重复注册
        downloadReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { Log.e(TAG, "unregister old receiver", e) }
            downloadReceiver = null
        }

        downloadReceiver = DownloadReceiver(context, apkName, downloadReference)

        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(downloadReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(downloadReceiver, intentFilter)
        }
    }

    private class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadReference: Long
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            Log.i(TAG, "reference $reference")

            if (reference == downloadReference) {
                val downloadManager =
                    context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadReference)
                val cursor = downloadManager.query(query)
                if (cursor != null && cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (statusIndex < 0) {
                        Log.i(TAG, "Download failure")
                        cursor.close()
                        return
                    }
                    val status = cursor.getInt(statusIndex)
                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            installNewVersion()
                        }

                        DownloadManager.STATUS_FAILED -> {
                            Log.i(TAG, "Download failure")
                        }

                        else -> {
                            Log.i(TAG, "Download in progress")
                        }
                    }
                }
            }
        }

        private fun installNewVersion() {
            val apkFile = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                apkFileName
            )
            Log.i(TAG, "apkFile $apkFile")

            if (apkFile.exists()) {
                runCatching {
                    val apkUri = Uri.fromFile(apkFile)
                    Log.i(TAG, "apkUri $apkUri")
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkUri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    context.startActivity(installIntent)
                }.onFailure { Log.e(TAG, "start install error", it) }
            } else {
                Log.e(TAG, "APK file does not exist!")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
    }

    override fun onConfirm() {
        Log.i(TAG, "onConfirm $pendingUpdate")
        pendingUpdate?.let { startDownload(it) }
    }

    override fun onCancel() {
    }

    fun destroy() {
        downloadReceiver?.let {
            try { context.unregisterReceiver(it) } catch (e: Exception) { Log.e(TAG, "unregister receiver", e) }
            downloadReceiver = null
        }
        Log.i(TAG, "destroy downloadReceiver")
    }
}
