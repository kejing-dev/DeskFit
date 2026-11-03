#!/usr/bin/env bash
# Desk Fit developer helper (macOS, mac1). Every device command is pinned to emulator-5554 (AVD Headline_QA_6_1).
#
#   tools/dev.sh build                 assembleDebug -> .scratch/build.log (one Gradle daemon, 2 workers)
#   tools/dev.sh test                  JVM unit tests
#   tools/dev.sh stop                  ./gradlew --stop (do this before installing / capturing: host memory is tight)
#   tools/dev.sh install | launch | kill | relaunch
#   tools/dev.sh cmd NAME [EXTRAS...]  debug broadcast, e.g. cmd state --es id S9 ; cmd eye --ef cm 118
#   tools/dev.sh log [REGEX] [LINES]   logcat, tag DeskFit
#   tools/dev.sh crash                 last crash lines of the app
#   tools/dev.sh shot NAME             screenshot -> .scratch/shots/NAME.png (+ NAME_small.png, 1400 px wide)
DEV=${DEV:-emulator-5554}
AVD=${AVD:-Headline_QA_6_1}
PKG=io.embodit.deskfit
TAG=DeskFit
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB=/Users/bytedance/Library/Android/sdk/platform-tools/adb
export JAVA_HOME="$HOME/.pico/primer-cli/jdk/jdk-21.0.12.1+1/Contents/Home"
export PICO_HOME="$HOME/Library/PICO/sdk"
export PATH="$HOME/.nvm/versions/node/v20.17.0/bin:$JAVA_HOME/bin:$PATH"
mkdir -p "$ROOT/.scratch/shots"

check() {
  avd="$($ADB -s $DEV emu avd name 2>/dev/null | head -1 | tr -d '\r')"
  if [ "$avd" != "$AVD" ]; then echo "WRONG AVD on $DEV: '$avd' (expected $AVD)" >&2; exit 2; fi
}

case "${1:-}" in
  build)
    cd "$ROOT" && ./gradlew assembleDebug --console=plain > .scratch/build.log 2>&1
    echo "exit=$?"; grep -E '^e: |error:|BUILD|FAILED|What went wrong' .scratch/build.log | head -40 ;;
  test)
    cd "$ROOT" && ./gradlew :app:testDebugUnitTest --console=plain > .scratch/unit.log 2>&1
    echo "exit=$?"; grep -E '^e: |FAILED|BUILD|tests completed' .scratch/unit.log | head -40 ;;
  stop) cd "$ROOT" && ./gradlew --stop ;;
  install) check; $ADB -s $DEV install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" 2>&1 | tail -2 ;;
  launch) check; $ADB -s $DEV logcat -c; $ADB -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 2>&1 | tail -1 ;;
  kill) check; $ADB -s $DEV shell am force-stop $PKG; echo stopped ;;
  relaunch) check; $ADB -s $DEV shell am force-stop $PKG; sleep 1; $ADB -s $DEV logcat -c; $ADB -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 2>&1 | tail -1 ;;
  cmd)
    check; shift; name="${1:?cmd NAME}"; shift
    $ADB -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $name $*" | tail -1 | tr -d '\r' ;;
  log) check; $ADB -s $DEV logcat -d -v time -s $TAG:V 2>/dev/null | grep -E -- "${2:-.}" | tail -n "${3:-200}" | cut -c1-600 ;;
  crash) check; $ADB -s $DEV logcat -d -v time 2>/dev/null | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $PKG" | tail -n 40 | cut -c1-400 ;;
  shot)
    check; name="${2:?shot NAME}"; out="$ROOT/.scratch/shots/$name.png"
    $ADB -s $DEV exec-out screencap -p > "$out" && sips --resampleWidth 1400 "$out" --out "$ROOT/.scratch/shots/${name}_small.png" >/dev/null && echo "OK $out" ;;
  *) echo "usage: dev.sh build|test|stop|install|launch|kill|relaunch|cmd|log|crash|shot" ;;
esac
