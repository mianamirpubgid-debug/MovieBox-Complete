#!/bin/bash
# MovieBox HD v2 APK build script (no Gradle, raw SDK tools + media3)
set -e
export JAVA_HOME=/tmp/jdk/jdk-21.0.13+9
export PATH=$JAVA_HOME/bin:$PATH

PROJ=/home/z/my-project/scripts/apk
BT=/tmp/sdk/android-VanillaIceCream   # build-tools 35
PLAT=/tmp/sdk/android-34              # platform android-34 (android.jar)
LIBS=$PROJ/libs
EXT=$LIBS/extracted
BUILD=$PROJ/build
OUT=/home/z/my-project/download

cd $PROJ
rm -rf $BUILD && mkdir -p $BUILD/classes $BUILD/dex

echo "== 1. aapt2 compile app resources =="
$BT/aapt2 compile --dir res -o $BUILD/res.zip

echo "== 1b. aapt2 compile media3-exoplayer resources =="
$BT/aapt2 compile --dir $EXT/exoplayer-1.3.1.aar/res -o $BUILD/common_res.zip

echo "== 2. aapt2 link =="
mkdir -p $BUILD/gen
$BT/aapt2 link -o $BUILD/base.apk \
  -I $PLAT/android.jar \
  --manifest AndroidManifest.xml \
  --java $BUILD/gen \
  --min-sdk-version 21 --target-sdk-version 34 \
  --version-code 4 --version-name 2.0 \
  -R $BUILD/res.zip \
  -R $BUILD/common_res.zip \
  --auto-add-overlay

echo "== 3. javac compile =="
CP="$PLAT/android.jar"
for j in $EXT/*/classes.jar; do CP="$CP:$j"; done
CP="$CP:$LIBS/guava.jar:$LIBS/checker.jar:$LIBS/jsr305.jar"
find src -name "*.java" > $BUILD/sources.txt
find $BUILD/gen -name "*.java" >> $BUILD/sources.txt
if ! $JAVA_HOME/bin/javac -source 8 -target 8 \
  -classpath $CP \
  -d $BUILD/classes \
  @$BUILD/sources.txt > $BUILD/javac.log 2>&1; then
  echo "JAVAC FAILED:"; grep "error:" $BUILD/javac.log | head -25; exit 1
fi
echo "classes: $(find $BUILD/classes -name '*.class' | wc -l)"

echo "== 4. d8 dex (multidex with media3 + guava) =="
$JAVA_HOME/bin/jar cf $BUILD/classes.jar -C $BUILD/classes .
D8_INPUTS="$BUILD/classes.jar"
for j in $EXT/*/classes.jar; do D8_INPUTS="$D8_INPUTS $j"; done
D8_INPUTS="$D8_INPUTS $LIBS/guava.jar $LIBS/checker.jar $LIBS/jsr305.jar"
$BT/d8 --release --min-api 21 --lib $PLAT/android.jar \
  --output $BUILD/dex \
  $D8_INPUTS 2> $BUILD/d8.log || { echo "D8 FAILED:"; tail -20 $BUILD/d8.log; exit 1; }
ls $BUILD/dex/

echo "== 5. add dex files to apk =="
cd $BUILD/dex
for f in classes*.dex; do zip -q -j $BUILD/base.apk "$f"; done
cd $PROJ

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
$BT/apksigner verify --print-certs $OUT/MovieBox-HD.apk | head -2
ls -lh $OUT/MovieBox-HD.apk
echo "BUILD OK"
