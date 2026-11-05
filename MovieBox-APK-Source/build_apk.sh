#!/bin/bash
# MovieBox HD APK build script (no Gradle, raw SDK tools)
set -e
export JAVA_HOME=/tmp/jdk/jdk-21.0.13+9
export PATH=$JAVA_HOME/bin:$PATH

PROJ=/home/z/my-project/scripts/apk
BT=/tmp/sdk/android-VanillaIceCream   # build-tools 35 (newer d8 works on JDK 21)
PLAT=/tmp/sdk/android-34              # platform android-34 (android.jar)
BUILD=$PROJ/build
OUT=/home/z/my-project/download

cd $PROJ
rm -rf $BUILD && mkdir -p $BUILD/classes $BUILD/dex

echo "== 1. aapt2 compile resources =="
$BT/aapt2 compile --dir res -o $BUILD/res.zip

echo "== 2. aapt2 link =="
$BT/aapt2 link -o $BUILD/base.apk \
  -I $PLAT/android.jar \
  --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 3 --version-name 1.2 \
  -R $BUILD/res.zip \
  --auto-add-overlay

echo "== 3. javac compile =="
find src -name "*.java" > $BUILD/sources.txt
if ! $JAVA_HOME/bin/javac -source 8 -target 8 \
  -bootclasspath $PLAT/android.jar \
  -classpath $PLAT/android.jar \
  -d $BUILD/classes \
  @$BUILD/sources.txt > $BUILD/javac.log 2>&1; then
  echo "JAVAC FAILED:"; grep "error:" $BUILD/javac.log | head -20; exit 1
fi
echo "classes: $(find $BUILD/classes -name '*.class' | wc -l)"

echo "== 4. d8 dex =="
$JAVA_HOME/bin/jar cf $BUILD/classes.jar -C $BUILD/classes .
$BT/d8 --release --min-api 21 --lib $PLAT/android.jar \
  --output $BUILD/dex \
  $BUILD/classes.jar

echo "== 5. add dex to apk =="
cd $BUILD/dex && zip -q -j $BUILD/base.apk classes.dex && cd $PROJ

echo "== 6. zipalign =="
$BT/zipalign -f 4 $BUILD/base.apk $BUILD/aligned.apk

echo "== 7. keystore + sign =="
if [ ! -f $BUILD/mb.keystore ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore $BUILD/mb.keystore -alias mb \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass moviebox123 -keypass moviebox123 \
    -dname "CN=MovieBox HD, OU=MovieBox, O=MovieBox, L=Karachi, C=PK" \
    >/dev/null 2>&1
fi
$BT/apksigner sign --ks $BUILD/mb.keystore \
  --ks-pass pass:moviebox123 --key-pass pass:moviebox123 \
  --out $OUT/MovieBox-HD.apk $BUILD/aligned.apk

echo "== 8. verify =="
$BT/apksigner verify --print-certs $OUT/MovieBox-HD.apk | head -5
ls -lh $OUT/MovieBox-HD.apk
echo "BUILD OK"
