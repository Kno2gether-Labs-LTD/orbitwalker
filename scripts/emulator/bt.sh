#!/bin/bash
# bt.sh "<script>" : run a virtual Bluetooth keyboard+mouse session on the emulator (see btgen.py)
A=$HOME/Library/Android/sdk/platform-tools/adb; S=$(dirname "$0")
python3 -I $S/btgen.py $S/bts.json "$1" && $A push $S/bts.json /data/local/tmp/bts.json >/dev/null && $A shell hid /data/local/tmp/bts.json >/dev/null 2>&1
