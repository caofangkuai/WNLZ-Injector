package com.wunelezi.injector.model

import android.graphics.drawable.Drawable

/**
 * 已安装应用信息
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable
)
