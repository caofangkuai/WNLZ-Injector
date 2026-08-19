package com.wunelezi.injector.util

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Shizuku 授权操作工具
 *
 * 当 FileHelper 漏洞方案无法直接创建文件时，通过 Shizuku（shell/root 权限）执行文件操作。
 *
 * Shizuku API 13.1.5 中 newProcess() 为 private，使用反射调用。
 */
object ShizukuHelper {

    private const val REQUEST_CODE = 0xA001

    /** 缓存的 newProcess Method 对象 */
    private var newProcessMethod: Method? = null

    /** Shizuku 是否可用（服务正在运行） */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /** 是否已获得 Shizuku 权限 */
    fun hasPermission(): Boolean {
        return try {
            isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /** 请求 Shizuku 权限 */
    fun requestPermission(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            listener.onRequestPermissionResult(REQUEST_CODE, PackageManager.PERMISSION_DENIED)
        }
    }

    /** 移除权限监听 */
    fun removeListener(listener: Shizuku.OnRequestPermissionResultListener) {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (e: Exception) {
            // 忽略
        }
    }

    /**
     * 通过反射获取 Shizuku.newProcess 方法
     *
     * 方法签名: newProcess(String[] cmd, String[] env, String dir) -> Process
     */
    private fun getNewProcessMethod(): Method? {
        newProcessMethod?.let { return it }
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            newProcessMethod = method
            method
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过 Shizuku 执行 shell 命令，返回退出码
     *
     * 使用反射调用 Shizuku.newProcess()，
     * 因为该方法在 API 13.1.5 中为 private。
     */
    fun execCommand(cmd: String): Int {
        return try {
            val method = getNewProcessMethod() ?: return -1
            if (!isAvailable()) return -1

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as? Process ?: return -1

            val code = process.waitFor()
            process.destroy()
            code
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 执行 shell 命令并返回输出文本
     */
    fun execCommandOutput(cmd: String): String? {
        return try {
            val method = getNewProcessMethod() ?: return null
            if (!isAvailable()) return null

            val process = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as? Process ?: return null

            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            process.destroy()
            text.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 确保目录存在
     */
    fun ensureDir(path: String): Boolean {
        return execCommand("mkdir -p \"$path\"") == 0
    }

    /**
     * 创建空文件
     */
    fun createFile(path: String): Boolean {
        return execCommand("touch \"$path\"") == 0
    }

    /**
     * 复制文件
     */
    fun copyFile(srcPath: String, dstPath: String): Boolean {
        return execCommand("cp \"$srcPath\" \"$dstPath\"") == 0
    }

    /**
     * 删除文件
     */
    fun deleteFile(path: String): Boolean {
        return execCommand("rm -f \"$path\"") == 0
    }

    /**
     * 通过 Shizuku 将 app 缓存中的文件复制到目标路径
     */
    fun copyToTarget(srcPath: String, dstPath: String): Boolean {
        val dstDir = dstPath.substring(0, dstPath.lastIndexOf('/'))
        ensureDir(dstDir)
        return copyFile(srcPath, dstPath)
    }
}
