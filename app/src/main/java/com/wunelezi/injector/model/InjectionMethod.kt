package com.wunelezi.injector.model

import android.os.Build

/**
 * 注入方式枚举
 *
 * 每个方式包含设备支持检测逻辑。
 * 目前仅有 StartAnyWhere，后续可在此扩展。
 */
enum class InjectionMethod(
    val displayName: String,
    val description: String
) {
    START_ANYWHERE(
        displayName = "StartAnyWhere",
        description = "利用 PendingIntent 重定向实现任意起点启动"
    );

    /**
     * 检测当前设备是否支持此注入方式。
     *
     * StartAnyWhere 条件:
     *  - SDK_INT >= 30 (Android 11)
     *  - SDK_INT <= 33 (Android 13)
     *  - SECURITY_PATCH != null
     *  - SECURITY_PATCH < "2023-03-01"
     */
    fun isSupported(): Boolean {
        return when (this) {
            START_ANYWHERE -> {
                Build.VERSION.SDK_INT >= 30 &&
                    Build.VERSION.SDK_INT <= 33 &&
                    Build.VERSION.SECURITY_PATCH != null &&
                    Build.VERSION.SECURITY_PATCH.compareTo("2023-03-01") < 0
            }
        }
    }
}
