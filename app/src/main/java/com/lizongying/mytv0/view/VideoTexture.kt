package com.lizongying.mytv0.view

import android.view.TextureView

/**
 * 全局视频 TextureView 注册点。
 * PlayerFragment 在视图就绪后注册，GlassPanelLayout 从这里取景做玻璃模糊。
 */
object VideoTexture {
    @Volatile
    var view: TextureView? = null
}
