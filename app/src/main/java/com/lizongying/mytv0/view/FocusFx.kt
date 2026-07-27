package com.lizongying.mytv0.view

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 焦点动效统一入口。
 * 去除 scale 动画（低端设备卡顿），焦点态由 selector drawable 处理。
 * 保留 panelIn 和 reset 以供特殊需求。
 */
object FocusFx {

    private val decel = DecelerateInterpolator()

    /** 焦点变化：不做动画，焦点态由 bg_item_selector 背景 drawable 处理 */
    fun apply(view: View, hasFocus: Boolean) {
        // no-op: 焦点态完全由 selector drawable 渲染，避免 scale 动画造成卡顿
    }

    /** 面板出现动效 */
    fun panelIn(view: View) {
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 12
        view.animate()
            .alpha(1f).translationY(0f)
            .setDuration(150)
            .setInterpolator(decel)
            .start()
    }

    /** 复位（detach/hide 时调用，防状态残留） */
    fun reset(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.alpha = 1f
        view.translationY = 0f
    }
}
