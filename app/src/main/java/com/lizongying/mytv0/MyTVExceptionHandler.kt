package com.lizongying.mytv0

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

class MyTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val crashInfo =
            "APP: ${context.appVersionName}, PRODUCT: ${Build.PRODUCT}, DEVICE: ${Build.DEVICE}, SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}, BOARD: ${Build.BOARD}, MANUFACTURER: ${Build.MANUFACTURER}, MODEL: ${Build.MODEL}, VERSION: ${Build.VERSION.SDK_INT}\nThread: ${t.name}\nException: ${e.message}\nStackTrace: ${
                Log.getStackTraceString(
                    e
                )
            }\n"

        runBlocking {
            launch {
                saveCrashInfoToFile(crashInfo)

                withContext(Dispatchers.Main) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(1)
                }
            }
        }
    }

    private suspend fun saveCrashInfoToFile(crashInfo: String) {
        if (isLimit()) {
            Log.e(TAG, crashInfo)
        } else {
            try {
                saveLog(crashInfo)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isLimit(): Boolean {
        if (context.appVersionName != SP.version) {
            SP.version = context.appVersionName
            SP.logTimes = SP.DEFAULT_LOG_TIMES
            return false
        } else {
            SP.logTimes--
            return SP.logTimes < 0
        }
    }

    private suspend fun saveLog(crashInfo: String) {
        withContext(Dispatchers.IO) {
            try {
                // 不再上报到原作者服务器（lyrics.run），改为仅保存到本地文件，
                // 便于排查问题且保护隐私（原上报内容含设备型号等信息）
                val dir = File(context.filesDir, "crash")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = File(dir, "crash_${System.currentTimeMillis()}.log")
                file.writeText(crashInfo)

                // 只保留最近 10 个崩溃日志
                dir.listFiles()?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
                Log.i(TAG, "crash log saved to $file")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        private const val TAG = "MyTVException"
    }
}