#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SPIKE_DIR="$PROJECT_DIR/spike"
BUILD_DIR="$PROJECT_DIR/.vulkan-spike-build"
DIST_DIR="$PROJECT_DIR/dist"

SDK_ROOT="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$PROJECT_DIR/local.properties")}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK_ROOT/build-tools/36.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-35/android.jar}"
NDK_ROOT="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/27.2.12479018}"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
GLSLC="$NDK_ROOT/shader-tools/linux-x86_64/glslc"
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR" "$GLSLC"; do
    if [[ ! -f "$tool" ]]; then
        echo "Missing build dependency: $tool" >&2
        exit 1
    fi
done

if [[ ! -x "$TOOLCHAIN/aarch64-linux-android24-clang++" ]]; then
    echo "Missing NDK toolchain: $TOOLCHAIN" >&2
    exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storepass android \
        -alias androiddebugkey \
        -keypass android \
        -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" \
    "$BUILD_DIR/spv" "$BUILD_DIR/libs" "$DIST_DIR"

echo "Compiling resources..."
"$AAPT2" compile --dir "$SPIKE_DIR/res" -o "$BUILD_DIR/resources.zip"
"$AAPT2" link \
    -o "$BUILD_DIR/resources-unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$SPIKE_DIR/AndroidManifest.xml" \
    --java "$BUILD_DIR/gen" \
    --custom-package com.a65.grasswallpapers.vulkan \
    --min-sdk-version 24 \
    --target-sdk-version 35 \
    --auto-add-overlay \
    "$BUILD_DIR/resources.zip"

echo "Compiling SPIR-V shaders..."
for shader in sky grass dandelion particle; do
    "$GLSLC" --target-env=vulkan1.0 \
        -fshader-stage=vert \
        "$SPIKE_DIR/cpp/shaders/$shader.vert" \
        -o "$BUILD_DIR/spv/$shader.vert.spv"
    "$GLSLC" --target-env=vulkan1.0 \
        -fshader-stage=frag \
        "$SPIKE_DIR/cpp/shaders/$shader.frag" \
        -o "$BUILD_DIR/spv/$shader.frag.spv"
done
{
    echo '#pragma once'
    for shader in sky grass dandelion particle; do
        xxd -i -n "${shader}_vert_spv" "$BUILD_DIR/spv/$shader.vert.spv"
        xxd -i -n "${shader}_frag_spv" "$BUILD_DIR/spv/$shader.frag.spv"
    done
} > "$BUILD_DIR/spv/shaders_spv.h"

echo "Compiling Java..."
find "$SPIKE_DIR/src" "$BUILD_DIR/gen" -name '*.java' -print | sort \
    > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 --release 17 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

mapfile -d '' class_files < <(
    find "$BUILD_DIR/classes" -name '*.class' -print0
)
"$D8" --release --min-api 24 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" \
    "${class_files[@]}"

echo "Compiling Vulkan native libraries..."
for abi in arm64-v8a armeabi-v7a; do
    case "$abi" in
        arm64-v8a) compiler="$TOOLCHAIN/aarch64-linux-android24-clang++" ;;
        armeabi-v7a) compiler="$TOOLCHAIN/armv7a-linux-androideabi24-clang++" ;;
    esac
    mkdir -p "$BUILD_DIR/libs/lib/$abi"
    "$compiler" \
        -std=c++17 \
        -O2 \
        -fPIC \
        -fvisibility=hidden \
        -Wall \
        -Wextra \
        -Werror \
        -Wno-missing-field-initializers \
        -shared \
        -static-libstdc++ \
        -I "$BUILD_DIR/spv" \
        -Wl,-z,max-page-size=16384 \
        -o "$BUILD_DIR/libs/lib/$abi/libvulkanspike.so" \
        "$SPIKE_DIR/cpp/vulkan_spike.cpp" \
        -lvulkan \
        -landroid \
        -llog
done

cp "$BUILD_DIR/resources-unsigned.apk" "$BUILD_DIR/unsigned.apk"
(
    cd "$BUILD_DIR/dex"
    zip -q -j "$BUILD_DIR/unsigned.apk" classes.dex
)
(
    cd "$BUILD_DIR/libs"
    zip -q -r "$BUILD_DIR/unsigned.apk" lib
)

"$ZIPALIGN" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$DIST_DIR/A65_Grass_Vulkan_Wallpaper.apk" \
    "$BUILD_DIR/aligned.apk"

"$APKSIGNER" verify --verbose "$DIST_DIR/A65_Grass_Vulkan_Wallpaper.apk" > /dev/null
"$ZIPALIGN" -c -v 4 "$DIST_DIR/A65_Grass_Vulkan_Wallpaper.apk" > /dev/null

echo "Vulkan spike APK:"
sha256sum "$DIST_DIR/A65_Grass_Vulkan_Wallpaper.apk"
