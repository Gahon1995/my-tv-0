package com.lizongying.mytv0.view

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.lizongying.mytv0.SP

/**
 * 玻璃模糊取景器：从视频 TextureView 周期抓取小尺寸帧，
 * 做轻量 box blur 后交给 GlassPanelLayout 作为模糊底图。
 *
 * 方案："小图放大即模糊"——抓 96x54 缩略图（成本 1-2ms），
 * CPU 两次 box blur 消块，再由 ImageView centerCrop 放大产生强模糊。
 * 性能不足时自动降级（连续超时 -> 本会话关闭）。
 */
class GlassBlurHelper(private val onFrame: (Bitmap?) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var slowCount = 0

    // 复用 bitmap 减少分配
    private var reuse: Bitmap? = null

    companion object {
        private const val TAG = "GlassBlurHelper"
        private const val W = 96
        private const val H = 54
        private const val INTERVAL = 500L
        private const val SLOW_MS = 8
        private const val SLOW_LIMIT = 3

        // 本会话级降级开关
        @Volatile
        var sessionDisabled = false
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            capture()
            handler.postDelayed(this, INTERVAL)
        }
    }

    fun start() {
        if (running) return
        if (!SP.glassBlur || sessionDisabled) {
            onFrame(null)
            return
        }
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun release() {
        stop()
        reuse?.recycle()
        reuse = null
    }

    private fun capture() {
        val tv = VideoTexture.view
        if (tv == null || !tv.isAvailable) {
            onFrame(null)
            return
        }
        try {
            val t0 = SystemClock.elapsedRealtime()
            var bmp = reuse
            if (bmp == null || bmp.isRecycled) {
                bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                reuse = bmp
            }
            val out = tv.getBitmap(bmp)
            val cost = SystemClock.elapsedRealtime() - t0
            if (out == null) {
                onSlow()
                return
            }
            if (cost > SLOW_MS) {
                onSlow()
                if (sessionDisabled) return
            } else {
                slowCount = 0
            }
            boxBlur(out)
            boxBlur(out)
            // 交给 UI 的必须是副本，reuse 下轮继续复用
            onFrame(out.copy(Bitmap.Config.ARGB_8888, false))
        } catch (e: Exception) {
            Log.w(TAG, "capture failed: ${e.message}")
            onSlow()
        }
    }

    private fun onSlow() {
        slowCount++
        if (slowCount >= SLOW_LIMIT) {
            sessionDisabled = true
            running = false
            handler.removeCallbacks(tick)
            onFrame(null)
            Log.w(TAG, "glass blur auto disabled for this session")
        }
    }

    /** 简易 box blur (radius=2)，96x54 尺寸下微秒级 */
    private fun boxBlur(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val r = 2
        // 水平
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                var dx = -r
                while (dx <= r) {
                    val xx = x + dx
                    if (xx in 0 until w) {
                        val c = px[row + xx]
                        a += c ushr 24 and 0xFF
                        rr += c ushr 16 and 0xFF
                        g += c ushr 8 and 0xFF
                        b += c and 0xFF
                        n++
                    }
                    dx++
                }
                out[row + x] =
                    (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        // 垂直
        for (x in 0 until w) {
            for (y in 0 until h) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                var dy = -r
                while (dy <= r) {
                    val yy = y + dy
                    if (yy in 0 until h) {
                        val c = out[yy * w + x]
                        a += c ushr 24 and 0xFF
                        rr += c ushr 16 and 0xFF
                        g += c ushr 8 and 0xFF
                        b += c and 0xFF
                        n++
                    }
                    dy++
                }
                px[y * w + x] =
                    (a / n shl 24) or (rr / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }
}
