#!/usr/bin/env bash
# Build SimpleDownloader.apk without Gradle, using only the Android SDK
# command-line tools (aapt2 + javac + d8 + zipalign + apksigner).
#
# Requirements: JDK 17+, Android SDK with platform android-34 and
# build-tools 34.0.0. Set ANDROID_HOME if the SDK is not in ~/android-sdk.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"
APP_DIR="$(cd "$(dirname "$0")" && pwd)/app"
OUT="$(cd "$(dirname "$0")" && pwd)/build"
KEYSTORE="$(cd "$(dirname "$0")" && pwd)/debug.keystore"

mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/res-compiled"

# 1. Compile resources and generate R.java
"$BT/aapt2" compile --dir "$APP_DIR/src/main/res" -o "$OUT/res-compiled"
"$BT/aapt2" link \
    -o "$OUT/app-unsigned.apk" \
    -I "$PLATFORM" \
    --manifest "$APP_DIR/src/main/AndroidManifest.xml" \
    --java "$OUT/gen" \
    "$OUT/res-compiled"/*.flat

# 2. Compile Java
find "$APP_DIR/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -cp "$PLATFORM" \
    -d "$OUT/classes" @"$OUT/sources.txt"

# 3. Dex
"$BT/d8" --lib "$PLATFORM" --output "$OUT" $(find "$OUT/classes" -name '*.class')

# 4. Put classes.dex into the APK, align, sign
cd "$OUT"
zip -q app-unsigned.apk classes.dex
"$BT/zipalign" -f 4 app-unsigned.apk app-aligned.apk

if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out SimpleDownloader.apk app-aligned.apk

echo "Built: $OUT/SimpleDownloader.apk"
"$BT/apksigner" verify --print-certs "$OUT/SimpleDownloader.apk" | head -3
ls -lh "$OUT/SimpleDownloader.apk"
