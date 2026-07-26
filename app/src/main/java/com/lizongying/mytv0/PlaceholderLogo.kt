package com.lizongying.mytv0

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import androidx.core.content.ContextCompat

/**
 * 频道号占位图（logo 缺失时显示的数字图）。
 * 之前每次 RecyclerView bind 都会新建 300x180 Bitmap+Canvas，
 * 遥控器快速滚动时造成大量分配与 GC 卡顿；这里按频道号缓存复用。
 */
object PlaceholderLogo {
    private const val WIDTH = 300
    private const val HEIGHT = 180

    // 每张约 210KB，缓存 64 张约 13MB，对 TV 足够且可被 LRU 回收
    private val cache = LruCache<Int, Bitmap>(64)

    fun get(context: Context, channelNum: Int): Bitmap {
        cache.get(channelNum)?.let { return it }

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        var size = 150f
        if (channelNum > 99) {
            size = 100f
        }
        if (channelNum > 999) {
            size = 75f
        }
        val paint = Paint().apply {
            isAntiAlias = true
            color = ContextCompat.getColor(context, R.color.title_blur)
            textSize = size
            textAlign = Paint.Align.CENTER
        }
        val x = WIDTH / 2f
        val y = HEIGHT / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(channelNum.toString(), x, y, paint)

        cache.put(channelNum, bitmap)
        return bitmap
    }
}
