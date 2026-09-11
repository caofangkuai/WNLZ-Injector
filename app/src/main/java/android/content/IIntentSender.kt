package android.content

import android.content.IIntentReceiver
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

interface IIntentSender : IInterface {
    @Throws(RemoteException::class)
    fun send(
        code: Int,
        intent: Intent?,
        resolvedType: String?,
        whitelistToken: IBinder?,
        finishedReceiver: IIntentReceiver?,
        requiredPermission: String?,
        options: Bundle?
    )

    abstract class Stub() : Binder(), IIntentSender {
        companion object {
            fun asInterface(obj: IBinder): IIntentSender {
                val iin = obj.queryLocalInterface("android.content.IIntentSender")
                if (iin != null && iin is IIntentSender) return iin
                return Proxy(obj)
            }
        }

        init {
            attachInterface(this, "android.content.IIntentSender")
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val descriptor = "android.content.IIntentSender"
            when (code) {
                1 -> {
                    data.enforceInterface(descriptor)
                    val intent = if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null
                    val resolvedType = data.readString()
                    val receiver = if (data.readInt() != 0) IIntentReceiver.Stub.asInterface(data.readStrongBinder()) else null
                    val requiredPermission = data.readString()
                    val options = if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    send(code, intent, resolvedType, null, receiver, requiredPermission, options)
                    return true
                }
                0x5F4E5446 -> {
                    reply?.writeString(descriptor)
                    return true
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
        }

        private class Proxy(val mRemote: IBinder) : IIntentSender {
            override fun send(
                code: Int, intent: Intent?, resolvedType: String?,
                whitelistToken: IBinder?, finishedReceiver: IIntentReceiver?,
                requiredPermission: String?, options: Bundle?
            ) {
                val _data = Parcel.obtain()
                val _reply = Parcel.obtain()
                try {
                    _data.writeInterfaceToken("android.content.IIntentSender")
                    _data.writeInt(code)
                    if (intent != null) {
                        _data.writeInt(1)
                        intent.writeToParcel(_data, 0)
                    } else _data.writeInt(0)
                    _data.writeString(resolvedType)
                    if (finishedReceiver != null) {
                        _data.writeInt(1)
                        _data.writeStrongBinder(finishedReceiver.asBinder())
                    } else _data.writeInt(0)
                    _data.writeString(requiredPermission)
                    if (options != null) {
                        _data.writeInt(1)
                        options.writeToParcel(_data, 0)
                    } else _data.writeInt(0)
                    mRemote.transact(1, _data, _reply, IBinder.FLAG_ONEWAY)
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
