package viture.glasses;

/** Log hook shape expected by libglasses-jni.so (the signature the native library binds to). */
public final class VitureLogger {
    private VitureLogger() {}

    public interface LogHook {
        void onLog(int level, byte[] tag, byte[] message);
    }
}
