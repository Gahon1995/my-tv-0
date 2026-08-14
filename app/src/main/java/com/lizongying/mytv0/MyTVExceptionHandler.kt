package com.lizongying.mytv0

import android.content.Context
import android.os.Build
import android.util.Log
import kotlin.system.exitProcess

class MyTVExceptionHandler(val context: Context) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        val crashInfo =
            "APP: ${context.appVersionName}, PRODUCT: ${Build.PRODUCT}, DEVICE: ${Build.DEVICE}, SUPPORTED_ABIS: ${Build.SUPPORTED_ABIS.joinToString()}, BOARD: ${Build.BOARD}, MANUFACTURER: ${Build.MANUFACTURER}, MODEL: ${Build.MODEL}, VERSION: ${Build.VERSION.SDK_INT}\nThread: ${t.name}\nException: ${e.message}\nStackTrace: ${
                Log.getStackTraceString(
                    e
                )
            }\n"

        // 直接在崩溃线程（通常是主线程）同步打日志后退出。
        // 此前 runBlocking + withContext(Dispatchers.Main) 会让主线程等自己空闲而永久死锁，
        // 表现为崩溃后进程卡死（ANR）而不是立即退出。
        Log.e(TAG, crashInfo)
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    companion object {
        private const val TAG = "MyTVException"
    }
}