/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\\Users\\Alessandro\\AppData\\Local\\Android\\Sdk\\build-tools\\36.0.0\\aidl.exe -pC:\\Users\\Alessandro\\AppData\\Local\\Android\\Sdk\\platforms\\android-37.0\\framework.aidl -oC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\build\\generated\\aidl_source_output_dir\\debug\\out -IC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\main\\aidl -IC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\debug\\aidl -IC:\\Users\\Alessandro\\.gradle\\caches\\9.4.1\\transforms\\786fcd9771877fd34bc09ef3320c5d6c\\transformed\\core-1.19.0\\aidl -IC:\\Users\\Alessandro\\.gradle\\caches\\9.4.1\\transforms\\2e1b6415b557541bdbdffd8cae3b7c67\\transformed\\versionedparcelable-1.1.1\\aidl -dC:\\Users\\ALESSA~1\\AppData\\Local\\Temp\\aidl17373420776332964543.d C:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\main\\aidl\\de\\lobianco\\saftssh\\linux\\ILinuxSession.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package de.lobianco.saftssh.linux;
public interface ILinuxSession extends android.os.IInterface
{
  /** Default implementation for ILinuxSession. */
  public static class Default implements de.lobianco.saftssh.linux.ILinuxSession
  {
    @Override public android.os.ParcelFileDescriptor getPtyFd() throws android.os.RemoteException
    {
      return null;
    }
    @Override public int getPid() throws android.os.RemoteException
    {
      return 0;
    }
    @Override public void resize(int cols, int rows) throws android.os.RemoteException
    {
    }
    @Override public void destroy() throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements de.lobianco.saftssh.linux.ILinuxSession
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an de.lobianco.saftssh.linux.ILinuxSession interface,
     * generating a proxy if needed.
     */
    public static de.lobianco.saftssh.linux.ILinuxSession asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof de.lobianco.saftssh.linux.ILinuxSession))) {
        return ((de.lobianco.saftssh.linux.ILinuxSession)iin);
      }
      return new de.lobianco.saftssh.linux.ILinuxSession.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_getPtyFd:
        {
          android.os.ParcelFileDescriptor _result = this.getPtyFd();
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_getPid:
        {
          int _result = this.getPid();
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_resize:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          this.resize(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_destroy:
        {
          this.destroy();
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements de.lobianco.saftssh.linux.ILinuxSession
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      @Override public android.os.ParcelFileDescriptor getPtyFd() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        android.os.ParcelFileDescriptor _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPtyFd, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, android.os.ParcelFileDescriptor.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public int getPid() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getPid, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void resize(int cols, int rows) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(cols);
          _data.writeInt(rows);
          boolean _status = mRemote.transact(Stub.TRANSACTION_resize, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void destroy() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_destroy, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_getPtyFd = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getPid = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_resize = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_destroy = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "de.lobianco.saftssh.linux.ILinuxSession";
  public android.os.ParcelFileDescriptor getPtyFd() throws android.os.RemoteException;
  public int getPid() throws android.os.RemoteException;
  public void resize(int cols, int rows) throws android.os.RemoteException;
  public void destroy() throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
