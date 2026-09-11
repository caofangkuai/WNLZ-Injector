package com.wunelezi.injector.inject

import android.content.Context

class CvePackageInstallerHandler(private val context: Context) : InjectionHandler {

    override fun execute(targetPackage: String, versionSegment: String) {
        val zipFile = DexDownloader.downloadDexZip(context, versionSegment)
        val dexFiles = DexDownloader.extractDexFiles(context, zipFile, versionSegment)
        DexDownloader.writeToWidgetProvider(context, dexFiles)
        val apkFile = DexDownloader.exportAndCopyApk(context)

        PackageInstallerShizuku.installPackage(apkFile, context.packageName)

        DexDownloader.copyDexToTarget(versionSegment)
    }
}
