#!/bin/bash
# Exit on error
set -e

echo "🧹 Cleaning and compiling LAN Beam Android..."
./gradlew clean assembleDebug

# Extract version name from app/build.gradle.kts
VERSION_NAME=$(grep -o "versionName = \".*\"" app/build.gradle.kts | cut -d '"' -f 2)
if [ -z "$VERSION_NAME" ]; then
  VERSION_NAME="1.1" # Fallback
fi

mkdir -p apks
cp app/build/outputs/apk/debug/app-debug.apk "apks/LAN-Beam-v${VERSION_NAME}.apk"

echo "✅ Build successful!"
echo "📦 Output copied to: apks/LAN-Beam-v${VERSION_NAME}.apk"
