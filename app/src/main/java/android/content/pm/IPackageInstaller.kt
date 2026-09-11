package android.content.pm

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException

interface IPackageInstaller : IInterface {
    @Throws(RemoteException::class)
    fun uninstall(
        versionedPackage: VersionedPackage,
        callerPackageName: String,
        flags: Int,
        statusReceiver: android.content.IntentSender,
        userId: Int
    )

    abstract class Stub : Binder(), IPackageInstaller {
        companion object {
            fun asInterface(obj: IBinder): IPackageInstaller {
                val iin = obj.queryLocalInterface("android.content.pm.IPackageInstaller")
                if (iin != null && iin is IPackageInstaller) return iin
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, "android.content.pm.IPackageInstaller")
        }

        private class Proxy(val mRemote: IBinder) : IPackageInstaller {
            override fun uninstall(
                versionedPackage: VersionedPackage,
                callerPackageName: String,
                flags: Int,
                statusReceiver: android.content.IntentSender,
                userId: Int
            ) {
                val _data = android.os.Parcel.obtain()
                val _reply = android.os.Parcel.obtain()
                try {
                    _data.writeInterfaceToken("android.content.pm.IPackageInstaller")
                    versionedPackage.writeToParcel(_data, 0)
                    _data.writeString(callerPackageName)
                    _data.writeInt(flags)
                    statusReceiver.writeToParcel(_data, 0)
                    _data.writeInt(userId)
                    mRemote.transact(1, _data, _reply, 0)
                    _reply.readException()
                } finally {
                    _reply.recycle()
                    _data.recycle()
                }
            }

            override fun asBinder(): IBinder = mRemote
        }
    }
}
