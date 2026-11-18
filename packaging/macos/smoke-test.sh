#!/bin/bash
# Checks a built Tracker-Rust.app with its own (Intel) Java runtime, so on Apple
# silicon everything runs through Rosetta 2, as for users:
#
#   packaging/macos/smoke-test.sh path/to/Tracker-Rust.app path/to/bench-classes
#
# 1. the native matcher gives the same matches as on Windows (Bench signatures)
# 2. the checkerboard detector and calibration geometry (PatternCheck)
# 3. Xuggle writes and reads a video; read-ahead decoding gives identical frames
# 4. the app itself starts and stays up
set -uo pipefail

APP="$(cd "$1" && pwd -P)"
BENCH="$(cd "$2" && pwd -P)"
C="$APP/Contents"
JAVA="$C/Resources/jre/Contents/Home/bin/java"
CP="$BENCH:$C/Java/tracker-native.jar:$C/Java/Xuggle/xuggle-xuggler-server-all.jar:$C/Java/Xuggle/slf4j-api.jar"
WORK="$(mktemp -d)"
fail=0
check() { if [ "$1" = 0 ]; then echo "PASS  $2"; else echo "FAIL  $2"; fail=1; fi; }

echo "machine: $(uname -m), macOS $(sw_vers -productVersion)"
file "$JAVA" "$C/Java/libtmnative.dylib"
"$JAVA" -XshowSettings:properties -version 2>&1 | grep -E "os.arch|java.version " | sed 's/^ */  /'

echo "== 1. matcher"
"$JAVA" -cp "$CP" Bench mac 7 0 >"$WORK/bench.txt" 2>&1
cat "$WORK/bench.txt"
grep -q "native library loaded" "$WORK/bench.txt"
check $? "native library loads"
bad=$(grep -E "^mac" "$WORK/bench.txt" | grep -cvE "sig=(74f1b8e8|88dda8e0|e825850f|525a6707|4e68125c)")
n=$(grep -cE "^mac" "$WORK/bench.txt")
[ "$n" -ge 10 ] && [ "$bad" = 0 ]
check $? "matches identical to Windows / stock Tracker ($n results)"

echo "== 2. checkerboard"
"$JAVA" -cp "$CP" PatternCheck >"$WORK/pattern.txt" 2>&1
cat "$WORK/pattern.txt"
rows=$(grep -cE "\| +54 9x6" "$WORK/pattern.txt")
[ "$rows" = 7 ]
check $? "all 54 corners found in all 7 views"

echo "== 3. video"
"$JAVA" -cp "$CP" MakeVideo "$WORK/test.mp4" 120 2>&1 | grep -v SLF4J
for mode in true false; do
	"$JAVA" -Dtracker.patch.on=$mode -cp "$CP" org.opensourcephysics.media.xuggle.PrefetchCheck "$WORK/test.mp4" \
		2>"$WORK/pf_$mode.err" | grep '^frame' >"$WORK/pf_$mode.txt"
	grep -h "frames=" "$WORK/pf_$mode.err"
done
[ "$(wc -l <"$WORK/pf_true.txt")" -ge 200 ] && ! grep -q null "$WORK/pf_true.txt"
check $? "Xuggle decodes every frame"
cmp -s "$WORK/pf_true.txt" "$WORK/pf_false.txt"
check $? "read-ahead decoding gives identical frames"
[ "$(awk '{print $3}' "$WORK/pf_true.txt" | sort -u | wc -l)" -eq 120 ]
check $? "120 distinct frames"

echo "== 4. app start"
"$C/MacOS/Tracker-Rust" >"$WORK/app.log" 2>&1 &
pid=$!
sleep 30
if kill -0 $pid 2>/dev/null; then
	check 0 "Tracker-Rust is running after 30 s"
	kill $pid 2>/dev/null
	sleep 2
	kill -9 $pid 2>/dev/null
else
	wait $pid
	check 1 "Tracker-Rust is running after 30 s (exited with $?)"
fi
grep -iE "exception|error" "$WORK/app.log" | grep -v SLF4J | head -20

rm -rf "$WORK"
exit $fail
