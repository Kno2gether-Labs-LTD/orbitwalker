#!/bin/bash
# tap.sh "<text>" : taps the centre of the first UI node whose text matches
A=$HOME/Library/Android/sdk/platform-tools/adb; S=$(dirname "$0")
$A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $A pull /sdcard/ui.xml $S/ui.xml >/dev/null 2>&1
B=$(python3 -I - "$1" "$S/ui.xml" <<'PY'
import sys,re
t,f=sys.argv[1],sys.argv[2]
x=open(f).read()
for m in re.finditer(r'<node [^>]*>', x):
    n=m.group(0)
    if f'text="{t}"' in n or f'content-desc="{t}"' in n:
        b=re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups()
        print((int(b[0])+int(b[2]))//2, (int(b[1])+int(b[3]))//2); break
PY
)
if [ -z "$B" ]; then echo "NOT FOUND: $1"; exit 1; fi
$A shell input tap $B; echo "tapped $1 at $B"
