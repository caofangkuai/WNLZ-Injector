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
    START_ANYWHERE_NGWEBVIEW(
        displayName = "StartAnyWhere(NgWebviewActivity)",
        description = "利用 Intent 重定向实现任意Activity启动"
    ),
    START_ANYWHERE_ASSIST(
        displayName = "StartAnyWhere(AssistActivity)",
        description = "利用 Intent 重定向实现任意Activity启动"
    ),
    CVE_2024_0044(
        displayName = "CVE-2024-0044",
        description = "利用 Shizuku + mcinject 实现任意Activity启动"
    ),
    ROOT(
        displayName = "Root注入",
        description = "以 Root 身份直接替换 app_ntp0/.unzip 下的 dex，并还原原文件的权限/所有者/用户组/修改时间"
    );

    /**
     * 检测当前设备是否支持此注入方式。
     */
    fun isSupported(): Boolean {
        return when (this) {
            START_ANYWHERE_NGWEBVIEW, START_ANYWHERE_ASSIST -> {
                // StartAnyWhere：Android 11-13 且安全补丁早于 2023-03-01
                Build.VERSION.SDK_INT >= 30 &&
                    Build.VERSION.SDK_INT <= 33 &&
                    Build.VERSION.SECURITY_PATCH != null &&
                    Build.VERSION.SECURITY_PATCH.compareTo("2023-03-01") < 0
            }
            CVE_2024_0044 -> {
                // CVE-2024-0044：Android 12-14 且安全补丁早于 2024-10-01
                Build.VERSION.SDK_INT >= 31 &&
                    Build.VERSION.SDK_INT <= 34 &&
                    Build.VERSION.SECURITY_PATCH != null &&
                    Build.VERSION.SECURITY_PATCH.compareTo("2024-10-01") < 0
            }
            ROOT -> {
                // Root 注入：依赖设备已 Root，运行时再检测 su 是否可用，这里默认可用
                true
            }
        }
    }
}
