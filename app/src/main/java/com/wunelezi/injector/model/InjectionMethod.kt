package com.wunelezi.injector.model

import android.os.Build

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
    CVE_2024_0044_PM(
        displayName = "CVE-2024-0044(pm install)",
        description = "利用 Shizuku 实现 run-as"
    ),
    CVE_2024_0044_PACKAGEINSTALLER(
        displayName = "CVE-2024-0044(PackageInstaller)",
        description = "利用 Shizuku 实现 run-as"
    ),
    ROOT(
        displayName = "Root注入",
        description = "以 Root 身份直接替换 app_ntp0/.unzip 下的 dex，并还原原文件的权限/所有者/用户组/修改时间"
    );

    fun isSupported(): Boolean {
        return when (this) {
            START_ANYWHERE_NGWEBVIEW, START_ANYWHERE_ASSIST -> {
                Build.VERSION.SDK_INT >= 30 &&
                    Build.VERSION.SDK_INT <= 33 &&
                    Build.VERSION.SECURITY_PATCH != null &&
                    Build.VERSION.SECURITY_PATCH.compareTo("2023-03-01") < 0
            }
            CVE_2024_0044_PM, CVE_2024_0044_PACKAGEINSTALLER -> {
                Build.VERSION.SDK_INT >= 31 &&
                    Build.VERSION.SDK_INT <= 34 &&
                    Build.VERSION.SECURITY_PATCH != null &&
                    Build.VERSION.SECURITY_PATCH.compareTo("2024-10-01") < 0
            }
            ROOT -> {
                true
            }
        }
    }
}
