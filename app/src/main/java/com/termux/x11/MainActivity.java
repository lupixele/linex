package com.termux.x11;

/** JNI callback type retained for compatibility with the embedded Termux:X11 renderer. */
public abstract class MainActivity extends android.app.Activity {
    public abstract void clientConnectedStateChanged();
}
