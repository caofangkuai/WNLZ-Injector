package com.wunelezi.injector

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import androidx.annotation.Keep

/**
 * Shizuku 用户服务（UserService）实现。
 *
 * 该类的实例由 Shizuku 服务端在「具有 root / adb shell 身份」的独立进程（user_service）中
 * 通过 createPackageContextAsUser + 反射构造，因此其中 Runtime.exec 执行的命令即拥有该身份。
 *
 * 官方要求：
 *  1. 必须继承 AIDL 生成的 Stub（即本身是 IBinder）；
 *  2. 必须提供无参构造器，或带有 Context 形参的构造器（二者均加 @Keep 防止 R8 裁剪）；
 *  3. 必须实现 destroy()（事务码 16777114），供 Shizuku 服务端销毁服务时调用。
 *
 * 无需在 AndroidManifest 中声明 <service>，Shizuku 会按 ComponentName 直接加载本类。
 */
class ShizukuShellService : IShizukuShell.Stub {

    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super()

    override fun destroy() {
        // Shizuku 服务端请求销毁用户服务时调用，直接结束所在进程。
        System.exit(0)
    }

    override fun exec(command: String?, env: Array<out String>?): Bundle {
        val result = Bundle()
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("sh", "-c", command ?: ""),
                env,
                null
            )
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val err = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            result.putInt("exit", exitCode)
            result.putString("out", out + err)
        } catch (e: Throwable) {
            result.putInt("exit", -1)
            result.putString("out", e.message ?: e.toString())
        }
        return result
    }
}
