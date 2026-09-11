package android.content

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

interface IIntentReceiver : IInterface {
    @Throws(RemoteException::class)
    fun performReceive(
        intent: Intent, resultCode: Int, data: String?,
        extras: Bundle?, ordered: Boolean, sticky: Boolean, sendingUser: Int
    )

    abstract class Stub() : Binder(), IIntentReceiver {
        companion object {
            fun asInterface(obj: IBinder): IIntentReceiver {
                val iin = obj.queryLocalInterface("android.content.IIntentReceiver")
                if (iin != null && iin is IIntentReceiver) return iin
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, "android.content.IIntentReceiver")
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                data.enforceInterface("android.content.IIntentReceiver")
                val intent = if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null
                val resultCode = data.readInt()
                val dataStr = data.readString()
                val extras = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                val ordered = data.readInt() != 0
                val sticky = data.readInt() != 0
                val sendingUser = data.readInt()
                performReceive(intent!!, resultCode, dataStr, extras, ordered, sticky, sendingUser)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(val mRemote: IBinder) : IIntentReceiver {
            override fun performReceive(
                intent: Intent, resultCode: Int, data: String?,
                extras: Bundle?, ordered: Boolean, sticky: Boolean, sendingUser: Int
            ) {
                val _data = Parcel.obtain()
                try {
                    _data.writeInterfaceToken("android.content.IIntentReceiver")
                    _data.writeInt(1)
                    intent.writeToParcel(_data, 0)
                    _data.writeInt(resultCode)
                    _data.writeString(data)
                    if (extras != null) {
                        _data.writeInt(1)
                        extras.writeToParcel(_data, 0)
                    } else _data.writeInt(0)
                    _data.writeInt(if (ordered) 1 else 0)
                    _data.writeInt(if (sticky) 1 else 0)
                    _data.writeInt(sendingUser)
                    mRemote.transact(1, _data, null, IBinder.FLAG_ONEWAY)
                } finally {
                    _data.recycle()
                }
            }

            override fun asBinder(): IBinder = mRemote
        }
    }
}
