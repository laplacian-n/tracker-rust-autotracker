#!/bin/bash
# Builds build/macos/Tracker-Rust-<version>-macOS.dmg on a Mac (Apple silicon or
# Intel) with Xcode command line tools, Rust and a JDK 17+:
#
#   VERSION=1.2.0 packaging/macos/build-macos.sh
#
# The app is Intel (x86_64) code because Tracker's video library, Xuggle, exists
# only for Intel Macs; on Apple silicon it runs through Rosetta 2. Downloads (and
# checks) Tracker 6.3.5's tracker.jar and Xuggle from the Open Source Physics
# repositories and the Eclipse Temurin 21 runtime for Intel Macs.
set -euo pipefail

VERSION="${VERSION:-dev}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd -P)"
OUT="$ROOT/build/macos"
DEPS="$OUT/deps"
APP="$OUT/Tracker-Rust.app"
export MACOSX_DEPLOYMENT_TARGET=10.15
mkdir -p "$DEPS"

fetch() { # url file sha256
	if [ ! -f "$2" ] || ! echo "$3  $2" | shasum -a 256 -c - >/dev/null 2>&1; then
		curl -fsSL --retry 4 --retry-delay 5 -o "$2" "$1"
	fi
	echo "$3  $2" | shasum -a 256 -c -
}

echo "== Tracker 6.3.5 jars"
fetch https://raw.githubusercontent.com/OpenSourcePhysics/tracker-website/main/docs/archives/tracker-6.3.5.jar \
	"$DEPS/tracker.jar" 3b655f4370613afa5c4224c8e2e435d21a1f1fdcc28274937890feb90e1460d0
fetch https://raw.githubusercontent.com/OpenSourcePhysics/tracker/version_6.3.5/libraries/xuggle-xuggler-server-all.jar \
	"$DEPS/xuggle-xuggler-server-all.jar" 5caff5b64a5313319b2ffe9c0e2fffef2dcc1a36b9a94b3990376866a7594524
fetch https://raw.githubusercontent.com/OpenSourcePhysics/tracker/version_6.3.5/libraries/slf4j-api.jar \
	"$DEPS/slf4j-api.jar" 1212418dbc2cc148e221f52a8f3027e8b96e0a05eb93048498e9d5210e518fa9

echo "== Temurin 21 JRE (macOS x64)"
read -r JRE_URL JRE_SHA JRE_NAME < <(curl -fsSL --retry 4 \
	"https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=x64&image_type=jre&os=mac&vendor=eclipse" |
	python3 -c 'import json,sys; p=json.load(sys.stdin)[0]["binary"]["package"]; print(p["link"], p["checksum"], p["name"])')
fetch "$JRE_URL" "$DEPS/$JRE_NAME" "$JRE_SHA"

echo "== tmnative (x86_64-apple-darwin)"
export CARGO_TARGET_DIR="$OUT/target"
export RUSTFLAGS="--remap-path-prefix=$HOME=~ --remap-path-prefix=$ROOT=."
rustup target add x86_64-apple-darwin >/dev/null 2>&1 || true
(cd "$ROOT/tmnative" && cargo build --release --target x86_64-apple-darwin)
DYLIB="$CARGO_TARGET_DIR/x86_64-apple-darwin/release/libtmnative.dylib"
file "$DYLIB"

echo "== Java classes and tracker-native.jar"
rm -rf "$OUT/classes" "$OUT/bench"
mkdir -p "$OUT/classes" "$OUT/bench"
CP="$DEPS/tracker.jar:$DEPS/xuggle-xuggler-server-all.jar"
find "$ROOT/java/src" -name '*.java' >"$OUT/sources.txt"
javac --release 17 -nowarn -encoding UTF-8 -cp "$CP" -d "$OUT/classes" @"$OUT/sources.txt"
cp "$DEPS/tracker.jar" "$OUT/tracker-native.jar"
jar uf "$OUT/tracker-native.jar" -C "$OUT/classes" .
# test programs (used by smoke-test.sh, not part of the app)
javac --release 17 -nowarn -encoding UTF-8 -cp "$OUT/classes:$CP" -d "$OUT/bench" \
	"$ROOT/java/bench/Bench.java" "$ROOT/java/bench/PatternCheck.java" "$ROOT/java/bench/MakeVideo.java" \
	"$ROOT/java/bench/org/opensourcephysics/media/xuggle/PrefetchCheck.java"

echo "== Tracker-Rust.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Java/Xuggle" "$APP/Contents/Resources"
sed "s/@VERSION@/$VERSION/g" "$ROOT/packaging/macos/Info.plist" >"$APP/Contents/Info.plist"
printf 'APPL????' >"$APP/Contents/PkgInfo"
cp "$ROOT/packaging/macos/Tracker-Rust" "$APP/Contents/MacOS/Tracker-Rust"
chmod 755 "$APP/Contents/MacOS/Tracker-Rust"
cp "$OUT/tracker-native.jar" "$DYLIB" "$APP/Contents/Java/"
cp "$DEPS/xuggle-xuggler-server-all.jar" "$DEPS/slf4j-api.jar" "$APP/Contents/Java/Xuggle/"
mkdir -p "$OUT/jre-x"
rm -rf "$OUT/jre-x"/*
tar -xzf "$DEPS/$JRE_NAME" -C "$OUT/jre-x"
mv "$OUT/jre-x"/*/ "$APP/Contents/Resources/jre"
file "$APP/Contents/Resources/jre/Contents/Home/bin/java"

# icon from Tracker's own 256 px icon
ICONSET="$OUT/Tracker-Rust.iconset"
rm -rf "$ICONSET"
mkdir -p "$ICONSET"
unzip -p "$DEPS/tracker.jar" org/opensourcephysics/cabrillo/tracker/resources/images/tracker_icon_256.png >"$OUT/icon256.png"
for s in 16 32 64 128 256 512; do
	sips -z $s $s "$OUT/icon256.png" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
	h=$((s / 2))
	[ $h -ge 16 ] && cp "$ICONSET/icon_${s}x${s}.png" "$ICONSET/icon_${h}x${h}@2x.png"
done
rm -f "$ICONSET/icon_64x64.png" # not an iconset size (only as 32x32@2x)
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/Tracker-Rust.icns"

echo "== dmg"
STAGE="$OUT/dmg"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
sed "s/@VERSION@/$VERSION/g" "$ROOT/packaging/macos/README.txt" >"$STAGE/README.txt"
cp "$ROOT/LICENSE" "$STAGE/LICENSE.txt"
DMG="$OUT/Tracker-Rust-$VERSION-macOS.dmg"
rm -f "$DMG"
hdiutil create -volname "Tracker-Rust $VERSION" -srcfolder "$STAGE" -ov -format UDZO "$DMG" >/dev/null
ls -l "$DMG"
