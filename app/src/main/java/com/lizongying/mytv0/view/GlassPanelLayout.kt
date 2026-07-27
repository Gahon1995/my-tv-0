package com.lizongying.mytv0.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 * 液态玻璃面板容器（遮罩方案）。
 *
 * 由深色半透明底 + 玻璃渐变高光 + 描边构成。
 * 背景在尺寸变化时缓存为 bitmap，减少 onDraw 开销。
 */
class GlassPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var radiusPx: Float
    private val overlayDrawable: Drawable
    private val basePaint = Paint()

    private var lastW = -1
    private var lastH = -1
    private var cachedBg: Bitmap? = null

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
        basePaint.color = ta.getColor(
            R.styleable.GlassPanelLayout_glassBaseColor,
            0xCC10161F.toInt()
        )
        ta.recycle()

        overlayDrawable = ContextCompat.getDrawable(context, overlayRes)!!

        setWillNotDraw(false)

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
            }
        }
    }

    fun setGlassRadius(px: Float) {
        radiusPx = px
        cachedBg?.recycle()
        cachedBg = null
        invalidateOutline()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cachedBg?.recycle()
        cachedBg = null
        lastW = w
        lastH = h
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        var bg = cachedBg
        if (bg == null || bg.isRecycled || lastW != w || lastH != h) {
            bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bgCanvas = Canvas(bg)
            bgCanvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radiusPx, radiusPx, basePaint)
            overlayDrawable.setBounds(0, 0, w, h)
            overlayDrawable.draw(bgCanvas)
            cachedBg = bg
            lastW = w
            lastH = h
        }

        canvas.drawBitmap(bg, 0f, 0f, null)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cachedBg?.recycle()
        cachedBg = null
    }
}
