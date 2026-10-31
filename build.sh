#!/bin/bash
# Builds FireRemote.apk with the Debian/Ubuntu Android tools (aapt, dx, zipalign, apksigner).
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
[ -f "$ANDROID_JAR" ] || ANDROID_JAR=$(ls /usr/lib/android-sdk/platforms/*/android.jar | head -1)

rm -rf build && mkdir -p build/classes build/gen
python3 make_icon.py res

aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F build/unsigned.apk
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
      -d build/classes $(find src build/gen -name '*.java')
dalvik-exchange --dex --min-sdk-version=21 --output=build/classes.dex build/classes
(cd build && aapt add unsigned.apk classes.dex >/dev/null)

zipalign -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f release.keystore ]; then
  keytool -genkeypair -keystore release.keystore -storepass fireremote -keypass fireremote \
          -alias fireremote -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=Fire Remote, O=Personal, C=GB" >/dev/null 2>&1
fi
apksigner sign --ks release.keystore --ks-pass pass:fireremote --key-pass pass:fireremote \
          --ks-key-alias fireremote --min-sdk-version 21 --out FireRemote.apk build/aligned.apk
apksigner verify --verbose FireRemote.apk | head -5
ls -la FireRemote.apk
