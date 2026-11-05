#!/bin/bash
# frame.sh <name> : pull exactly what the glasses show
A=$HOME/Library/Android/sdk/platform-tools/adb; S=$(dirname "$0")
$S/t.sh frame; sleep 1.5
$A pull /sdcard/Android/data/dev.avijit.orbit/files/frame.png $S/$1.png >/dev/null 2>&1 && sips -Z 1000 $S/$1.png >/dev/null && echo $S/$1.png
