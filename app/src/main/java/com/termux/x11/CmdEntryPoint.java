package com.termux.x11;

import android.os.ParcelFileDescriptor;
import dalvik.annotation.optimization.CriticalNative;

/** The X server entry point. Invoke only in Linex's dedicated X11 service process. */
public final class CmdEntryPoint {
    static { System.loadLibrary("Xlorie"); }
    private final Runnable onConnectionRequested;

    public CmdEntryPoint(Runnable onConnectionRequested) {
        this.onConnectionRequested = onConnectionRequested;
    }

    public native boolean start(String[] arguments);
    public native ParcelFileDescriptor getXConnection();
    public native ParcelFileDescriptor getLogcatOutput();
    public native void reportFatalError(String message);
    @CriticalNative private static native boolean connected();

    // Called on the native X server thread; callback implementations must marshal as needed.
    public void sendBroadcast() { onConnectionRequested.run(); }
    public void sendBroadcastDelayed() { onConnectionRequested.run(); }
}
