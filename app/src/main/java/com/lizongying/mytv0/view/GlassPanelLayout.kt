package com.lizongying.mytv0.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
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
    private var glassApplied = false

    init {
        setWillNotDraw(false)
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

    /**
     * 让毛玻璃模糊开关实时生效。
     * 关闭模糊或切换 SP.glassBlur 后调用：清除旧 RenderEffect 并按新开关重建。
     */
    fun refreshGlassEffect() {
        // API < 31 不支持 RenderEffect，保持纯色降级模式
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            return
        }
        if (!SP.glassBlur) {
            setRenderEffect(null)
            return
        }
        applyGlassEffect()
    }

    @SuppressLint("NewApi")
    private fun applyGlassEffect() {
        if (glassApplied) return
        if (!SP.glassBlur || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        glassApplied = true
        try {
            // 模糊半径从 24f 降到 16f：玻璃质感足够且对大面板渲染开销明显更低
            val effect = RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
            setRenderEffect(effect)
        } catch (_: Exception) {
            // 降级到纯色模式
        }
    }

    companion object {
        private const val BLUR_RADIUS = 16f
    }

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w > 0 && h > 0) {
            rect.set(0f, 0f, w, h)
            fillPaint.shader = null
            fillPaint.color = baseColor
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            gradient?.let {
                fillPaint.shader = it
                canvas.drawRoundRect(rect, radius, radius, fillPaint)
                fillPaint.shader = null
            }
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
        super.dispatchDraw(canvas)
    }
}
