package android.content.pm

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException

interface IPackageManager : IInterface {
    @Throws(RemoteException::class)
    fun getPackageInstaller(): IPackageInstaller

    abstract class Stub : Binder(), IPackageManager {
        companion object {
            fun asInterface(obj: IBinder): IPackageManager {
                val iin = obj.queryLocalInterface("android.content.pm.IPackageManager")
                if (iin != null && iin is IPackageManager) return iin
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, "android.content.pm.IPackageManager")
        }

        private class Proxy(val mRemote: IBinder) : IPackageManager {
            override fun getPackageInstaller(): IPackageInstaller {
                val _data = android.os.Parcel.obtain()
                val _reply = android.os.Parcel.obtain()
                try {
                    _data.writeInterfaceToken("android.content.pm.IPackageManager")
                    mRemote.transact(10, _data, _reply, 0)
                    _reply.readException()
                    return IPackageInstaller.Stub.asInterface(_reply.readStrongBinder())
                } finally {
                    _reply.recycle()
                    _data.recycle()
                }
            }

            override fun asBinder(): IBinder = mRemote
        }
    }
}
