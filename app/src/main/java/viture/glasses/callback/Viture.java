package viture.glasses.callback;

/** Callback shapes expected by libglasses-jni.so (the signatures the native library binds to). */
public final class Viture {
    private Viture() {}

    public interface Pose {
        void onImuPoseData(float[] pose, long timestamp);
    }

    public interface Raw {
        void onImuRawData(float[] imu, long timestamp, long vsync);
    }
}
