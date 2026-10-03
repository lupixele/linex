package com.termux.x11;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.nio.charset.StandardCharsets;
import dalvik.annotation.optimization.CriticalNative;

/**
 * Small host adapter for Termux:X11's EGL renderer. JNI names and signatures intentionally
 * match upstream; desktop controls belong to Linex rather than upstream's activity UI.
 * All public methods and release() must be called from Android's main thread.
 */
public class LorieView extends SurfaceView implements SurfaceHolder.Callback {
    static { System.loadLibrary("Xlorie"); }
    // Upstream JNI reads this exact field. Linex handles connection state through its service.
    @SuppressWarnings("unused") private final MainActivity activity = null;
    private long nativeContext;
    private int desktopWidth = 1280;
    private int desktopHeight = 720;
    private int frameRate = 60;
    private boolean renderingVisible = true;
    private boolean clipboardEnabled = false;

    public LorieView(Context context) {
        super(context);
        nativeContext = nativeInit();
        holderCallbacks();
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private void holderCallbacks() { getHolder().addCallback(this); }

    public void attachConnection(ParcelFileDescriptor descriptor) {
        if (nativeContext == 0) throw new IllegalStateException("Renderer was released");
        // Native renderer owns the descriptor after detachFd, and closes it on disconnect.
        connect(nativeContext, descriptor.detachFd());
        setClipboardSyncEnabled(nativeContext, clipboardEnabled, false);
        sendWindowChange(nativeContext, desktopWidth, desktopHeight, frameRate, "Linex");
    }

    public boolean isConnected() {
        return nativeContext != 0 && connected(nativeContext);
    }

    public void configureDesktop(int width, int height, int fps) {
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535 || fps < 1 || fps > 240)
            throw new IllegalArgumentException("Invalid desktop geometry or frame rate");
        desktopWidth = width;
        desktopHeight = height;
        frameRate = fps;
        if (nativeContext != 0) {
            sendWindowChange(nativeContext, width, height, fps, "Linex");
            setViewport(nativeContext, 0, 0, getWidth(), getHeight(), width, height, 0);
        }
    }

    public void pointer(float x, float y, int button, boolean down, boolean relative) {
        if (nativeContext != 0) sendMouseEvent(nativeContext, x, y, button, down, relative);
    }

    public boolean key(int scanCode, int keyCode, boolean down) {
        return nativeContext != 0 && sendKeyEvent(nativeContext, scanCode, keyCode, down);
    }

    public void text(String value) {
        if (nativeContext != 0) sendTextEvent(nativeContext, value.getBytes(StandardCharsets.UTF_8));
    }

    public void release() {
        if (nativeContext == 0) return;
        getHolder().removeCallback(this);
        surfaceChanged(nativeContext, null);
        long context = nativeContext;
        nativeContext = 0;
        nativeDestroy(context);
    }

    /** Detach the Android surface while backgrounded, leaving the guest connection alive. */
    public void setRenderingVisible(boolean visible) {
        renderingVisible = visible;
        if (nativeContext == 0) return;
        Surface surface = getHolder().getSurface();
        surfaceChanged(nativeContext, visible && surface.isValid() ? surface : null);
        setViewport(nativeContext, 0, 0, getWidth(), getHeight(), desktopWidth, desktopHeight, visible ? 0 : 1);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        if (nativeContext != 0 && renderingVisible) surfaceChanged(nativeContext, holder.getSurface());
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (nativeContext == 0) return;
        surfaceChanged(nativeContext, renderingVisible ? holder.getSurface() : null);
        setViewport(nativeContext, 0, 0, width, height, desktopWidth, desktopHeight, renderingVisible ? 0 : 1);
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        if (nativeContext != 0) surfaceChanged(nativeContext, null);
    }

    // Native callbacks. Clipboard is opt-in and disabled by default.
    public void resetIme() { }
    public void onSyncReply(int serial) { }
    public void setRendererViewport(int x, int y, int width, int height,
                                    float sourceX, float sourceY, float scaleX, float scaleY) { }
    public void setClipboardText(String value) {
        if (!clipboardEnabled || nativeContext == 0) return;
        ClipboardManager manager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("Linux", value));
    }
    public void requestClipboard() {
        if (nativeContext != 0 && clipboardEnabled) sendClipboardEvent(nativeContext, new byte[0]);
    }

    // Every method registered by upstream JNI_OnLoad must exist, even unused optional APIs.
    private native long nativeInit();
    private native void nativeDestroy(long ptr);
    private native void surfaceChanged(long ptr, Surface surface);
    private native void setFiltering(long ptr, int filtering);
    private native void setClipboardSyncEnabled(long ptr, boolean enabled, boolean ignored);
    private native void sendClipboardAnnounce(long ptr);
    private native void sendClipboardEvent(long ptr, byte[] text);
    private native void sendWindowChange(long ptr, int width, int height, int fps, String name);
    private native void setViewport(long ptr, int x, int y, int w, int h, int expectedW, int expectedH, int hidden);
    private native void setRendererZoom(long ptr, int percent);
    private native void setZoomAnchor(long ptr, float x, float y, float fracX, float fracY);
    private native void clearZoomAnchor(long ptr);
    private native void setFollowCursorPan(long ptr, boolean enabled);
    private native long getCursorPosition(long ptr);
    private native void sendSync(long ptr, int serial);
    private static native void connect(long ptr, int fd);
    @CriticalNative private static native boolean connected(long ptr);
    private static native void startLogcat(long ptr, int fd);
    private native void sendMouseEvent(long ptr, float x, float y, int button, boolean down, boolean relative);
    private native void sendTouchEvent(long ptr, int action, int id, int x, int y);
    private native void sendStylusEvent(long ptr, float x, float y, int pressure, int tiltX, int tiltY,
                                      int orientation, int buttons, boolean eraser, boolean mouse);
    private native void requestStylusEnabled(long ptr, boolean enabled);
    private native void sendLockKeysState(long ptr, int state);
    private native boolean sendKeyEvent(long ptr, int scanCode, int keyCode, boolean down);
    private native void sendTextEvent(long ptr, byte[] text);
    @CriticalNative private static native boolean requestConnection(long ptr);
    @CriticalNative public static native long getLastInputTimestamp();
    @CriticalNative public static native void markUserActivity();
}
