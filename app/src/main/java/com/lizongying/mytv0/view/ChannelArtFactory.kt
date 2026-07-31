package com.lizongying.mytv0.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import com.lizongying.mytv0.R

/**
 * 生成频道号占位图的工厂。
 *
 * 电视上频道号多为个位/两位/三位，用 LRU 缓存按数字缓存已渲染的占位图，
 * 避免在列表滑动绑定、切台时反复 new Bitmap(300x180 -> ~216KB) + Canvas + Paint，
 * 显著减少高频 GC 造成的滑动卡顿。
 */
object ChannelArtFactory {
    const val WIDTH = 300
    const val HEIGHT = 180

    // 频道号越小越常被看到；缓存 64 张足够覆盖一个分组内常见的台号
    private const val CACHE_MAX = 64

    /** 数字 -> 复用位图的缓存的简单近似 LRU（头部最近使用，尾部最久）。 */
    private class BitmapCache {
        private val map = object : LinkedHashMap<Int, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>?): Boolean {
                return size > CACHE_MAX
            }
        }

        @Synchronized
        fun get(num: Int): Bitmap? = map[num]

        @Synchronized
        fun put(num: Int, bmp: Bitmap) {
            map[num] = bmp
        }
    }

    private val cache = BitmapCache()

    /**
     * 返回给定频道号的占位位图。
     * 生成的占位图为 300x180，中心绘制频道号数字，颜色用 title_blur 保证在多种背景可见。
     */
    fun channelBitmap(context: Context, channelNum: Int): Bitmap {
        cache.get(channelNum)?.let { return it }

        // 只绘制一次文本，消除重复的 Paint/Geometry 计算
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val size: Float = when {
            channelNum > 999 -> 75f
            channelNum > 99 -> 100f
            else -> 150f
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
