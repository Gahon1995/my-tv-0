package com.lizongying.mytv0.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.renderscript.RenderEffect
import android.util.AttributeSet
import android.widget.FrameLayout
import com.lizongying.mytv0.SP

/**
 * 毛玻璃面板容器。
 *
 * 行为由 SP.glassBlur 控制：
 * - true (API 31+): 设置 RenderEffect 真正模糊
 * - false 或 API < 31: 纯色半透明 + onDraw 渐变高光 + 描边
 */
class GlassPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f * resources.displayMetrics.density
        color = Color.argb(51, 255, 255, 255) // #33FFFFFF
    }
    private val rect = RectF()

    private var radius = 12f * resources.displayMetrics.density
    private var baseColor = Color.argb(204, 16, 22, 31) // #CC10161F

    // 渐变高光颜色
    private var highlightStart = Color.argb(26, 255, 255, 255) // #1AFFFFFF
    private var highlightEnd = Color.argb(0, 255, 255, 255)

    private var gradient: LinearGradient? = null
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        setWillNotDraw(false)
        // 确保背景透明，由 onDraw 绘制
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setGlassBaseColor(color: Int) {
        baseColor = color
        invalidate()
    }

    fun setGlassRadius(r: Float) {
        radius = r
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != lastWidth || h != lastHeight) {
            gradient = LinearGradient(
                0f, 0f, 0f, h * 0.4f,
                highlightStart, highlightEnd,
                Shader.TileMode.CLAMP
            )
            lastWidth = w
            lastHeight = h
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyGlassEffect()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0 && h > 0) {
            rect.set(0f, 0f, w, h)

            // 画半透明底色
            fillPaint.shader = null
            fillPaint.color = baseColor
            canvas.drawRoundRect(rect, radius, radius, fillPaint)

            // 画顶部渐变高光
            gradient?.let {
                fillPaint.shader = it
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                fillPaint.shader = null
            }

            // 画描边
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
        super.dispatchDraw(canvas)
    }

    private fun applyGlassEffect() {
        if (SP.glassBlur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val effect = RenderEffect.createBlurEffect(
                    24f, 24f,
                    RenderEffect.createBlurEffect(24f, 24f, RenderEffect.TileMode.CLAMP)
                )
                // 注意：RenderEffect 作用于整个 View 的内容，需要设置到 View 层级
                // 在 GlassPanelLayout 上设置会让子 View 也被模糊，所以只对底层容器生效
                // 这里使用 setRenderEffect（Android 12+ API）
                setRenderEffect(effect)
            } catch (_: Exception) {
                // 降级到纯色模式
            }
        }
    }
}
