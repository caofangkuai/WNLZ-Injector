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
 * 所有方法在失败时返回 null/false，调用方可通过 [ReadResult] / [WriteResult] 获取错误详情。
 */

/** 读取结果（含错误信息） */
data class ReadResult(
    val content: String?,
    val error: String?
)

/** 写入结果（含错误信息） */
data class WriteResult(
    val success: Boolean,
    val error: String?
)

object UriHelper {

    /**
     * 读取 content:// URI 内容，返回字符串
     *
     * 失败时返回 null
     */
    fun readUri(context: Context, url: String): String? {
        return readUriWithDetail(context, url).content
    }

    /**
     * 读取 content:// URI 内容，返回 [ReadResult] 含错误详情
     */
    fun readUriWithDetail(context: Context, url: String): ReadResult {
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
            val ins: InputStream = resolver.openInputStream(uri)
                ?: return ReadResult(null, "openInputStream 返回 null (URI: $url)")
            val reader = BufferedReader(InputStreamReader(ins))
            val content = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                content.append(line).append("\n")
            }
            reader.close()
            ReadResult(content.toString(), null)
        } catch (e: Exception) {
            ReadResult(null, "${e::class.java.simpleName}: ${e.message} (URI: $url)")
        }
    }

    /**
     * 写入字符串到 content:// URI
     *
     * 失败时返回 false
     */
    fun writeUri(context: Context, url: String, content: String): Boolean {
        return writeUriWithDetail(context, url, content).success
    }

    /**
     * 写入字符串到 content:// URI，返回 [WriteResult] 含错误详情
     */
    fun writeUriWithDetail(context: Context, url: String, content: String): WriteResult {
        return try {
            val uri = Uri.parse(url)
            val resolver = context.contentResolver
            try {
                resolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // 某些 content provider 不支持持久化权限，忽略
            }
            val os: OutputStream = resolver.openOutputStream(uri)
                ?: return WriteResult(false, "openOutputStream 返回 null (URI: $url)")
            os.write(content.toByteArray())
            os.flush()
            os.close()
            WriteResult(true, null)
        } catch (e: Exception) {
            WriteResult(false, "${e::class.java.simpleName}: ${e.message} (URI: $url)")
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
