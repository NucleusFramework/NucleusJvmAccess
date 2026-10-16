#!/bin/bash
# Stages jdk-mobile's runtimes in the layout the NJA plugin reads (nucleusJvm.jdkMobile), one directory per target:
# static/ (the VM and the JDK's natives, as static archives), include/ (JNI) and lib/ (java.home).
#
#   stage-jdk-mobile.sh <stage dir> --release <tag>   (all targets, from NucleusFramework/openjdk-mobile's releases, e.g.
#                                                     jdk-mobile-27b35.3: from the first with inprocess.patch)
#   stage-jdk-mobile.sh <stage dir> android-aarch64 <OpenJDK build dir (build/android-aarch64-server-release)> <java.home>
#   stage-jdk-mobile.sh <stage dir> ios-simulator-aarch64|ios-device-aarch64 <extracted jdk-mobile release>
#
# Android needs jdk-mobile's static libraries built with inprocess.patch (in its releases since it ships it).
set -euo pipefail
STAGE=${1:?stage dir} TARGET=${2:?target}

if [ "$TARGET" = "--release" ]; then
    TAG=${3:?release tag}
    # jdk-mobile-<version>b<build>.<revision> -> assets jdk-mobile-<version>-<target>-b<build>.<revision>.tar.gz
    VERSION=${TAG#jdk-mobile-}; VERSION=${VERSION%%b*}; BUILD=${TAG##*b}
    DOWNLOADS=$STAGE/.downloads/$TAG
    mkdir -p "$DOWNLOADS"
    for target in android-aarch64 android-x64 ios-simulator-aarch64 ios-device-aarch64; do
        asset=jdk-mobile-$VERSION-$target-b$BUILD.tar.gz
        url=https://github.com/NucleusFramework/openjdk-mobile/releases/download/$TAG/$asset
        if [ ! -f "$DOWNLOADS/$asset" ]; then
            curl -sSfL -o "$DOWNLOADS/$asset.sha256" "$url.sha256"
            curl -sSfL -o "$DOWNLOADS/$asset" "$url"
            (cd "$DOWNLOADS" && shasum -a 256 -c "$asset.sha256" > /dev/null) || { echo "Bad checksum: $asset" >&2; exit 1; }
        fi
        rm -rf "$STAGE/$target" && mkdir -p "$STAGE/$target"
        tar xzf "$DOWNLOADS/$asset" -C "$STAGE/$target"
        [ -f "$STAGE/$target/static/libjvm.a" ] || { echo "$TAG's $target has no static/libjvm.a (needs inprocess.patch)" >&2; exit 1; }
        echo "$target: $(du -sh "$STAGE/$target" | cut -f1)"
    done
    exit 0
fi
OUT=$STAGE/$TARGET
mkdir -p "$OUT/static" "$OUT/include"
case "$TARGET" in
    android-*)
        BUILD=${3:?OpenJDK build dir} HOME_DIR=${4:?java.home}
        LIBS=$BUILD/images/static-libs/lib
        cp "$LIBS/server/libjvm.a" "$OUT/static/"
        for lib in java nio net zip jimage verify extnet syslookup management; do cp "$LIBS/lib$lib.a" "$OUT/static/"; done
        cp -R "$BUILD/jdk/include/." "$OUT/include/"
        rsync -a --delete "$HOME_DIR/" "$OUT/lib/"
        ;;
    ios-*)
        RELEASE=${3:?extracted release}
        rsync -a --delete "$RELEASE/static/" "$OUT/static/"
        rsync -a --delete "$RELEASE/include/" "$OUT/include/"
        rsync -a --delete "$RELEASE/lib/" "$OUT/lib/"
        ;;
    *) echo "Unknown target $TARGET" >&2; exit 1 ;;
esac
du -sh "$OUT"
