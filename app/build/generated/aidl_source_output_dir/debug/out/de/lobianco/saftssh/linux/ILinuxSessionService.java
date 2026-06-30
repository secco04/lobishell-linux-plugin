/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\\Users\\Alessandro\\AppData\\Local\\Android\\Sdk\\build-tools\\36.0.0\\aidl.exe -pC:\\Users\\Alessandro\\AppData\\Local\\Android\\Sdk\\platforms\\android-37.0\\framework.aidl -oC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\build\\generated\\aidl_source_output_dir\\debug\\out -IC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\main\\aidl -IC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\debug\\aidl -IC:\\Users\\Alessandro\\.gradle\\caches\\9.4.1\\transforms\\786fcd9771877fd34bc09ef3320c5d6c\\transformed\\core-1.19.0\\aidl -IC:\\Users\\Alessandro\\.gradle\\caches\\9.4.1\\transforms\\2e1b6415b557541bdbdffd8cae3b7c67\\transformed\\versionedparcelable-1.1.1\\aidl -dC:\\Users\\ALESSA~1\\AppData\\Local\\Temp\\aidl1790445827851287593.d C:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\main\\aidl\\de\\lobianco\\saftssh\\linux\\ILinuxSessionService.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package de.lobianco.saftssh.linux;
public interface ILinuxSessionService extends android.os.IInterface
{
  /** Default implementation for ILinuxSessionService. */
  public static class Default implements de.lobianco.saftssh.linux.ILinuxSessionService
  {
    /**
     * Create a PTY-backed Linux session for the userland [userlandId] (one rootfs per connection).
     * [callback] receives setup progress (may be null).
     */
    @Override public de.lobianco.saftssh.linux.ILinuxSession createSession(int cols, int rows, java.lang.String cwd, java.lang.String userlandId, de.lobianco.saftssh.linux.ILinuxSessionCallback callback) throws android.os.RemoteException
    {
      return null;
    }
    /** Ids of all installed userlands. */
    @Override public java.lang.String[] listUserlandIds() throws android.os.RemoteException
    {
      return null;
    }
    /** Size in bytes of one userland (rootfs + tmp). */
    @Override public long userlandSize(java.lang.String userlandId) throws android.os.RemoteException
    {
      return 0L;
    }
    /** Delete one userland. Returns bytes freed. */
    @Override public long clearUserland(java.lang.String userlandId) throws android.os.RemoteException
    {
      return 0L;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements de.lobianco.saftssh.linux.ILinuxSessionService
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an de.lobianco.saftssh.linux.ILinuxSessionService interface,
     * generating a proxy if needed.
     */
    public static de.lobianco.saftssh.linux.ILinuxSessionService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof de.lobianco.saftssh.linux.ILinuxSessionService))) {
        return ((de.lobianco.saftssh.linux.ILinuxSessionService)iin);
      }
      return new de.lobianco.saftssh.linux.ILinuxSessionService.Stub.Proxy(obj);
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
        case TRANSACTION_createSession:
        {
          int _arg0;
          _arg0 = data.readInt();
          int _arg1;
          _arg1 = data.readInt();
          java.lang.String _arg2;
          _arg2 = data.readString();
          java.lang.String _arg3;
          _arg3 = data.readString();
          de.lobianco.saftssh.linux.ILinuxSessionCallback _arg4;
          _arg4 = de.lobianco.saftssh.linux.ILinuxSessionCallback.Stub.asInterface(data.readStrongBinder());
          de.lobianco.saftssh.linux.ILinuxSession _result = this.createSession(_arg0, _arg1, _arg2, _arg3, _arg4);
          reply.writeNoException();
          reply.writeStrongInterface(_result);
          break;
        }
        case TRANSACTION_listUserlandIds:
        {
          java.lang.String[] _result = this.listUserlandIds();
          reply.writeNoException();
          reply.writeStringArray(_result);
          break;
        }
        case TRANSACTION_userlandSize:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          long _result = this.userlandSize(_arg0);
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_clearUserland:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          long _result = this.clearUserland(_arg0);
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements de.lobianco.saftssh.linux.ILinuxSessionService
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
      /**
       * Create a PTY-backed Linux session for the userland [userlandId] (one rootfs per connection).
       * [callback] receives setup progress (may be null).
       */
      @Override public de.lobianco.saftssh.linux.ILinuxSession createSession(int cols, int rows, java.lang.String cwd, java.lang.String userlandId, de.lobianco.saftssh.linux.ILinuxSessionCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        de.lobianco.saftssh.linux.ILinuxSession _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(cols);
          _data.writeInt(rows);
          _data.writeString(cwd);
          _data.writeString(userlandId);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_createSession, _data, _reply, 0);
          _reply.readException();
          _result = de.lobianco.saftssh.linux.ILinuxSession.Stub.asInterface(_reply.readStrongBinder());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Ids of all installed userlands. */
      @Override public java.lang.String[] listUserlandIds() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        java.lang.String[] _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_listUserlandIds, _data, _reply, 0);
          _reply.readException();
          _result = _reply.createStringArray();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Size in bytes of one userland (rootfs + tmp). */
      @Override public long userlandSize(java.lang.String userlandId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(userlandId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_userlandSize, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      /** Delete one userland. Returns bytes freed. */
      @Override public long clearUserland(java.lang.String userlandId) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(userlandId);
          boolean _status = mRemote.transact(Stub.TRANSACTION_clearUserland, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_createSession = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_listUserlandIds = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_userlandSize = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_clearUserland = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "de.lobianco.saftssh.linux.ILinuxSessionService";
  /**
   * Create a PTY-backed Linux session for the userland [userlandId] (one rootfs per connection).
   * [callback] receives setup progress (may be null).
   */
  public de.lobianco.saftssh.linux.ILinuxSession createSession(int cols, int rows, java.lang.String cwd, java.lang.String userlandId, de.lobianco.saftssh.linux.ILinuxSessionCallback callback) throws android.os.RemoteException;
  /** Ids of all installed userlands. */
  public java.lang.String[] listUserlandIds() throws android.os.RemoteException;
  /** Size in bytes of one userland (rootfs + tmp). */
  public long userlandSize(java.lang.String userlandId) throws android.os.RemoteException;
  /** Delete one userland. Returns bytes freed. */
  public long clearUserland(java.lang.String userlandId) throws android.os.RemoteException;
}
