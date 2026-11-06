#!/bin/bash
# Merged APK build: Netflix Ke Pitaji wrapper + MovieBox data layer
# (their app, unchanged logic + additive MovieBox bridge)
set -e
export JAVA_HOME=/home/z/my-project/scripts/tools/jdk-21.0.5+11
export PATH=$JAVA_HOME/bin:$PATH

PROJ=/home/z/my-project/scripts/mergedapk
BT=/home/z/my-project/scripts/tools/bt35/android-VanillaIceCream
PLAT=/home/z/my-project/scripts/tools/plat/android-34/android.jar
BUILD=$PROJ/build
OUT=/home/z/my-project/download

cd $PROJ
rm -rf $BUILD && mkdir -p $BUILD/classes $BUILD/gen

echo "== 1. aapt2 compile resources =="
$BT/aapt2 compile --dir res -o $BUILD/res.zip

echo "== 2. aapt2 link (manifest + res + assets) =="
$BT/aapt2 link -o $BUILD/base.apk \
  -I $PLAT \
  --manifest AndroidManifest.xml \
  --java $BUILD/gen \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 11 --version-name 1.9.1 \
  -A assets \
  $BUILD/res.zip

echo "== 3. javac =="
find src -name "*.java" > $BUILD/sources.txt
$JAVA_HOME/bin/javac -source 8 -target 8 \
  -classpath $PLAT \
  -d $BUILD/classes \
  @$BUILD/sources.txt > $BUILD/javac.log 2>&1 || {
    echo "JAVAC FAILED:"; grep "error:" $BUILD/javac.log | head -25; exit 1;
  }
echo "classes: $(find $BUILD/classes -name '*.class' | wc -l)"

echo "== 4. d8 dex =="
$JAVA_HOME/bin/jar cf $BUILD/classes.jar -C $BUILD/classes .
$BT/d8 --release --min-api 21 --lib $PLAT \
  --output $BUILD $BUILD/classes.jar 2> $BUILD/d8.log || { echo "D8 FAILED"; tail -20 $BUILD/d8.log; exit 1; }
ls $BUILD/*.dex

echo "== 5. add dex to apk =="
cd $BUILD
zip -q -j base.apk classes.dex
cd $PROJ

echo "== 6. zipalign =="
$BT/zipalign -f 4 $BUILD/base.apk $BUILD/aligned.apk

echo "== 7. keystore + sign =="
if [ ! -f $BUILD/mb.keystore ]; then
  $JAVA_HOME/bin/keytool -genkeypair -keystore $BUILD/mb.keystore -alias mb \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass moviebox123 -keypass moviebox123 \
    -dname "CN=Netflix Clone MB, OU=Amir, O=Amir, L=Lahore, C=PK" \
    >/dev/null 2>&1
fi
$BT/apksigner sign --ks $BUILD/mb.keystore \
  --ks-pass pass:moviebox123 --key-pass pass:moviebox123 \
  --out $OUT/Netflix-KePitaji-MovieBox.apk $BUILD/aligned.apk

echo "== 8. verify =="
$BT/apksigner verify --print-certs $OUT/Netflix-KePitaji-MovieBox.apk | head -3
$BT/aapt2 dump badging $OUT/Netflix-KePitaji-MovieBox.apk 2>/dev/null | head -6
ls -lh $OUT/Netflix-KePitaji-MovieBox.apk
echo "BUILD OK"
