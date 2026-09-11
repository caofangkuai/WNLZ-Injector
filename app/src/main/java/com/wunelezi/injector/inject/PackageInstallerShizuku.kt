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
import rikka.shizuku.ShizukuBinderWrapper
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object PackageInstallerShizuku {

    private val packageInstallerConstructor: Constructor<*> by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PackageInstaller::class.java.getDeclaredConstructor(
                IPackageInstaller::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PackageInstaller::class.java.getDeclaredConstructor(
                IPackageInstaller::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        } else {
            PackageInstaller::class.java.getDeclaredConstructor(
                android.content.Context::class.java,
                PackageInstaller::class.java,
                IPackageInstaller::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }
    }

    private val sessionIBinderField: Field by lazy {
        val field = PackageInstaller.Session::class.java.getDeclaredField("mSession")
        field.isAccessible = true
        field
    }

    private val installFlagsField: Field by lazy {
        val field = PackageInstaller.SessionParams::class.java.getDeclaredField("installFlags")
        field.isAccessible = true
        field
    }

    private val serviceManagerClass = Class.forName("android.os.ServiceManager")

    private fun getService(name: String): IBinder {
        val method = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
        return method.invoke(null, name) as IBinder
    }

    fun installPackage(apkFile: File, installerPackageName: String) {
        val iPackageManager = IPackageManager.Stub.asInterface(
            ShizukuBinderWrapper(getService("package"))
        )
        val iPackageInstaller = IPackageInstaller.Stub.asInterface(
            ShizukuBinderWrapper(iPackageManager.getPackageInstaller().asBinder())
        )

        val packageInstaller = createPackageInstaller(
            iPackageInstaller, installerPackageName, 0
        )

        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        installFlagsField.setInt(params, installFlagsField.getInt(params) or 0x00000002)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        params.setInstallerPackageName(installerPackageName)

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        wrapSessionIBinder(session)

        session.openWrite("base.apk", 0, apkFile.length()).use { outputStream ->
            apkFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
            session.fsync(outputStream)
        }

        val receiver = LocalIntentReceiver()
        session.commit(receiver.getIntentSender())
        session.close()

        verifyInstallResult(receiver)
    }

    private fun createPackageInstaller(
        iPackageInstaller: IPackageInstaller,
        installerPackageName: String,
        userId: Int
    ): PackageInstaller {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            packageInstallerConstructor.newInstance(
                iPackageInstaller, installerPackageName, null, userId
            ) as PackageInstaller
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageInstallerConstructor.newInstance(
                iPackageInstaller, installerPackageName, userId
            ) as PackageInstaller
        } else {
            packageInstallerConstructor.newInstance(
                null, null, iPackageInstaller, installerPackageName, userId
            ) as PackageInstaller
        }
    }

    private fun wrapSessionIBinder(session: PackageInstaller.Session) {
        val iSession = sessionIBinderField.get(session) as IInterface
        val wrappedBinder = ShizukuBinderWrapper(iSession.asBinder())
        sessionIBinderField.set(
            session,
            IPackageInstallerSession.Stub.asInterface(wrappedBinder)
        )
    }

    private fun verifyInstallResult(receiver: LocalIntentReceiver) {
        val intent = receiver.getResult()
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
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
                queue.offer(intent, 5, TimeUnit.SECONDS)
            }

            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    return super.onTransact(code, data, reply, flags)
                }
                val descriptor = "android.content.IIntentSender"
                when (code) {
                    1 -> {
                        data.enforceInterface(descriptor)
                        send(
                            data.readInt(),
                            if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null,
                            data.readString(),
                            null,
                            IIntentReceiver.Stub.asInterface(data.readStrongBinder()),
                            data.readString(),
                            if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                        )
                        return true
                    }
                    0x5F4E5446 -> {
                        reply?.writeString(descriptor)
                        return true
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
            }


        }

        fun getIntentSender(): IntentSender {
            val constructor = IntentSender::class.java.getDeclaredConstructor(
                IIntentSender::class.java
            ).apply { isAccessible = true }
            return constructor.newInstance(localSender) as IntentSender
        }

        fun getResult(): Intent {
            return queue.take()
        }
    }
}
