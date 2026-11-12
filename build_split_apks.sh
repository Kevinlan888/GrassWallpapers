#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$PROJECT_DIR/app/src/main"
SPLIT_DIR="$PROJECT_DIR/split"
DIST_DIR="$PROJECT_DIR/dist"
BUILD_DIR="$PROJECT_DIR/.split-build"

SDK_ROOT="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' "$PROJECT_DIR/local.properties")}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK_ROOT/build-tools/36.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK_ROOT/platforms/android-35/android.jar}"
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
    if [[ ! -f "$tool" ]]; then
        echo "Missing build dependency: $tool" >&2
        exit 1
    fi
done

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
mkdir -p "$BUILD_DIR" "$DIST_DIR"

echo "Compiling resources..."
"$AAPT2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/resources.zip"

for variant in aosp mtk; do
    case "$variant" in
        aosp) output_name="A65_Grass_AOSP_Android15.apk" ;;
        mtk) output_name="A65_Grass_MTK_Android15.apk" ;;
    esac

    variant_build="$BUILD_DIR/$variant"
    mkdir -p "$variant_build/gen" "$variant_build/classes" "$variant_build/dex"

    echo "Linking $variant manifest..."
    "$AAPT2" link \
        -o "$variant_build/resources-unsigned.apk" \
        -I "$ANDROID_JAR" \
        --manifest "$SPLIT_DIR/$variant/AndroidManifest.xml" \
        --java "$variant_build/gen" \
        --custom-package com.a65.grasswallpapers \
        --auto-add-overlay \
        "$BUILD_DIR/resources.zip"

    find "$APP_DIR/java" "$variant_build/gen" -name '*.java' -print | sort \
        > "$variant_build/sources.txt"
    javac -encoding UTF-8 --release 17 \
        -cp "$ANDROID_JAR" \
        -d "$variant_build/classes" \
        @"$variant_build/sources.txt"

    mapfile -d '' class_files < <(
        find "$variant_build/classes" -name '*.class' -print0
    )
    "$D8" --release --min-api 23 \
        --lib "$ANDROID_JAR" \
        --output "$variant_build/dex" \
        "${class_files[@]}"

    cp "$variant_build/resources-unsigned.apk" "$variant_build/unsigned.apk"
    (
        cd "$variant_build/dex"
        zip -q -j "$variant_build/unsigned.apk" classes.dex
    )

    "$ZIPALIGN" -f -p 4 \
        "$variant_build/unsigned.apk" \
        "$variant_build/aligned.apk"

    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias androiddebugkey \
        --ks-pass pass:android \
        --key-pass pass:android \
        --v1-signing-enabled true \
        --v2-signing-enabled true \
        --v3-signing-enabled true \
        --out "$DIST_DIR/$output_name" \
        "$variant_build/aligned.apk"

    "$APKSIGNER" verify --verbose "$DIST_DIR/$output_name" > /dev/null
    "$ZIPALIGN" -c -v 4 "$DIST_DIR/$output_name" > /dev/null
    sha256sum "$DIST_DIR/$output_name"
done

echo "Split APKs written to $DIST_DIR"
