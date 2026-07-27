package com.lizongying.mytv0

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer

/**
 * 轻量级播放性能监控。
 * 统计解码帧率、渲染掉帧数，辅助定位播放卡顿根因。
 *
 * 用法：
 * ```
 * val tracker = PlaybackPerfTracker()
 * tracker.start()
 * // ... 播放中 ...
 * tracker.stop()
 * Log.i("Perf", tracker.summary())
 * ```
 *
 * 注意：Choreographer 回调在 UI 线程，保持回调内逻辑极简。
 */
class PlaybackPerfTracker {

    private var running = false
    private var startTimeMs = 0L
    private var frameCount = 0
    private var droppedFrameCount = 0
    private var lastFrameTimeNanos = 0L

    // VSYNC 周期约 16.67ms (60fps)，超过 2 个周期算掉帧
    private val dropThresholdNanos = 34_000_000L

    private val handler = Handler(Looper.getMainLooper())

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return

            if (lastFrameTimeNanos > 0) {
                val elapsed = frameTimeNanos - lastFrameTimeNanos
                if (elapsed > dropThresholdNanos) {
                    droppedFrameCount++
                }
            }
            lastFrameTimeNanos = frameTimeNanos
            frameCount++
            choreographer.postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        startTimeMs = System.currentTimeMillis()
        frameCount = 0
        droppedFrameCount = 0
        lastFrameTimeNanos = 0L
        handler.post {
            choreographer.postFrameCallback(frameCallback)
        }
    }

    fun stop() {
        running = false
        handler.post {
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    fun summary(): String {
        val elapsedMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0L
        val fps = if (elapsedMs > 0) frameCount * 1000f / elapsedMs else 0f
        return "PlaybackPerf: %.1f fps, %d frames, %d drops in %.1fs".format(
            fps, frameCount, droppedFrameCount, elapsedMs / 1000f
        )
    }

    fun logSummary(tag: String = "PlaybackPerf") {
        Log.i(tag, summary())
    }
}
