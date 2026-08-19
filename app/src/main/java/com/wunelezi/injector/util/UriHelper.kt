package com.wunelezi.injector.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * ContentUri 读写工具
 *
 * 提供通过 ContentResolver 读写 content:// URI 的方法。
 */
object UriHelper {

    /**
     * 读取 content:// URI 内容，返回字符串
     */
    fun readUri(context: Context, url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 某些 content provider 不支持持久化权限，忽略
            }
            val ins: InputStream = resolver.openInputStream(uri) ?: return null
            val reader = BufferedReader(InputStreamReader(ins))
            val content = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                content.append(line).append("\n")
            }
            reader.close()
            content.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 写入字符串到 content:// URI
     */
    fun writeUri(context: Context, url: String, content: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 忽略
            }
            val os: OutputStream = resolver.openOutputStream(uri) ?: return false
            os.write(content.toByteArray())
            os.flush()
            os.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从 content:// URI 读取二进制 InputStream
     */
    fun openInputStream(context: Context, url: String): InputStream? {
        return try {
            val uri = Uri.parse(url)
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 向 content:// URI 写入二进制 OutputStream
     */
    fun openOutputStream(context: Context, url: String): OutputStream? {
        return try {
            val uri = Uri.parse(url)
            context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            null
        }
    }
}
