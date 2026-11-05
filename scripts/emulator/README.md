# Emulator test kit

How OrbitWalker is tested without glasses, on an Android 14 arm64 emulator with Shizuku:

- **Simulated glasses:** `adb shell settings put global overlay_display_devices 1920x1080/240`
- **Start Shizuku:** `adb shell <shizuku apk dir>/lib/arm64/libshizuku.so`
- **`setup.sh`:** reinstall OrbitWalker and wait for Shizuku READY.
- **`t.sh <cmd> [am extras]`:** test-harness commands (DUMP-protected, adb only):
  - `status`
  - `open --es component pkg/cls`
  - `aim --ei slot N --ef u U --ef v V` (slot -1 = the in-glasses keyboard, -2 = the app dock, -3 = the title bar)
  - `click`, `press --ez down true|false`
  - `type --es text ...`, `key --ei code N`
  - `keyboard --ez on true`
  - `look --ef yaw Y --ef pitch P`
  - `frame`, `forceStereo`, `pixelPerfect`
  - `cinema [--es component c]`
  - `reclaim`, `closeAll`, `rawAnyBus --ez on true`, `eyebreak`, `background --es mode ORBIT|SPACE|LOGO|BLACK`
- **`frame.sh <name>`:** pulls exactly what the glasses show (PNG).
- **`bt.sh "<script>"`:** a virtual **Bluetooth** keyboard and mouse (uhid via `hid`):
  - `wait:MS`
  - `text:abc`
  - `key:USAGE[:MODS]`
  - `move:DX:DY:N`
  - `click`, `rclick`, `down`, `up`
  - `wheel:N`
