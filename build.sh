#!/bin/bash
set -e
# Diller — build the APK straight from source with the Android SDK build-tools (no Gradle).
# Requirements: JAVA_HOME -> JDK 17, ANDROID_HOME -> SDK with build-tools;34.0.0 and platforms;android-34
APP="$(cd "$(dirname "$0")" && pwd)"
BUILD="$APP/build"
BT="$ANDROID_HOME/build-tools/34.0.0"
AJAR="$ANDROID_HOME/platforms/android-34/android.jar"
rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> [1/7] compile resources"
"$BT/aapt2" compile --dir "$APP/app/res" -o "$BUILD/res.zip"

echo "==> [2/7] link resources + generate R.java"
"$BT/aapt2" link -o "$BUILD/app.ap_" -I "$AJAR" \
  --manifest "$APP/app/AndroidManifest.xml" --java "$BUILD/gen" \
  --min-sdk-version 26 --target-sdk-version 34 --auto-add-overlay "$BUILD/res.zip"

echo "==> [3/7] javac"
javac -source 8 -target 8 -nowarn -encoding UTF-8 -cp "$AJAR" -d "$BUILD/classes" \
  $(find "$APP/app/src" "$BUILD/gen" -name '*.java')

echo "==> [4/7] dex (d8)"
( cd "$BUILD/classes" && jar cf "$BUILD/classes.jar" . )
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"

echo "==> [5/7] package apk"
cp "$BUILD/app.ap_" "$BUILD/app.apk"
python3 - "$BUILD" <<'PY'
import zipfile, sys, os
b = sys.argv[1]
apk = os.path.join(b, "app.apk")
dex = os.path.join(b, "dex", "classes.dex")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "==> [6/7] zipalign"
"$BT/zipalign" -f -p 4 "$BUILD/app.apk" "$BUILD/app-aligned.apk"

echo "==> [7/7] sign"
KS="$APP/diller.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias diller -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass dillerpass -keypass dillerpass \
    -dname "CN=Diller, OU=Mobile, O=Diller, L=Bengaluru, ST=Karnataka, C=IN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:dillerpass --key-pass pass:dillerpass \
  --out "$BUILD/Diller.apk" "$BUILD/app-aligned.apk"
echo "==> APK: $BUILD/Diller.apk"
