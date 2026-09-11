package com.wunelezi.injector.inject

import android.content.IIntentReceiver
import android.content.IIntentSender
import android.content.Intent
import android.content.IntentSender
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.ServiceManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import java.io.File
import java.lang.reflect.Field
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object PackageInstallerShizuku {

    init {
        // 解除本进程的非 SDK 接口访问限制。
        // 反射调用 IPackageManager.getPackageInstaller()、PackageInstaller 隐藏构造函数、
        // Session.mSession 字段、IntentSender(IIntentSender) 构造函数等都属于 hidden API，
        // 未豁免时 Class.getMethod 会直接抛出 NoSuchMethodException。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    private fun getDeclaredField(clazz: Class<*>, name: String): Field? {
        return try {
            for (field in clazz.declaredFields) {
                if (field.name != name) continue
                field.isAccessible = true
                return field
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun getDeclaredField(clazz: Class<*>, name: String, type: Class<*>): Field? {
        return try {
            var field = getDeclaredField(clazz, name)
            if (field?.type != type) {
                for (f in clazz.declaredFields) {
                    if (f.type != type) continue
                    f.isAccessible = true
                    field = f
                    break
                }
            }
            field
        } catch (e: Exception) {
            null
        }
    }

    private fun getDeclaredConstructor(clazz: Class<*>, vararg parameterTypes: Class<*>): java.lang.reflect.Constructor<*>? {
        return try {
            for (constructor in clazz.declaredConstructors) {
                val expectedTypes = constructor.parameterTypes
                if (expectedTypes.size != parameterTypes.size) continue
                var match = true
                for (i in expectedTypes.indices) {
                    if (expectedTypes[i] != parameterTypes[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    constructor.isAccessible = true
                    return constructor
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 通过反射调用系统 Stub 的 asInterface。
     *
     * 不能直接写 `IPackageInstallerSession.Stub.asInterface(...)`：本应用的桩类是用 Kotlin 写的，
     * 编译产物会访问 `...$Stub.Companion` 字段；而运行时类加载器父优先，实际加载的是
     * framework.jar 里的 Java `...$Stub`，它没有 `Companion` 字段，于是抛出
     * "No field Companion of type ...$Stub$Companion"。
     * 用反射按类名查找即可绕开 Kotlin 伴生对象的字段访问。
     *
     * "$Stub" 后缀刻意在运行时由 char 数组拼出：若写成字符串常量，R8 会在编译期把
     * `Class.forName("...$Stub")` 解析为 app 内的同名桩类，并注入
     * `sget-object ...$Stub.Companion` 以保留类初始化副作用；该引用运行时解析到
     * framework.jar 的系统类，系统类没有 Companion 字段，release 下会抛
     * NoSuchFieldError（debug 不混淆所以正常）。拆成运行时拼接后 R8 无法静态解析，
     * 反射保持原样，Class.forName 在运行时才解析到系统类。
     */
    private val STUB_SUFFIX = charArrayOf('$', 'S', 't', 'u', 'b')

    private fun asInterfaceViaReflection(interfaceName: String, binder: IBinder?): IInterface? {
        if (binder == null) return null
        val stubClass = Class.forName(interfaceName + String(STUB_SUFFIX))
        val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
        return asInterfaceMethod.invoke(null, binder) as? IInterface
    }

    private fun getPackageInstaller(installerPackageName: String, userId: Int): Result<PackageInstaller> {
        return runCatching {
            val packageBinder = try {
                ShizukuBinderWrapper(ServiceManager.getService("package"))
            } catch (e: Exception) {
                throw RuntimeException("获取 package 服务失败: ${e.message}", e)
            }

            val iPackageManager = runCatching {
                asInterfaceViaReflection("android.content.pm.IPackageManager", packageBinder)
            }.getOrElse { throw RuntimeException("创建 IPackageManager 失败: ${it.message}", it) }

            if (iPackageManager == null) {
                throw RuntimeException("IPackageManager.Stub.asInterface 返回 null")
            }

            val installerBinder = try {
                val pmClass = iPackageManager.javaClass
                val getPackageInstallerMethod = pmClass.getMethod("getPackageInstaller")
                val iPackageInstallerObj = getPackageInstallerMethod.invoke(iPackageManager)
                (iPackageInstallerObj as IInterface).asBinder()
            } catch (e: Exception) {
                throw RuntimeException("获取 packageInstaller 失败: ${e.message}", e)
            }

            val iPackageInstaller = runCatching {
                asInterfaceViaReflection("android.content.pm.IPackageInstaller", ShizukuBinderWrapper(installerBinder))
            }.getOrElse { throw RuntimeException("创建 IPackageInstaller 失败: ${it.message}", it) }

            if (iPackageInstaller == null) {
                throw RuntimeException("IPackageInstaller.Stub.asInterface 返回 null")
            }

            val constructor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getDeclaredConstructor(
                    PackageInstaller::class.java,
                    IPackageInstaller::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.java,
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getDeclaredConstructor(
                    PackageInstaller::class.java,
                    IPackageInstaller::class.java,
                    String::class.java,
                    Int::class.java,
                )
            } else {
                getDeclaredConstructor(
                    PackageInstaller::class.java,
                    android.content.Context::class.java,
                    PackageInstaller::class.java,
                    IPackageInstaller::class.java,
                    String::class.java,
                    Int::class.java
                )
            }

            if (constructor == null) {
                throw RuntimeException("未找到 PackageInstaller 构造函数 (SDK: ${Build.VERSION.SDK_INT})")
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    constructor.newInstance(iPackageInstaller, installerPackageName, null, userId) as PackageInstaller
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    constructor.newInstance(iPackageInstaller, installerPackageName, userId) as PackageInstaller
                } else {
                    constructor.newInstance(null, null, iPackageInstaller, installerPackageName, userId) as PackageInstaller
                }
            } catch (e: Exception) {
                throw RuntimeException("PackageInstaller 构造失败: ${e.message}", e)
            }
        }
    }

    private fun setSessionIBinder(session: PackageInstaller.Session): Result<Unit> {
        return runCatching {
            val field = getDeclaredField(
                session::class.java, "mSession", IPackageInstallerSession::class.java
            ) ?: throw RuntimeException("未找到 Session.mSession 字段")

            val iInterface = try {
                field.get(session) as? IInterface
            } catch (e: Exception) {
                throw RuntimeException("获取 mSession 值失败: ${e.message}", e)
            } ?: throw RuntimeException("mSession 为 null")

            val iBinder = iInterface.asBinder()
            val wrapped = ShizukuBinderWrapper(iBinder)
            val wrappedSession = asInterfaceViaReflection(
                "android.content.pm.IPackageInstallerSession", wrapped
            ) ?: throw RuntimeException("IPackageInstallerSession.Stub.asInterface 返回 null")

            try {
                field.set(session, wrappedSession)
            } catch (e: Exception) {
                throw RuntimeException("设置 mSession 失败: ${e.message}", e)
            }
        }
    }

    fun installPackage(apkFile: File, installerPackageName: String): Result<Unit> {
        return runCatching {
            if (!apkFile.exists()) {
                throw RuntimeException("APK 文件不存在: ${apkFile.absolutePath}")
            }
            if (!apkFile.canRead()) {
                throw RuntimeException("APK 文件不可读: ${apkFile.absolutePath}")
            }

            val packageInstaller = getPackageInstaller(installerPackageName, 0).getOrElse {
                throw RuntimeException("获取 PackageInstaller 失败: ${it.message}", it)
            }

            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val installFlagsField = getDeclaredField(PackageInstaller.SessionParams::class.java, "installFlags")
            if (installFlagsField != null) {
                try {
                    installFlagsField.setInt(params, installFlagsField.getInt(params) or 0x00000002)
                } catch (e: Exception) {
                    // ignore: installFlags 设置失败不影响核心流程
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            params.setInstallerPackageName(installerPackageName)

            val sessionId = try {
                packageInstaller.createSession(params)
            } catch (e: Exception) {
                throw RuntimeException("创建安装会话失败: ${e.message}", e)
            }

            val session = try {
                packageInstaller.openSession(sessionId)
            } catch (e: Exception) {
                throw RuntimeException("打开安装会话失败: ${e.message}", e)
            }

            setSessionIBinder(session).getOrElse {
                throw RuntimeException("设置 Session IBinder 失败: ${it.message}", it)
            }

            try {
                session.openWrite("base.apk", 0, apkFile.length()).use { outputStream ->
                    apkFile.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    session.fsync(outputStream)
                }
            } catch (e: Exception) {
                throw RuntimeException("写入 APK 数据失败: ${e.message}", e)
            }

            val receiver = LocalIntentReceiver()
            try {
                session.commit(receiver.intentSender)
            } catch (e: Exception) {
                throw RuntimeException("提交安装失败: ${e.message}", e)
            } finally {
                try {
                    session.close()
                } catch (_: Exception) {}
            }

            installResultVerify(receiver)
        }
    }

    private fun installResultVerify(receiver: LocalIntentReceiver) {
        val intent = receiver.result
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val action = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            throw RuntimeException("需要用户确认安装: $action")
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "未知错误"
            throw RuntimeException("安装失败: status=$status, msg=$msg")
        }
    }

    private class LocalIntentReceiver {
        private val queue = LinkedBlockingQueue<Intent>(1)

        private val localSender = object : IIntentSender.Stub() {
            override fun send(
                code: Int,
                intent: Intent?,
                resolvedType: String?,
                whitelistToken: IBinder?,
                finishedReceiver: IIntentReceiver?,
                requiredPermission: String?,
                options: Bundle?
            ) {
                try {
                    queue.offer(intent, 5, TimeUnit.SECONDS)
                } catch (_: Exception) {}
            }

            fun send(
                code: Int,
                intent: Intent?,
                resolvedType: String?,
                finishedReceiver: IIntentReceiver?,
                requiredPermission: String?,
                options: Bundle?
            ) {
                send(
                    code, intent, resolvedType, null, finishedReceiver, requiredPermission, options
                )
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return super.onTransact(
                    code, data, reply, flags
                )
                val descriptor = "android.content.IIntentSender"
                return when (code) {
                    1 -> {
                        data.enforceInterface(descriptor)
                        send(
                            data.readInt(),
                            if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null,
                            data.readString(),
                            asInterfaceViaReflection("android.content.IIntentReceiver", data.readStrongBinder()) as? IIntentReceiver,
                            data.readString(),
                            if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        )
                        true
                    }

                    0x5F4E5446 -> {
                        reply?.writeString(descriptor)
                        true
                    }

                    else -> return super.onTransact(code, data, reply, flags)
                }
            }
        }

        val intentSender: IntentSender by lazy {
            val constructor = getDeclaredConstructor(
                IntentSender::class.java, IIntentSender::class.java
            ) ?: throw RuntimeException("未找到 IntentSender 构造函数")
            try {
                constructor.newInstance(localSender) as IntentSender
            } catch (e: Exception) {
                throw RuntimeException("创建 IntentSender 失败: ${e.message}", e)
            }
        }

        val result: Intent
            get() = try {
                val r = queue.take()
                queue.remove(r)
                r
            } catch (e: InterruptedException) {
                throw RuntimeException("获取安装结果超时", e)
            }
    }
}
