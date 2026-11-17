#!/usr/bin/env bash
# An Android 34 emulator at the Galaxy Tab S10 FE's size (2304x1440), headless,
# to try what the tests cannot run (the front buffer) with adb's simulated pen.
#
# It lives in ~/.cache/cuaderno-emulator (or $CUADERNO_EMULATOR), not in the
# session scratchpad: /tmp has a ~3 GB quota and the system image unpacks to
# over 4 GB. Outside the repo and the PATH; delete the folder to remove it.
# Installing needs the toolchain in the environment (see CLAUDE.md).
#
# Usage: tools/emulator.sh start        install if missing, boot, install and open the debug APK
#        tools/emulator.sh shot FILE    screenshot to a PNG
#        tools/emulator.sh adb ARGS...  any adb command against it
#        tools/emulator.sh stop
#
# The pen, one event at a time (about 50 ms each), so a held pen can be tried:
#   tools/emulator.sh adb shell "input stylus motionevent DOWN 300 500; \
#       input stylus motionevent MOVE 600 510; sleep 1; input stylus motionevent UP 600 510"
set -euo pipefail
cd "$(dirname "$0")/.."

HOME_DIR=$(realpath -m "${CUADERNO_EMULATOR:-$HOME/.cache/cuaderno-emulator}")
SDK="$HOME_DIR/sdk"
IMAGE="system-images;android-34;google_apis;x86_64"
export ANDROID_SDK_ROOT="$SDK" ANDROID_AVD_HOME="$HOME_DIR/avd"
ADB="$SDK/platform-tools/adb"

install() {
    [ -x "$SDK/emulator/emulator" ] && [ -d "$SDK/system-images/android-34/google_apis/x86_64" ] && return
    : "${ANDROID_HOME:?run eval \"\$(tools/toolchain.sh DIR)\" first}"
    mkdir -p "$SDK"
    cp -r "$ANDROID_HOME/licenses" "$SDK/"
    echo "downloading the emulator and the Android 34 image (about 1.5 GB) to $HOME_DIR" >&2
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" \
        "emulator" "platform-tools" "$IMAGE" > "$HOME_DIR/install.log"
}

# Written by hand: avdmanager only looks for images in its own SDK.
define() {
    mkdir -p "$ANDROID_AVD_HOME/tab.avd"
    printf 'avd.ini.encoding=UTF-8\npath=%s\ntarget=android-34\n' "$ANDROID_AVD_HOME/tab.avd" > "$ANDROID_AVD_HOME/tab.ini"
    cat > "$ANDROID_AVD_HOME/tab.avd/config.ini" <<EOF
AvdId=tab
avd.ini.displayname=tab
abi.type=x86_64
hw.cpu.arch=x86_64
image.sysdir.1=system-images/android-34/google_apis/x86_64/
tag.id=google_apis
hw.lcd.width=2304
hw.lcd.height=1440
hw.lcd.density=240
hw.ramSize=3072
hw.keyboard=yes
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
disk.dataPartition.size=4G
showDeviceFrame=no
EOF
}

booted() { [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

case "${1:-}" in
start)
    install
    [ -f "$ANDROID_AVD_HOME/tab.avd/config.ini" ] || define
    if ! booted; then
        # ANDROID_HOME too: the toolchain's points at the scratchpad SDK, which has no image.
        ANDROID_HOME="$SDK" nohup "$SDK/emulator/emulator" -avd tab -no-window -no-audio -no-boot-anim -no-snapshot \
            > "$HOME_DIR/emulator.log" 2>&1 &
        PID=$!
        for _ in $(seq 1 60); do booted && break; kill -0 $PID 2>/dev/null || break; sleep 5; done
        booted || { echo "did not boot: see $HOME_DIR/emulator.log"; exit 1; }
    fi
    # No "Viewing full screen" hint over the page.
    "$ADB" shell settings put secure immersive_mode_confirmations confirmed
    APK=app/build/outputs/apk/debug/app-debug.apk
    if [ -f "$APK" ]; then
        "$ADB" install -r "$APK" > /dev/null
        "$ADB" shell monkey -p com.cuaderno.app -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
        echo "running $APK"
    else
        echo "booted; no debug APK yet (gradle assembleDebug)"
    fi
    ;;
shot) "$ADB" exec-out screencap -p > "${2:?usage: tools/emulator.sh shot FILE}" ;;
adb) shift; "$ADB" "$@" ;;
stop) "$ADB" emu kill > /dev/null 2>&1 || true ;;
*) sed -n '2,17p' "$0"; exit 1 ;;
esac
