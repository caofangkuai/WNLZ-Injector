package com.wunelezi.injector.inject

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import rikka.shizuku.Shizuku
import java.io.File
import java.lang.reflect.Method

interface InjectionHandler {
    fun execute(targetPackage: String, versionSegment: String)
}

data class ShellResult(val exitCode: Int, val output: String)

object ShizukuExecutor {

    private val shizukuNewProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    fun shell(command: String, env: Array<String>? = null): ShellResult {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku 未连接")
        }
        val process = shizukuNewProcessMethod.invoke(
            null,
            arrayOf("sh", "-c", command),
            env,
            null
        ) as Process
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ShellResult(exitCode, out + err)
    }

    fun isConnected(): Boolean = Shizuku.pingBinder()

    fun hasPermission(): Boolean = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun shouldShowRationale(): Boolean = Shizuku.shouldShowRequestPermissionRationale()

    fun isPreV11(): Boolean = Shizuku.isPreV11()
}
