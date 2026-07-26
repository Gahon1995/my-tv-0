package com.lizongying.mytv0.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.lizongying.mytv0.R

/**
 * 液态玻璃面板容器。
 *
 * 模糊底图与玻璃渐变/描边均在 onDraw 中直接绘制（不作为子 View），
 * 避免 match_parent 装饰层在 wrap_content 容器中把面板撑满全屏。
 *
 * VISIBLE 时自动启动视频抓帧取景，GONE/DETACH 自动停止。
 * SP.glassBlur=false 或会话降级时无底图，仅剩渐变仿玻璃。
 */
class GlassPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var blurHelper: GlassBlurHelper? = null
    private var radiusPx: Float
    private val overlayDrawable: Drawable

    private var blurBitmap: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        alpha = 170
    }
    private val bmpMatrix = Matrix()
    private val basePaint = Paint().apply {
        color = 0xB30D131B.toInt() // 70% 深底，保证列表可读性
    }

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.GlassPanelLayout)
        radiusPx = ta.getDimension(
            R.styleable.GlassPanelLayout_glassRadius,
            resources.displayMetrics.density * 24f
        )
        val overlayRes = ta.getResourceId(
            R.styleable.GlassPanelLayout_glassOverlay,
            R.drawable.bg_glass_panel
        )
        ta.recycle()

        overlayDrawable = ContextCompat.getDrawable(context, overlayRes)!!

        setWillNotDraw(false)

        // 圆角裁切
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    fun setGlassRadius(px: Float) {
        radiusPx = px
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        // 深色底：保证任何视频画面下文字可读
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), basePaint)

        // 模糊底图 centerCrop 绘制（双线性放大 = 强模糊）
        blurBitmap?.let {
            if (!it.isRecycled) {
                val scale = maxOf(w.toFloat() / it.width, h.toFloat() / it.height)
                bmpMatrix.reset()
                bmpMatrix.setScale(scale, scale)
                bmpMatrix.postTranslate(
                    (w - it.width * scale) / 2f,
                    (h - it.height * scale) / 2f
                )
                canvas.drawBitmap(it, bmpMatrix, bmpPaint)
            }
        }

        // 玻璃渐变 + 描边
        overlayDrawable.setBounds(0, 0, w, h)
        overlayDrawable.draw(canvas)
    }

    private fun ensureHelper(): GlassBlurHelper {
        var h = blurHelper
        if (h == null) {
            h = GlassBlurHelper { bmp: Bitmap? ->
                blurBitmap = bmp
                invalidate()
            }
            blurHelper = h
        }
        return h
    }

    private fun syncBlurState() {
        if (isAttachedToWindow && isShown) {
            ensureHelper().start()
        } else {
            blurHelper?.stop()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncBlurState()
    }

    override fun onDetachedFromWindow() {
        blurHelper?.release()
        blurHelper = null
        blurBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncBlurState()
        if (!isVisible) {
            blurBitmap = null
        }
    }
}
