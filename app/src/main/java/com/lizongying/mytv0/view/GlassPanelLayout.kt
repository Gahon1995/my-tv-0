package com.lizongying.mytv0.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import com.lizongying.mytv0.R

/**
 * 液态玻璃面板容器：
 *  层0 blurLayer(ImageView, centerCrop) —— 视频抓帧模糊底图
 *  层1 玻璃渐变+描边 overlay (bg_glass_panel / bg_glass_pill)
 *  层2 业务内容 (XML 子节点)
 *
 * VISIBLE 时自动启动取景，GONE/DETACH 自动停止。
 * SP.glassBlur=false 或会话降级时底图为空，仅剩渐变仿玻璃。
 */
class GlassPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val blurLayer: ImageView
    private var blurHelper: GlassBlurHelper? = null
    private var radiusPx: Float

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

        // 模糊底图层（最底）
        blurLayer = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.85f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 放大后的纹理再加一层 GPU 模糊消除颗粒感
                setRenderEffect(
                    RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                )
            }
        }
        addView(blurLayer, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // 玻璃渐变+描边覆盖层
        val overlay = View(context).apply { setBackgroundResource(overlayRes) }
        addView(overlay, 1, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

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

    private fun ensureHelper(): GlassBlurHelper {
        var h = blurHelper
        if (h == null) {
            h = GlassBlurHelper { bmp: Bitmap? ->
                blurLayer.setImageBitmap(bmp)
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
        blurLayer.setImageBitmap(null)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncBlurState()
        if (!isVisible) {
            blurLayer.setImageBitmap(null)
        }
    }
}
