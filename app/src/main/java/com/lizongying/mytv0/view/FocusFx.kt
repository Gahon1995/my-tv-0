package com.lizongying.mytv0.view

import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * 焦点动效统一入口：获得焦点轻微放大，失去焦点复位。
 */
object FocusFx {

    private val decel = DecelerateInterpolator()

    fun apply(view: View, hasFocus: Boolean) {
        if (hasFocus) {
            view.animate()
                .scaleX(1.03f).scaleY(1.03f)
                .setDuration(120)
                .setInterpolator(decel)
                .start()
        } else {
            view.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(100)
                .setInterpolator(decel)
                .start()
        }
    }

    /** 面板出现动效 */
    fun panelIn(view: View) {
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 16
        view.animate()
            .alpha(1f).translationY(0f)
            .setDuration(180)
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
