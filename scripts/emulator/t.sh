#!/bin/bash
# t.sh <cmd> [extra am args...] : send a test-harness command to OrbitWalker
A=$HOME/Library/Android/sdk/platform-tools/adb
cmd=$1; shift
$A shell am broadcast -a dev.avijit.orbit.TEST -n dev.avijit.orbit/.diag.TestHarness --es cmd "$cmd" "$@" >/dev/null
