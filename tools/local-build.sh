#!/bin/bash
# Builds the APK without Gradle (used where Google's Maven repo is unreachable).
# NOTE: this script has no dependency resolution, so it cannot build the embedded yt-dlp/FFmpeg
# media engine (io.github.junkfood02.youtubedl-android). Use Gradle: ./gradlew :app:assembleDebug
set -euo pipefail
if grep -q "youtubedl-android" app/build.gradle.kts && [ "${VF_FORCE_LOCAL:-}" != "1" ]; then
  echo "local-build.sh can't include the youtubedl-android library the app now depends on." >&2
  echo "Build with Gradle instead: ./gradlew :app:assembleDebug" >&2
  exit 1
fi
cd "$(dirname "$0")/.."
AJ=/opt/vfandroid/android-34.jar
KC=/opt/vfandroid/kotlinc/bin/kotlinc
B=build-local; rm -rf $B; mkdir -p $B/gen $B/classes $B/stdlib
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.voltfetch.app">#' app/src/main/AndroidManifest.xml > $B/AndroidManifest.xml
aapt2 compile --dir app/src/main/res -o $B/res.zip
aapt2 link -I $AJ --manifest $B/AndroidManifest.xml -o $B/base.apk --java $B/gen \
  --min-sdk-version 29 --target-sdk-version 34 --version-code ${VC:-1} --version-name ${VN:-1.0.0} --auto-add-overlay $B/res.zip
javac -nowarn -source 8 -target 8 -cp $AJ -d $B/classes $(find $B/gen -name '*.java') 2>&1 | grep -v "warning" || true
$KC -nowarn -jvm-target 1.8 -Xlambdas=class -Xsam-conversions=class -Xstring-concat=inline -cp $AJ:$B/classes -d $B/classes $(find app/src/main/java -name '*.kt') 2>&1 | grep -v "^warning" || true
test -f $B/classes/com/voltfetch/app/MainActivity.class
STD=$(dirname $KC)/../lib/kotlin-stdlib.jar
(cd $B/stdlib && unzip -qo $STD -x 'META-INF/*')
dalvik-exchange --dex --min-sdk-version=26 --output=$B/classes.dex $B/classes $B/stdlib
cp $B/base.apk $B/unsigned.apk; (cd $B && zip -qj unsigned.apk classes.dex)
zipalign -f -p 4 $B/unsigned.apk $B/aligned.apk
echo built $B/aligned.apk
