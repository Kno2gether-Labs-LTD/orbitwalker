package dev.avijit.orbit.shell;

import android.view.Surface;
import dev.avijit.orbit.shell.IRawInputListener;

/** Runs as the shell user inside a Shizuku UserService process. */
interface IOrbitShell {
    /** Display id (>= 0) or a negative error; see lastError(). */
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    /** Output of `am start`. */
    String launch(int displayId, String component) = 2;
    boolean tap(int displayId, float x, float y) = 3;
    boolean scroll(int displayId, float x, float y, float vscroll) = 4;
    boolean key(int displayId, int keyCode) = 5;
    void releaseDisplay(int displayId) = 6;
    String lastError() = 7;
    String info() = 8;
    /** Types [text] into the focused window of [displayId]. */
    boolean text(int displayId, String text) = 9;
    /** Injects one raw key event (BT keyboard passthrough). */
    boolean keyEvent(int displayId, int action, int keyCode, int metaState, int repeat) = 10;
    /** Like keyEvent, keeping the original event's timestamps (consistent DOWN/UP pairs, key repeat). */
    /**
     * Reads external mice and keyboards directly (/dev/input) and, with [grab], takes them over
     * exclusively so Android doesn't also act on them. Returns the number of devices opened.
     */
    int startRawInput(IRawInputListener listener, boolean grab) = 13;
    void stopRawInput() = 14;
    /** Devices the raw reader has open, or -1 when it isn't running (health check). */
    int rawInputStatus() = 18;
    /** Tests only: also read non-USB/Bluetooth devices (e.g. the emulator's keyboard). */
    void setRawAnyBus(boolean on) = 19;
    /** App tasks on [displayId] (0 = the app closed itself), or -1 if unknown. */
    int taskCount(int displayId) = 20;
    /**
     * A key exactly as a hardware keyboard would send it: with its scan code and the id of the
     * input device it came from (some apps, e.g. Flutter-based ones, rely on these).
     */
    boolean keyEventFull(int displayId, int action, int keyCode, int metaState, int repeat, long downTime, long eventTime, int scanCode, int deviceId) = 16;
    /** One touch event (DOWN 0 / UP 1 / MOVE 2) at (x, y), for mouse press, drag and long-press. */
    boolean touch(int displayId, int action, float x, float y, long downTime) = 17;
    boolean keyEventTimed(int displayId, int action, int keyCode, int metaState, int repeat, long downTime, long eventTime) = 12;
    /** IME placement for [displayId]: 1 = keyboard shows on that screen, 0 = unsupported, -1 = error. */
    int imePolicy(int displayId) = 11;
    /** Shizuku's reserved transaction for stopping the service. */
    void destroy() = 16777114;
}
