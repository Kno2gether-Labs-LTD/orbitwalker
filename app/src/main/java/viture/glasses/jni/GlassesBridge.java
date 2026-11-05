package viture.glasses.jni;

import viture.glasses.VitureLogger;
import viture.glasses.callback.General;
import viture.glasses.callback.Viture;

/**
 * JNI declarations for VITURE's libglasses-jni.so. Package, class and method signatures must
 * match what the library exports (Java_viture_glasses_jni_GlassesBridge_*) so the symbols bind.
 * Only the subset Orbit uses is declared.
 */
public final class GlassesBridge {
    static {
        System.loadLibrary("glasses-jni");
    }

    private GlassesBridge() {}

    public static native boolean nativeXRDeviceInitialize(int fd, String path, int productId);

    public static native void nativeXRDeviceStart();

    public static native void nativeXRDeviceStop();

    public static native void nativeXRDeviceRelease();

    public static native int nativeOpenImu(int mode, int frequency);

    public static native int nativeCloseImu(int mode);

    public static native void nativeRegisterVitureRawCallback(Viture.Raw raw);

    public static native void nativeUnregisterVitureRawCallback();

    public static native void nativeRegisterViturePoseCallback(Viture.Pose pose);

    public static native void nativeUnregisterViturePoseCallback();

    public static native void nativeRegisterStateCallback(General.State state);

    public static native void nativeUnregisterStateCallback();

    public static native void nativeSetLogHook(VitureLogger.LogHook logHook);

    public static native int nativeGetDeviceType();

    public static native String nativeGetGlassesVersion();

    /** Glasses display mode (Pro 2: 0x31 = 2D 1080p, 0x37 = 3D side-by-side). */
    public static native int nativeSetDisplayMode(int mode);

    public static native int nativeGetDisplayMode();
}
