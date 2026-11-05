package viture.glasses.callback;

/** Callback shape expected by libglasses-jni.so (the signature the native library binds to). */
public final class General {
    private General() {}

    public interface State {
        void onStateChange(int state, int value);
    }
}
