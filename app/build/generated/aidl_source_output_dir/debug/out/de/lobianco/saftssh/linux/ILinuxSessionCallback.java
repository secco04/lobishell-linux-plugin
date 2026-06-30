/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\\Users\\Alessandro\\AppData\\Local\\Android\\Sdk\\build-tools\\36.0.0\\aidl.exe -pC:\\Users\\Alessandro\\AppData\\Local\\Android\\Sdk\\platforms\\android-37.0\\framework.aidl -oC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\build\\generated\\aidl_source_output_dir\\debug\\out -IC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\main\\aidl -IC:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\debug\\aidl -IC:\\Users\\Alessandro\\.gradle\\caches\\9.4.1\\transforms\\786fcd9771877fd34bc09ef3320c5d6c\\transformed\\core-1.19.0\\aidl -IC:\\Users\\Alessandro\\.gradle\\caches\\9.4.1\\transforms\\2e1b6415b557541bdbdffd8cae3b7c67\\transformed\\versionedparcelable-1.1.1\\aidl -dC:\\Users\\ALESSA~1\\AppData\\Local\\Temp\\aidl7075595458380577086.d C:\\Users\\Alessandro\\AndroidStudioProjects\\linux-plugin\\app\\src\\main\\aidl\\de\\lobianco\\saftssh\\linux\\ILinuxSessionCallback.aidl
 *
 * DO NOT CHECK THIS FILE INTO A CODE TREE (e.g. git, etc..).
 * ALWAYS GENERATE THIS FILE FROM UPDATED AIDL COMPILER
 * AS A BUILD INTERMEDIATE ONLY. THIS IS NOT SOURCE CODE.
 */
package de.lobianco.saftssh.linux;
/** Progress notifications during session setup (rootfs download/extract), shown in the terminal. */
public interface ILinuxSessionCallback extends android.os.IInterface
{
  /** Default implementation for ILinuxSessionCallback. */
  public static class Default implements de.lobianco.saftssh.linux.ILinuxSessionCallback
  {
    @Override public void onProgress(java.lang.String line) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements de.lobianco.saftssh.linux.ILinuxSessionCallback
  {
    /** Construct the stub and attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an de.lobianco.saftssh.linux.ILinuxSessionCallback interface,
     * generating a proxy if needed.
     */
    public static de.lobianco.saftssh.linux.ILinuxSessionCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof de.lobianco.saftssh.linux.ILinuxSessionCallback))) {
        return ((de.lobianco.saftssh.linux.ILinuxSessionCallback)iin);
      }
      return new de.lobianco.saftssh.linux.ILinuxSessionCallback.Stub.Proxy(obj);
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
        case TRANSACTION_onProgress:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          this.onProgress(_arg0);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements de.lobianco.saftssh.linux.ILinuxSessionCallback
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
      @Override public void onProgress(java.lang.String line) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(line);
          boolean _status = mRemote.transact(Stub.TRANSACTION_onProgress, _data, null, android.os.IBinder.FLAG_ONEWAY);
        }
        finally {
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_onProgress = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "de.lobianco.saftssh.linux.ILinuxSessionCallback";
  public void onProgress(java.lang.String line) throws android.os.RemoteException;
}
