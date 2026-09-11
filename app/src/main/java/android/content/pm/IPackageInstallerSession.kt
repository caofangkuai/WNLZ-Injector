package android.content.pm

import android.os.Binder
import android.os.IBinder
import android.os.IInterface

interface IPackageInstallerSession : IInterface {
    abstract class Stub : Binder(), IPackageInstallerSession {
        companion object {
            fun asInterface(obj: IBinder): IPackageInstallerSession {
                val iin = obj.queryLocalInterface("android.content.pm.IPackageInstallerSession")
                if (iin != null && iin is IPackageInstallerSession) return iin
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, "android.content.pm.IPackageInstallerSession")
        }

        private class Proxy(val mRemote: IBinder) : IPackageInstallerSession {
            override fun asBinder(): IBinder = mRemote
        }
    }
}
