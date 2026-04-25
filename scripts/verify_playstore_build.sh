#!/bin/bash
# Verify PlayStore Release Build
# This script builds and verifies the PlayStore release APK

set -e

echo "========================================="
echo "PlayStore Release Build Verification"
echo "========================================="

# Check if keystore exists for signing (CI or local)
if [ ! -f "keystore.jks" ] && [ ! -f "app/keystore.jks" ]; then
    echo "WARNING: No keystore found. Build will be unsigned."
    echo "For CI builds, ensure ANDROID_KEYSTORE_BASE64 is set."
fi

echo ""
echo "Building PlayStore Release AAB..."
./gradlew bundlePlaystoreRelease --stacktrace

echo ""
echo "Verifying AAB exists..."
AAB_PATH="app/build/outputs/bundle/playstoreRelease/app-playstore-release.aab"
if [ ! -f "$AAB_PATH" ]; then
    echo "ERROR: AAB not found at $AAB_PATH"
    exit 1
fi
echo "✓ AAB found: $AAB_PATH"

echo ""
echo "Checking AAB size..."
AAB_SIZE=$(du -h "$AAB_PATH" | cut -f1)
echo "  Size: $AAB_SIZE"

# Check if AAB is signed (applies to release builds with signing config)
echo ""
echo "Checking if AAB is signed..."
if command -v jarsigner &> /dev/null; then
    if jarsigner -verify -verbose "$AAB_PATH" 2>&1 | grep -q "jar verified"; then
        echo "✓ AAB is signed"
    else
        echo "⚠ AAB may not be properly signed"
    fi
else
    echo "  (jarsigner not available, skipping signature check)"
fi

echo ""
echo "Building PlayStore Release APK for testing..."
./gradlew assemblePlaystoreRelease --stacktrace

echo ""
echo "Verifying APK exists..."
APK_PATH="app/build/outputs/apk/playstore/release/"
if [ -d "$APK_PATH" ] && [ "$(ls -A $APK_PATH/*.apk 2>/dev/null)" ]; then
    echo "✓ APK(s) found in $APK_PATH"
    ls -lh $APK_PATH/*.apk
else
    echo "ERROR: APK not found in $APK_PATH"
    exit 1
fi

echo ""
echo "========================================="
echo "PlayStore Release Build Verified!"
echo "========================================="
echo "Next steps:"
echo "  1. Install APK on device: adb install $APK_PATH*.apk"
echo "  2. Test app launch immediately"
echo "  3. Check logcat for crashes: adb logcat | grep AndroidRuntime"
echo ""
