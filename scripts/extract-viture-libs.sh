#!/usr/bin/env bash
# Copies VITURE's native glasses libraries out of the user's own SpaceWalker.apk
# into app/src/main/jniLibs/arm64-v8a (gitignored). Never commit these files.
set -euo pipefail

APK="${1:?usage: extract-viture-libs.sh /path/to/SpaceWalker.apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

SYSTEM_LIBS="libc.so libm.so libdl.so liblog.so libandroid.so libEGL.so libGLESv2.so libGLESv3.so libz.so libmediandk.so libvulkan.so libstdc++.so libnativewindow.so libsync.so libOpenSLES.so libjnigraphics.so libcamera2ndk.so libbinder_ndk.so"
WANT=(libglasses-jni.so libglasses-internal.so libcarina_vio.so libcloud_protocol.so libc++_shared.so)

mkdir -p "$OUT"
unzip -q -o "$APK" 'lib/arm64-v8a/*' -d "$TMP"
SRC="$TMP/lib/arm64-v8a"
if [ -n "$(find "$TMP" -type l)" ]; then
  echo "ERROR: APK contains symlinks under lib/; refusing to continue" >&2
  exit 1
fi

is_system() { case " $SYSTEM_LIBS " in *" $1 "*) return 0;; esac; return 1; }
valid_name() { [[ "$1" =~ ^lib[A-Za-z0-9_+.-]+\.so$ ]] && [[ "$1" != *..* ]]; }

queue=("${WANT[@]}")
copied=()
while [ ${#queue[@]} -gt 0 ]; do
  lib="${queue[0]}"; queue=("${queue[@]:1}")
  case " ${copied[*]-} " in *" $lib "*) continue;; esac
  if ! valid_name "$lib"; then
    echo "ERROR: refusing unexpected library name: $lib" >&2
    exit 1
  fi
  if [ ! -f "$SRC/$lib" ] || [ -L "$SRC/$lib" ]; then
    echo "ERROR: $lib is required but not present in $APK" >&2
    exit 1
  fi
  cp "$SRC/$lib" "$OUT/$lib"
  copied+=("$lib")
  # Hard dependencies: the ELF DT_NEEDED entries.
  while IFS= read -r dep; do
    [ "$dep" = "$lib" ] && continue
    is_system "$dep" && continue
    if [ -f "$SRC/$dep" ]; then
      queue+=("$dep")
    else
      echo "ERROR: $lib depends on $dep, which is neither a system library nor in the APK" >&2
      exit 1
    fi
  done < <(python3 -I "$ROOT/scripts/elf_needed.py" "$SRC/$lib")
  # Optional (dlopen'd) libraries are only reported.
  while IFS= read -r dep; do
    is_system "$dep" && continue
    [ -f "$SRC/$dep" ] || echo "note: $lib mentions $dep (not in APK; assumed optional dlopen)"
  done < <(strings -a "$SRC/$lib" | grep -E '^lib[A-Za-z0-9_+.-]+\.so$' | sort -u)
done

echo "Copied ${#copied[@]} libraries to $OUT:"
printf '  %s\n' "${copied[@]}"
