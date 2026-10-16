#!/bin/bash
# Links HotSpot, the JDK's natives and the NJA bridge into one shared library an Android app loads:
#   link.sh <static libs dir> <JDK include dir> <out .so> [aarch64|x86_64]
set -euo pipefail
LIBS=$1 INCLUDE=$2 OUT=$3 ARCH=${4:-aarch64}
HERE=$(cd "$(dirname "$0")" && pwd)
NDK=${ANDROID_NDK_HOME:-$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/ndk/* | sort | tail -1)}
TC=$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)/bin
mkdir -p "$(dirname "$OUT")"
"$TC/$ARCH-linux-android29-clang++" -shared -fPIC -O2 -Wall -x c -std=c11 \
    -I"$INCLUDE" -I"$INCLUDE/android" "$HERE/src/nja_android.c" -x none -o "$OUT" \
    -Wl,--whole-archive "$LIBS/server/libjvm.a" "$LIBS/libjava.a" "$LIBS/libnio.a" "$LIBS/libnet.a" "$LIBS/libzip.a" \
    "$LIBS/libjimage.a" "$LIBS/libverify.a" "$LIBS/libextnet.a" "$LIBS/libsyslookup.a" "$LIBS/libmanagement.a" \
    -Wl,--no-whole-archive \
    -Wl,-Bsymbolic -Wl,-z,max-page-size=16384 -Wl,-z,noexecstack -Wl,--no-undefined \
    -static-libstdc++ -lz -ldl -llog -lm
"$TC/llvm-strip" --strip-debug "$OUT"
