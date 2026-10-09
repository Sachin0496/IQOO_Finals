#!/usr/bin/env bash
# Run Mouna on a Mac without a phone: arm64 Android 15 emulator (Hypervisor.framework, native on Apple Silicon).
# MediaPipe ships arm64 but not x86_64 libraries, so the image must be arm64-v8a. QNN (NPU) cannot run here: the app
# falls back to the CPU encoder or lip landmarks and says so on screen.
#
#   scripts/emulator.sh              # headless, Mac's built-in camera as the front camera
#   scripts/emulator.sh window       # with a window (keep it visible: macOS App Nap stalls a hidden emulator)
#   CAMERA=emulated scripts/emulator.sh   # the emulator's virtual room instead of a webcam (no face, UI checks only)
#
# The webcam needs macOS camera permission for the app that started the emulator (System Settings → Privacy &
# Security → Camera). Without it the camera HAL gets no frames ("Unable to obtain video frame from the camera").
set -euo pipefail
# Use ANDROID_HOME only if it really has an emulator (shell profiles often point at a removed Android Studio SDK).
SDK=/opt/homebrew/share/android-commandlinetools
[ -x "${ANDROID_HOME:-}/emulator/emulator" ] && SDK="$ANDROID_HOME"
IMAGE="system-images;android-35;google_apis;arm64-v8a"
AVD=mouna35
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
# sdkmanager/avdmanager need a JDK 17-21; ignore a JAVA_HOME left pointing at a removed Android Studio.
[ -x "${JAVA_HOME:-}/bin/java" ] || export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$SDK/cmdline-tools/latest/bin:$PATH"
EMU="$SDK/emulator/emulator"
ADB="$SDK/platform-tools/adb"

if [ ! -d "$SDK/system-images/android-35/google_apis/arm64-v8a" ]; then
  sdkmanager --sdk_root="$SDK" emulator platform-tools "$IMAGE"
fi
if [ ! -d "$HOME/.android/avd/$AVD.avd" ]; then
  echo no | avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_7 --force
  # 3 GB data partition: the default 12 GB fails on a nearly full disk.
  printf 'hw.ramSize=4096\ndisk.dataPartition.size=3G\nhw.keyboard=yes\n' >> "$HOME/.android/avd/$AVD.avd/config.ini"
fi

# The built-in camera, not a virtual one (Iriun, OBS, Camo… often take webcam0 and deliver nothing).
CAMERA="${CAMERA:-$("$EMU" -webcam-list 2>/dev/null | grep -E "MacBook|FaceTime|Built-in" | grep -oE "webcam[0-9]+" | head -1)}"
CAMERA="${CAMERA:-webcam0}"

ARGS=(-avd "$AVD" -camera-front "$CAMERA" -camera-back none -no-snapshot -no-audio -no-boot-anim)
if [ "${1:-}" = window ]; then ARGS+=(-gpu host); else ARGS+=(-no-window -gpu swiftshader_indirect); fi
echo "starting $AVD with front camera $CAMERA"
nohup "$EMU" "${ARGS[@]}" > "${TMPDIR:-/tmp}/mouna-emulator.log" 2>&1 &
"$ADB" wait-for-device
until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
"$ADB" shell settings put global window_animation_scale 0
"$ADB" shell settings put global transition_animation_scale 0
echo "emulator ready: adb install -r -g app/build/outputs/apk/debug/app-debug.apk"
