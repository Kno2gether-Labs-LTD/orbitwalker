#!/bin/bash
# setup.sh : (re)install OrbitWalker, start it, wait for the helper (or Shizuku) to be READY
A=$HOME/Library/Android/sdk/platform-tools/adb
$A install -r "$(cd "$(dirname "$0")/../.." && pwd)/app/build/outputs/apk/debug/app-debug.apk" | tail -1
$A shell "run-as dev.avijit.orbit mkdir -p files && run-as dev.avijit.orbit touch files/test-hooks"   # enable adb-only test hooks
$A shell am start -n dev.avijit.orbit/.MainActivity >/dev/null
for i in $(seq 1 20); do sleep 1; $A logcat -d | grep -qE "(helper|Shizuku): READY" && break; done
$A logcat -d | grep -E "(helper|Shizuku): READY" | tail -1
