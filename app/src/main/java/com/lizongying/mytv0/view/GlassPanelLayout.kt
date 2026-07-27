package com.lizongying.mytv0.view

import android.content.Context
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
 * 由深色半透明底 + 玻璃渐变高光 + 描边构成，全部在 onDraw 绘制，
 * 不参与布局测量（wrap_content 面板尺寸完全跟随内容）。
 * 已移除视频抓帧模糊（对低端设备播放有干扰）。
 */
class GlassPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var radiusPx: Float
    private val overlayDrawable: Drawable

    private val basePaint = Paint()

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
            0xCC10161F.toInt() // 默认 80% 深底，保证文字可读
        )
        ta.recycle()

        overlayDrawable = ContextCompat.getDrawable(context, overlayRes)!!

        setWillNotDraw(false)

        // 圆角通过 ViewOutlineProvider 提供 elevation shadow，不 clip children
        // （避免裁剪导航高亮背景、列表文字等）
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

        // 圆角深色底
        canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radiusPx, radiusPx, basePaint)

        // 玻璃渐变 + 描边
        overlayDrawable.setBounds(0, 0, w, h)
        overlayDrawable.draw(canvas)
    }
}
