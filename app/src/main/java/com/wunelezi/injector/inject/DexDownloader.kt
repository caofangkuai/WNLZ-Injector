package com.wunelezi.injector.inject

import android.content.Context
import android.net.Uri
import java.io.File

object DexDownloader {

    fun downloadDexZip(context: Context, versionSegment: String): File {
        val zipFile = File(context.cacheDir, "wnlz_cve_$versionSegment.zip")
        val urlStr = "https://github.com/caofangkuai/WNLZ-Injector-dex/releases/download/$versionSegment/dex.zip"
        val code = downloadZip(urlStr, zipFile)
        if (code == 404) throw DexNotFoundException()
        if (code != 200) throw java.io.IOException("下载 dex 失败 HTTP $code")
        return zipFile
    }

    fun extractDexFiles(context: Context, zipFile: File, versionSegment: String): List<File> {
        val extractDir = File(context.cacheDir, "wnlz_cve_$versionSegment")
        extractDir.deleteRecursively()
        extractDir.mkdirs()
        return unzipZip(zipFile, extractDir)
    }

    fun writeToWidgetProvider(context: Context, dexFiles: List<File>) {
        for (dex in dexFiles) {
            val targetUri = "content://com.netease.x19.widget_file_provider/widget_file_cache/${dex.name}"
            val os = context.contentResolver.openOutputStream(Uri.parse(targetUri))
                ?: throw java.io.IOException("无法打开输出流: $targetUri")
            os.use { out ->
                dex.inputStream().use { it.copyTo(out) }
            }
        }
    }

    fun exportAndCopyApk(context: Context): File {
        val extDir = context.getExternalFilesDir(null)
            ?: throw java.io.IOException("外部存储不可用，无法导出 cve-2024-0044.apk")
        val apkFile = File(extDir, "cve-2024-0044.apk")
        context.assets.open("cve-2024-0044.apk").use { input ->
            apkFile.outputStream().use { input.copyTo(it) }
        }
        val r = ShizukuExecutor.shell("cp ${apkFile.absolutePath} /data/local/tmp/cve-2024-0044.apk")
        if (r.exitCode != 0) {
            throw RuntimeException("复制 cve-2024-0044.apk 失败 (exit ${r.exitCode}):\n${r.output}")
        }
        runCatching { apkFile.delete() }
        return File("/data/local/tmp/cve-2024-0044.apk")
    }

    fun copyDexToTarget(versionSegment: String) {
        val r = ShizukuExecutor.shell("run-as mcinject cp -f cache/classes*.dex \"app_ntp0/$versionSegment/.unzip/\"")
        if (r.exitCode != 0) {
            throw RuntimeException("复制 dex 失败 (exit ${r.exitCode}):\n${r.output}")
        }
    }

    private fun downloadZip(urlStr: String, destFile: File): Int {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 60000
        }
        val code = conn.responseCode
        if (code == 200) {
            conn.inputStream.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        conn.disconnect()
        return code
    }

    private fun unzipZip(zipFile: File, destDir: File): List<File> {
        val dexFiles = mutableListOf<File>()
        val destCanonical = destDir.canonicalPath
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destCanonical + File.separator) && outFile.canonicalPath != destCanonical) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    if (outFile.name.endsWith(".dex")) dexFiles.add(outFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return dexFiles
    }
}

class DexNotFoundException : Exception("暂时没有适配的注入文件")
