package dev.avijit.orbit.shell;

/** Raw Bluetooth/USB mouse and keyboard events read by the shell helper (app side). */
oneway interface IRawInputListener {
    /** One mouse report: relative motion, buttons (bit0 left, bit1 right, bit2 middle), wheel notches. */
    void onMouse(int dx, int dy, int buttons, int wheel);
    /** One key: Linux key code, value 1 = down, 0 = up, 2 = repeat. */
    void onKey(int linuxCode, int value);
    /** Human-readable list of the devices being read (for diagnostics). */
    void onDevices(String description);
}
